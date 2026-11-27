/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.android.bluetooth.bthelper

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import com.android.bluetooth.bthelper.pods.AccessoryControls
import com.android.bluetooth.bthelper.utils.DeviceSettingsDiscovery.Advertisement
import com.android.bluetooth.bthelper.utils.deviceSettingsAdvertisement
import com.android.settingslib.bluetooth.devicesettings.DeviceInfo
import com.android.settingslib.bluetooth.devicesettings.DeviceSetting
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingConfigOptions
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfigServiceStatus
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsProviderServiceStatus
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsConfigProviderService
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsListener
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsProviderService
import com.android.settingslib.bluetooth.devicesettings.IGetDeviceSettingsConfigCallback
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal const val DEVICE_SETTINGS_CONFIG_ACTION =
    "com.android.bluetooth.bthelper.action.DEVICE_SETTINGS_CONFIG"
internal const val DEVICE_SETTINGS_PROVIDER_ACTION =
    "com.android.bluetooth.bthelper.action.DEVICE_SETTINGS_PROVIDER"
private const val BIND_DEVICE_SETTINGS = "com.android.bluetooth.bthelper.permission.BIND_DEVICE_SETTINGS"
private const val TAG = "BtHelperDeviceSettings"

class BtHelperDeviceSettingsConfigService : Service() {
    private val mapper by lazy { NativeDeviceSettings(this) }

    private val binder = object : IDeviceSettingsConfigProviderService.Stub() {
        override fun getDeviceSettingsConfig(
            device: DeviceInfo?,
            callback: IGetDeviceSettingsConfigCallback?,
        ) = sendConfig(device, callback)

        override fun getDeviceSettingsConfigWithOptions(
            device: DeviceInfo?,
            callback: IGetDeviceSettingsConfigCallback?,
            options: DeviceSettingConfigOptions?,
        ) = sendConfig(device, callback) // This config has no optional providers.
    }

    override fun onBind(intent: Intent): IBinder? =
        binder.takeIf { intent.action == DEVICE_SETTINGS_CONFIG_ACTION }

    private fun sendConfig(device: DeviceInfo?, callback: IGetDeviceSettingsConfigCallback?) {
        enforceDeviceSettingsCaller()
        if (callback == null) return
        val address = device.validAddress()
        val token = Binder.clearCallingIdentity()
        try {
            val config = if (address != null && advertisesBtHelperConfig(address)) {
                mapper.config()
            } else {
                null
            }
            notifyRemote { callback.onResult(DeviceSettingsConfigServiceStatus(config != null), config) }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun advertisesBtHelperConfig(address: String): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val device = adapter.getRemoteDevice(address)
        if (device.bondState != BluetoothDevice.BOND_BONDED) return false
        return when (device.deviceSettingsAdvertisement()) {
            Advertisement.DIRECT -> true
            Advertisement.SECONDARY -> device.hasPodsService()
            null -> false
        }
    }
}

class BtHelperDeviceSettingsProviderService : Service() {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mapper by lazy { NativeDeviceSettings(this) }
    // Accessed only on main, including removal after a remote Binder dies.
    private val listeners = mutableMapOf<String, DeviceListeners>()

    private val binder = object : IDeviceSettingsProviderService.Stub() {
        override fun getServiceStatus(): DeviceSettingsProviderServiceStatus {
            enforceDeviceSettingsCaller()
            return ENABLED
        }

        override fun registerDeviceSettingsListener(
            device: DeviceInfo?,
            callback: IDeviceSettingsListener?,
        ) {
            enforceDeviceSettingsCaller()
            if (callback == null) return
            val address = device.validAddress()
            mainScope.launch {
                // SettingsLib waits for the first callback, even when there are no rows.
                if (address == null) {
                    notifyRemote { callback.onDeviceSettingsChanged(emptyList()) }
                    return@launch
                }
                val callbacks = listeners.getOrPut(address) { DeviceListeners(address) }
                if (callbacks.register(callback)) {
                    val settings = settingsFor(address, AccessoryControls.state.value)
                    notifyRemote { callback.onDeviceSettingsChanged(settings) }
                } else {
                    removeIfEmpty(address, callbacks)
                }
            }
        }

        override fun unregisterDeviceSettingsListener(
            device: DeviceInfo?,
            callback: IDeviceSettingsListener?,
        ) {
            enforceDeviceSettingsCaller()
            val address = device.validAddress() ?: return
            if (callback == null) return
            mainScope.launch {
                val callbacks = listeners[address] ?: return@launch
                callbacks.unregister(callback)
                removeIfEmpty(address, callbacks)
            }
        }

        override fun updateDeviceSettings(device: DeviceInfo?, params: DeviceSettingState?) {
            enforceDeviceSettingsCaller()
            val address = device.validAddress() ?: return
            val state = AccessoryControls.state.value
            if (params == null || state.address != address || !state.connected) return
            mainScope.launch {
                // The authority revalidates this captured generation before sending anything.
                val action = mapper.decode(state, params) ?: return@launch
                AccessoryControls.request(action.address, action.session, action.control, action.value)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mainScope.launch {
            var previousAddress: String? = null
            AccessoryControls.state.collect { state ->
                val address = state.address
                if (previousAddress != address) previousAddress?.let { publish(it, state) }
                address?.let { publish(it, state) }
                previousAddress = address
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? =
        binder.takeIf { intent.action == DEVICE_SETTINGS_PROVIDER_ACTION }

    override fun onDestroy() {
        mainScope.cancel()
        listeners.values.forEach { it.kill() }
        listeners.clear()
        super.onDestroy()
    }

    private fun settingsFor(address: String, state: AccessoryControls.State): List<DeviceSetting> =
        if (state.address == address && state.connected) mapper.settings(state) else emptyList()

    private fun publish(address: String, state: AccessoryControls.State) {
        val callbacks = listeners[address] ?: return
        val settings = settingsFor(address, state)
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                notifyRemote { callbacks.getBroadcastItem(i).onDeviceSettingsChanged(settings) }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun removeIfEmpty(address: String, callbacks: DeviceListeners) {
        if (listeners[address] === callbacks && callbacks.registeredCallbackCount == 0) {
            listeners.remove(address)
            callbacks.kill()
        }
    }

    private inner class DeviceListeners(private val address: String) :
        RemoteCallbackList<IDeviceSettingsListener>() {
        override fun onCallbackDied(callback: IDeviceSettingsListener) {
            mainScope.launch { removeIfEmpty(address, this@DeviceListeners) }
        }
    }

    private companion object {
        // SettingsLib caches this service-wide status; device readiness arrives via callbacks.
        val ENABLED = DeviceSettingsProviderServiceStatus(enabled = true)
    }
}

private fun Context.enforceDeviceSettingsCaller() {
    enforceCallingPermission(BIND_DEVICE_SETTINGS, "Device settings access requires a signature permission")
    val uid = Binder.getCallingUid()
    val pm = packageManager
    // A shared UID is an Android security principal, not an individual package identity.
    if (UserHandle.getUserId(uid) != UserHandle.myUserId() ||
        pm.checkSignatures(Process.myUid(), uid) != PackageManager.SIGNATURE_MATCH ||
        !(pm.packageHasUid("com.android.settings", uid) || pm.packageHasUid("com.android.systemui", uid))
    ) {
        throw SecurityException("UID $uid is not a Bluetooth device settings client")
    }
}

private fun PackageManager.packageHasUid(packageName: String, uid: Int): Boolean =
    try {
        getPackageUid(packageName, 0) == uid
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

private fun DeviceInfo?.validAddress(): String? =
    this?.bluetoothAddress?.uppercase(Locale.ROOT)?.takeIf(BluetoothAdapter::checkBluetoothAddress)

private inline fun notifyRemote(call: () -> Unit) {
    try {
        call()
    } catch (e: RemoteException) {
        Log.d(TAG, "Device settings callback failed", e)
    }
}
