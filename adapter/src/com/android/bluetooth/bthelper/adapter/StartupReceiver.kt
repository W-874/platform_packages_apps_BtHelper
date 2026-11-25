/*
 * SPDX-FileCopyrightText: Federico Dossena
 * SPDX-FileCopyrightText: The MoKee Open Source Project
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */

package com.android.bluetooth.bthelper.adapter

import android.app.slice.SliceManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class StartupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Constants.ACTION_SET_SLICE) return

        val key = intent.getIntExtra(Constants.EXTRA_METADATA_KEY, -1)
        val targetPackage = when (key) {
            BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI -> "com.android.settings"
            Constants.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS -> "com.android.systemui"
            else -> return
        }
        val device =
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
        val sliceManager = context.getSystemService(SliceManager::class.java) ?: return

        try {
            sliceManager.grantSlicePermission(targetPackage, SLICE_URI)
            val uri = getSliceUri(device.address)
            val current = device.getMetadata(key)
            val updated = if (key == BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI) {
                uri.toString().toByteArray()
            } else {
                withControlSlice(current, uri) ?: return
            }
            if (current?.contentEquals(updated) == true) return
            if (updated.size > BluetoothDevice.METADATA_MAX_LENGTH ||
                !device.setMetadata(key, updated)
            ) {
                Log.w(TAG, "Could not publish slice metadata key $key")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to publish slice metadata key $key", e)
        }
    }

    private fun withControlSlice(current: ByteArray?, uri: Uri): ByteArray? {
        // The legacy volume panel appends its width directly to the metadata URI string.
        val controlUri = uri.buildUpon().appendQueryParameter("width", "").build()
        val field = "<$CONTROL_SLICE_TAG>$controlUri</$CONTROL_SLICE_TAG>"
        val fields = current?.decodeToString().orEmpty()
        val existing = CONTROL_SLICE_FIELD.find(fields)
        val updated = when {
            existing == null -> fields + field
            existing.value == field -> return null
            else -> fields.replaceRange(existing.range, field)
        }
        return updated.toByteArray()
    }

    private companion object {
        const val TAG = "StartupReceiver"
        const val CONTROL_SLICE_TAG = "HEARABLE_CONTROL_SLICE_WITH_WIDTH"
        val CONTROL_SLICE_FIELD = Regex("<$CONTROL_SLICE_TAG>[^<]*</$CONTROL_SLICE_TAG>")
        val SLICE_URI = getSliceUri()
    }
}
