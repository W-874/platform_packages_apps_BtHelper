/*
 * SPDX-License-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.ParcelUuid
import android.os.Parcelable
import android.util.Log
import com.android.bluetooth.bthelper.utils.models.IPods

internal object DeviceIdentificationSdp {
    val uuid: ParcelUuid = ParcelUuid.fromString("00001200-0000-1000-8000-00805f9b34fb")
    const val ACTION_RECORD = "android.bluetooth.device.action.SDP_RECORD"
    private const val EXTRA_RECORD = "android.bluetooth.device.extra.SDP_RECORD"
    private const val EXTRA_STATUS = "android.bluetooth.device.extra.SDP_SEARCH_STATUS"
    private const val TAG = "DeviceIdentificationSdp"

    // The Bluetooth mainline compile stubs omit these existing platform APIs.
    private val search by lazy { BluetoothDevice::class.java.getMethod("sdpSearch", ParcelUuid::class.java) }
    private val recordClass by lazy { Class.forName("android.bluetooth.SdpDipRecord").asSubclass(Parcelable::class.java) }
    private val primary by lazy { recordClass.getMethod("getPrimaryRecord") }
    private val vendorSource by lazy { recordClass.getMethod("getVendorIdSource") }
    private val vendor by lazy { recordClass.getMethod("getVendorId") }
    private val product by lazy { recordClass.getMethod("getProductId") }

    fun request(device: BluetoothDevice): Boolean = try {
        search.invoke(device, uuid) == true
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "Platform DIP search unavailable", e)
        false
    }

    fun model(intent: Intent): IPods? {
        if (intent.getIntExtra(EXTRA_STATUS, -1) != 0) return null
        return try {
            val record = intent.getParcelableExtra(EXTRA_RECORD, recordClass) ?: return null
            if (primary.invoke(record) != true) return null
            AirPodsParser.modelFromPnpId(
                vendorSource.invoke(record) as Int,
                vendor.invoke(record) as Int,
                product.invoke(record) as Int,
            )
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Platform DIP record unavailable", e)
            null
        }
    }
}

// One read-only DIS probe, scoped to the already bonded headset. All callbacks run on main.
internal class DeviceInformationGatt(
    private val context: Context,
    private val handler: Handler,
    private val onModel: (BluetoothDevice, IPods) -> Unit,
) : BluetoothGattCallback() {
    private val dis = ParcelUuid.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    private val pnp = ParcelUuid.fromString("00002a50-0000-1000-8000-00805f9b34fb")
    private var connection: BluetoothGatt? = null
    private val timeout = Runnable { close() }

    fun probe(device: BluetoothDevice) {
        val leConnected = device.isConnected(BluetoothDevice.TRANSPORT_LE)
        if (!leConnected && device.uuids?.contains(dis) != true) return
        val settings = BluetoothGattConnectionSettings.Builder()
            .setTransport(BluetoothDevice.TRANSPORT_LE)
            .setAutoConnectEnabled(false)
            .setOpportunisticEnabled(leConnected)
            .setAutomaticMtuEnabled(false)
            .build()
        connection = device.connectGatt(settings, context.mainExecutor, this)
        if (connection != null) handler.postDelayed(timeout, 5000)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (connection !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED ||
            !gatt.discoverServices()
        ) close()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (connection !== gatt) return
        val characteristic = gatt.getService(dis.uuid)?.getCharacteristic(pnp.uuid)
        if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null ||
            !gatt.readCharacteristic(characteristic)
        ) close()
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (connection !== gatt) return
        close()
        if (status != BluetoothGatt.GATT_SUCCESS || characteristic.uuid != pnp.uuid) return
        AirPodsParser.modelFromGattPnpId(value)?.let { onModel(gatt.device, it) }
    }

    fun close() {
        handler.removeCallbacks(timeout)
        val gatt = connection ?: return
        connection = null
        gatt.close()
    }
}
