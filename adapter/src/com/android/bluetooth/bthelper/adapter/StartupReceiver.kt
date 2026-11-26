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

        val key = BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI
        if (intent.getIntExtra(Constants.EXTRA_METADATA_KEY, -1) != key) return
        val device =
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
        val sliceManager = context.getSystemService(SliceManager::class.java) ?: return

        try {
            val current = device.getMetadata(key)
            if (current != null && current.isNotEmpty()) {
                val previous = Uri.parse(current.decodeToString())
                if (previous.scheme != SLICE_URI.scheme || previous.authority != SLICE_URI.authority) return
            }
            sliceManager.grantSlicePermission("com.android.settings", SLICE_URI)
            val updated = getSliceUri(device.address).toString().encodeToByteArray()
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

    private companion object {
        const val TAG = "StartupReceiver"
        val SLICE_URI = getSliceUri()
    }
}
