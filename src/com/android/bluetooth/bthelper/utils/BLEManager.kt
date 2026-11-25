/*
 * SPDX-FileCopyrightText: LibrePods Contributors
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils

import android.bluetooth.BluetoothAssignedNumbers
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.android.bluetooth.bthelper.Constants
import com.android.bluetooth.bthelper.getSharedPreferences
import com.android.bluetooth.bthelper.utils.models.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Manager for Bluetooth Low Energy scanning operations specifically for AirPods */
@OptIn(ExperimentalEncodingApi::class)
class BLEManager(private val context: Context, private val keyStorageManager: KeyStorageManager) {

    data class AirPodsStatus(
        val address: String = "",
        val lastSeen: Long = System.currentTimeMillis(),
        val paired: Boolean = false,
        val model: IPods? = null,
        val leftBattery: Int = BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
        val rightBattery: Int = BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
        val caseBattery: Int = BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
        val headsetBattery: Int = BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
        val isLeftInEar: Boolean = false,
        val isRightInEar: Boolean = false,
        val isLeftCharging: Boolean = false,
        val isRightCharging: Boolean = false,
        val isCaseCharging: Boolean = false,
        val isHeadsetCharging: Boolean = false,
        val lidOpen: Boolean = false,
        val connectionState: String = Constants.STATE_UNKNOWN,
        val shortFormat: Boolean = false,
    )

    interface AirPodsStatusListener {
        fun onDeviceStatusChanged(currStatus: AirPodsStatus, prevStatus: AirPodsStatus?)

        fun onBroadcastFromNewAddress(status: AirPodsStatus)

        fun onLidStateChanged(status: AirPodsStatus?, lidOpen: Boolean)

        fun onEarStateChanged(status: AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean)

        fun onBatteryChanged(status: AirPodsStatus)
    }

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var airPodsStatusListener: AirPodsStatusListener? = null
    private val deviceStatusMap = mutableMapOf<String, AirPodsStatus>()
    private val verifiedAddresses = mutableSetOf<String>()
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences()
    private var currentGlobalLidState: Boolean? = null
    private var lastBroadcastTime: Long = 0
    private val processedAddresses = mutableSetOf<String>()
    private var targetDevice: BluetoothDevice? = null

    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable =
        object : Runnable {
            override fun run() {
                cleanupStaleDevices()
                checkLidStateTimeout()
                cleanupHandler.postDelayed(this, CLEANUP_INTERVAL_MS)
            }
        }

    fun setAirPodsStatusListener(listener: AirPodsStatusListener) {
        airPodsStatusListener = listener
    }

    fun startScanning(device: BluetoothDevice) {
        targetDevice = device
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter

            if (btAdapter == null) {
                return
            }

            if (bluetoothLeScanner != null && scanCallback != null) {
                bluetoothLeScanner?.stopScan(scanCallback)
                scanCallback = null
            }

            if (!btAdapter.isEnabled) {
                return
            }

            bluetoothLeScanner = btAdapter.bluetoothLeScanner

            val scanSettings =
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(1)
                    .build()

            val scanFilters = listOf(0x19, 0x13).map { length ->
                ScanFilter.Builder()
                    .setManufacturerData(BluetoothAssignedNumbers.APPLE, byteArrayOf(0x07, length.toByte()))
                    .build()
            }

            scanCallback =
                object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (scanCallback !== this) return
                        processedAddresses.clear()
                        processScanResult(result)
                    }

                    override fun onBatchScanResults(results: List<ScanResult>) {
                        if (scanCallback !== this) return
                        processedAddresses.clear()
                        for (result in results) {
                            processScanResult(result)
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {}
                }

            bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)

            cleanupHandler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
        } catch (t: Throwable) {}
    }

    fun stopScanning() {
        targetDevice = null
        try {
            if (bluetoothLeScanner != null && scanCallback != null) {
                bluetoothLeScanner?.stopScan(scanCallback)
                scanCallback = null
            }

            cleanupHandler.removeCallbacksAndMessages(null)
        } catch (t: Throwable) {}
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun getKeyFromPreferences(address: String, type: AACPManager.ProximityKeyType): ByteArray? {
        val prefix = "${address}_${type.name}"
        val encryptedKeyBase64 = sharedPreferences.getString("${prefix}_encrypted", null)
        val ivBase64 = sharedPreferences.getString("${prefix}_iv", null)

        if (encryptedKeyBase64 != null && ivBase64 != null) {
            return try {
                val encryptedKey = Base64.decode(encryptedKeyBase64)
                val iv = Base64.decode(ivBase64)
                keyStorageManager.decrypt(iv, encryptedKey)
            } catch (e: Exception) {
                null // Decryption failed
            }
        }

        return null
    }

    private fun decryptLastBytes(data: ByteArray, key: ByteArray): ByteArray? {
        return try {
            if (data.size < 16) {
                return null
            }

            val block = data.copyOfRange(data.size - 16, data.size)
            val cipher = Cipher.getInstance(Constants.CRYPTO_AES_ECB_NO_PADDING)
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            cipher.doFinal(block)
        } catch (e: Exception) {
            null
        }
    }

    private fun processScanResult(result: ScanResult) {
        try {
            val scanRecord = result.scanRecord ?: return
            val address = result.device.address

            if (processedAddresses.contains(address)) {
                return
            }

            val manufacturerData = scanRecord.getManufacturerSpecificData(BluetoothAssignedNumbers.APPLE) ?: return
            val target = targetDevice ?: return
            if (target.bondState != BluetoothDevice.BOND_BONDED) return

            if (manufacturerData.size == 21) {
                // Require a public match or an identity resolved by Android, never proximity or name.
                val matches = if (result.device.addressType == BluetoothDevice.ADDRESS_TYPE_PUBLIC) {
                    address == target.address
                } else {
                    val identity = result.device.identityAddressWithType
                    identity.addressType == BluetoothDevice.ADDRESS_TYPE_PUBLIC &&
                        identity.address == target.address
                }
                if (!matches) return
                val status = AirPodsParser.parseShortProximityMessage(address, manufacturerData) ?: return
                airPodsStatusListener?.onDeviceStatusChanged(status, null)
                return
            }

            if (manufacturerData.size != 27 || manufacturerData[0] != 0x07.toByte() ||
                manufacturerData[1] != 0x19.toByte() || manufacturerData[2] != 0x01.toByte()
            ) return

            if (!verifiedAddresses.contains(address)) {
                val irk = getKeyFromPreferences(target.address, AACPManager.ProximityKeyType.IRK)
                if (irk == null || !BluetoothCryptography.verifyRPA(address, irk)) {
                    return
                }
                verifiedAddresses.add(address)
            }

            processedAddresses.add(address)
            lastBroadcastTime = System.currentTimeMillis()

            val encryptionKey = getKeyFromPreferences(target.address, AACPManager.ProximityKeyType.ENC_KEY)
            val decryptedData =
                if (encryptionKey != null) decryptLastBytes(manufacturerData, encryptionKey)
                else null
            val parsedStatus =
                if (decryptedData != null && decryptedData.size == 16) {
                    AirPodsParser.parseProximityMessageWithDecryption(
                        address,
                        manufacturerData,
                        decryptedData,
                    )
                } else {
                    AirPodsParser.parseProximityMessage(address, manufacturerData)
                }

            val previousStatus = deviceStatusMap[address]
            deviceStatusMap[address] = parsedStatus

            airPodsStatusListener?.let { listener ->
                if (previousStatus == null) {
                    listener.onDeviceStatusChanged(parsedStatus, null)
                    listener.onBroadcastFromNewAddress(parsedStatus)

                    if (
                        currentGlobalLidState == null ||
                            currentGlobalLidState != parsedStatus.lidOpen
                    ) {
                        currentGlobalLidState = parsedStatus.lidOpen
                        listener.onLidStateChanged(parsedStatus, parsedStatus.lidOpen)
                    }
                } else {
                    if (parsedStatus != previousStatus) {
                        listener.onDeviceStatusChanged(parsedStatus, previousStatus)
                    }

                    if (parsedStatus.lidOpen != previousStatus.lidOpen) {
                        val previousGlobalState = currentGlobalLidState
                        currentGlobalLidState = parsedStatus.lidOpen

                        if (previousGlobalState != parsedStatus.lidOpen) {
                            listener.onLidStateChanged(parsedStatus, parsedStatus.lidOpen)
                        }
                    }

                    if (
                        parsedStatus.isLeftInEar != previousStatus.isLeftInEar ||
                            parsedStatus.isRightInEar != previousStatus.isRightInEar
                    ) {
                        listener.onEarStateChanged(
                            parsedStatus,
                            parsedStatus.isLeftInEar,
                            parsedStatus.isRightInEar,
                        )
                    }

                    if (
                        parsedStatus.leftBattery != previousStatus.leftBattery ||
                            parsedStatus.rightBattery != previousStatus.rightBattery ||
                            parsedStatus.caseBattery != previousStatus.caseBattery
                    ) {
                        listener.onBatteryChanged(parsedStatus)
                    }
                }
            }
        } catch (t: Throwable) {}
    }

    private fun cleanupStaleDevices() {
        val now = System.currentTimeMillis()
        val staleCutoff = now - STALE_DEVICE_TIMEOUT_MS

        val staleDevices = deviceStatusMap.filter { it.value.lastSeen < staleCutoff }

        for (device in staleDevices) {
            deviceStatusMap.remove(device.key)
        }
    }

    private fun checkLidStateTimeout() {
        val currentTime = System.currentTimeMillis()
        if (
            currentTime - lastBroadcastTime > LID_CLOSE_TIMEOUT_MS && currentGlobalLidState == true
        ) {
            currentGlobalLidState = false
            airPodsStatusListener?.onLidStateChanged(null, false)
        }
    }

    companion object {
        private const val TAG = "AirPodsBLE"
        private const val CLEANUP_INTERVAL_MS = 30000L
        private const val STALE_DEVICE_TIMEOUT_MS = 60000L
        private const val LID_CLOSE_TIMEOUT_MS = 2000L
    }
}
