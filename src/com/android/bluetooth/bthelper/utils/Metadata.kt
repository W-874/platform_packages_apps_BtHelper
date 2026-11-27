/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils

import android.bluetooth.BluetoothDevice
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.android.bluetooth.bthelper.BtHelperDeviceSettingsConfigService
import com.android.bluetooth.bthelper.Constants
import com.android.bluetooth.bthelper.Constants.TAG
import com.android.bluetooth.bthelper.DEVICE_SETTINGS_CONFIG_ACTION
import java.util.Locale

fun BluetoothDevice.setMetadataValue(key: Int, value: ByteArray, force: Boolean = false): Boolean {
    try {
        if (!force && this.getMetadata(key)?.contentEquals(value) == true) return true
        return this.writeMetadata(key, value)
    } catch (e: Exception) {
        Log.w(TAG, "Exception while updating metadata $key", e)
        return false
    }
}

private fun BluetoothDevice.writeMetadata(key: Int, value: ByteArray): Boolean {
    val written = setMetadata(key, value)
    if (!written) Log.w(TAG, "Failed to set metadata $key")
    return written
}

fun BluetoothDevice.setMetadataUri(key: Int, uri: Uri?, force: Boolean = false): Boolean {
    uri?.let {
        return this.setMetadataValue(key, it.toString().toByteArray(), force)
    }
    return true
}

fun BluetoothDevice.setMetadataString(key: Int, value: String, force: Boolean = false): Boolean {
    return this.setMetadataValue(key, value.toByteArray(), force)
}

fun BluetoothDevice.setMetadataBoolean(key: Int, value: Boolean, force: Boolean = false): Boolean {
    return this.setMetadataString(key, value.toString().uppercase(Locale.ROOT), force)
}

fun BluetoothDevice.setMetadataInt(key: Int, value: Int, force: Boolean = false): Boolean {
    return this.setMetadataString(key, value.toString(), force)
}

internal fun BluetoothDevice.clearLegacySettingsSlice(): Boolean {
    val key = BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI
    return updateMetadata(key) { current ->
        if (current == null || current.isEmpty()) return@updateMetadata null
        val uri = Uri.parse(current.decodeToString())
        if (uri.scheme == ContentResolver.SCHEME_CONTENT &&
            uri.authority == "${Constants.AUTHORITY_BTHELPER}.sliceprovider" &&
            current.contentEquals(getMetadata(key))
        ) byteArrayOf() else null
    }
}

private val deviceSettingsDiscovery = DeviceSettingsDiscovery(
    packageName = Constants.AUTHORITY_BTHELPER,
    className = BtHelperDeviceSettingsConfigService::class.java.name,
    action = DEVICE_SETTINGS_CONFIG_ACTION,
)

internal fun BluetoothDevice.publishDeviceSettingsDiscovery(): Boolean =
    updateMetadata(Constants.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS) { current ->
        if (bondState != BluetoothDevice.BOND_BONDED) return@updateMetadata null
        val updated = deviceSettingsDiscovery.merge(current, BluetoothDevice.METADATA_MAX_LENGTH)
            ?: return@updateMetadata null
        // Bluetooth has no compare-and-set. Avoid a stale write if another owner has already
        // changed key 25, and let its listener notification merge the latest snapshot instead.
        updated.takeIf {
            current.contentEquals(getMetadata(Constants.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS))
        }
    }

internal fun BluetoothDevice.deviceSettingsAdvertisement(): DeviceSettingsDiscovery.Advertisement? =
    deviceSettingsDiscovery.advertisement(getMetadata(Constants.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS))

internal fun BluetoothDevice.setBatteryThresholdMetadata(key: Int, threshold: Int): Boolean =
    threshold !in 0..100 || setMetadataInt(key, threshold)

internal fun BluetoothDevice.setPodsIconMetadata(key: Int, uri: Uri?): Boolean =
    updateMetadata(key) { current ->
        val oldUri = current?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it.decodeToString()) }
        val owned = oldUri?.scheme == "content" && oldUri.authority == Constants.AUTHORITY_FILE
        when {
            oldUri != null && !owned -> null
            uri != null -> uri.toString().encodeToByteArray()
            owned -> byteArrayOf()
            else -> null
        }
    }

// Bluetooth metadata has no compare-and-set; serialize our own read/modify/write calls.
private val metadataUpdateLock = Any()

private inline fun BluetoothDevice.updateMetadata(
    key: Int,
    transform: (ByteArray?) -> ByteArray?,
): Boolean = synchronized(metadataUpdateLock) {
    try {
        val current = getMetadata(key)
        val updated = transform(current)
        updated == null || current?.contentEquals(updated) == true || writeMetadata(key, updated)
    } catch (e: Exception) {
        Log.w(TAG, "Exception while updating metadata $key", e)
        false
    }
}

/** Owns only battery values actually written by this running service. */
internal class PodsBatteryMetadata {
    private data class Keys(val level: Int, val charging: Int)

    private val lastWritten = mutableMapOf<String, MutableMap<Int, ByteArray>>()

    fun publish(device: BluetoothDevice, batteries: List<Battery>, split: Boolean): Int {
        val left = if (split) batteries[0] else null
        val right = if (split) batteries[1] else null
        val case = if (split) batteries[2] else null
        val headset = if (split) null else batteries[3]
        val leftLevel = left.validLevel()
        val rightLevel = right.validLevel()
        val level = if (split) {
            when {
                leftLevel == null -> rightLevel
                rightLevel == null -> leftLevel
                else -> minOf(leftLevel, rightLevel)
            }
        } else headset.validLevel()
        val charging = if (split) {
            (leftLevel == null || left.isCharging()) && (rightLevel == null || right.isCharging())
        } else headset.isCharging()

        publishReading(device, LEFT, leftLevel, left.isCharging())
        publishReading(device, RIGHT, rightLevel, right.isCharging())
        publishReading(device, CASE, case.validLevel(), case.isCharging())
        publishReading(device, MAIN, level, charging)
        return level ?: BluetoothDevice.BATTERY_LEVEL_UNKNOWN
    }

    private fun Battery?.validLevel(): Int? =
        this?.takeIf { it.status != BatteryStatus.DISCONNECTED && it.level in 0..100 }?.level

    private fun Battery?.isCharging(): Boolean = this?.status == BatteryStatus.CHARGING

    private fun publishReading(device: BluetoothDevice, keys: Keys, level: Int?, charging: Boolean) {
        val written = lastWritten.getOrPut(device.address) { mutableMapOf() }
        update(device, written, keys.charging, level?.let { if (charging) "TRUE" else "FALSE" }, "")
        update(device, written, keys.level, level?.toString(), BluetoothDevice.BATTERY_LEVEL_UNKNOWN.toString())
    }

    private fun update(
        device: BluetoothDevice,
        written: MutableMap<Int, ByteArray>,
        key: Int,
        value: String?,
        unavailable: String,
    ) {
        val previous = written[key]
        if (value == null && previous == null) return
        val next = (value ?: unavailable).encodeToByteArray()
        var changed = false
        var keepOwnership = false
        val success = device.updateMetadata(key) { current ->
            keepOwnership = current != null && previous?.contentEquals(current) == true
            changed = (value != null || keepOwnership) && current?.contentEquals(next) != true
            next.takeIf { changed }
        }
        if (success) {
            if (value != null && (changed || keepOwnership)) written[key] = next else written.remove(key)
        }
    }

    private companion object {
        val LEFT = Keys(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY, BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING)
        val RIGHT = Keys(BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY, BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING)
        val CASE = Keys(BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY, BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING)
        val MAIN = Keys(BluetoothDevice.METADATA_MAIN_BATTERY, BluetoothDevice.METADATA_MAIN_CHARGING)
    }
}
