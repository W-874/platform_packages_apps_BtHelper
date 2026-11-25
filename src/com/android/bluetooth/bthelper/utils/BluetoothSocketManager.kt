/*
 * SPDX-FileCopyrightText: LibrePods Contributors
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BluetoothSocketManager(
    private val serviceScope: CoroutineScope,
    private val onIncomingCall: () -> Unit,
    private val onCallReceived: () -> Unit,
    private val onDeviceConnected: () -> Unit,
    private val onLinkChanged: (String, Boolean) -> Unit,
) {

    private var currentSocket: BluetoothSocket? = null
    private var socketJob: Job? = null
    private var stopped = false

    @Synchronized
    fun connectToSocket(device: BluetoothDevice?) {
        if (device == null || stopped || currentSocket != null) return

        currentSocket =
            try {
                BluetoothConnectionManager.createAirPodsSocket(device)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating bluetooth socket", e)
                return
            }

        val currentSocket = this.currentSocket ?: return

        socketJob = serviceScope.launch {
            try {
                currentSocket.connect()
            } catch (e: IOException) {
                if (closeSocket(currentSocket)) Log.w(TAG, "AACP connection failed", e)
                return@launch
            }
            synchronized(this@BluetoothSocketManager) {
                if (this@BluetoothSocketManager.currentSocket !== currentSocket) return@launch
                BluetoothConnectionManager.currentSocket = currentSocket
                BluetoothConnectionManager.isConnected = true
                onLinkChanged(device.address, true)
            }

            AACPManager.sendPacket(AACPManager.createHandshakePacket())
            AACPManager.sendSetFeatureFlagsPacket()
            AACPManager.sendNotificationRequest()
            val keyTypes = (AACPManager.ProximityKeyType.IRK.value +
                    AACPManager.ProximityKeyType.ENC_KEY.value).toByte()
            AACPManager.sendRequestProximityKeys(keyTypes)

            AACPManager.sendPacket(AACPManager.createHandshakePacket())
            delay(200)
            AACPManager.sendSetFeatureFlagsPacket()
            delay(200)
            AACPManager.sendNotificationRequest()
            delay(200)
            AACPManager.sendRequestProximityKeys(keyTypes)
            onIncomingCall()

            val repeatHandshake = launch {
                delay(5000)
                AACPManager.sendPacket(AACPManager.createHandshakePacket())
                AACPManager.sendSetFeatureFlagsPacket()
                AACPManager.sendNotificationRequest()
                AACPManager.sendRequestProximityKeys(AACPManager.ProximityKeyType.IRK.value)
                onCallReceived()
            }

            try {
                onDeviceConnected()
                val buffer = ByteArray(currentSocket.maxReceivePacketSize.coerceAtLeast(1024))
                val input = currentSocket.inputStream
                while (currentSocket.isConnected) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead > 0) AACPManager.receivePacket(buffer.copyOf(bytesRead))
                }
            } catch (e: IOException) {
                if (currentSocket.isConnected) Log.w(TAG, "AACP read failed", e)
            } finally {
                repeatHandshake.cancel()
            }
        }
        socketJob?.invokeOnCompletion { closeSocket(currentSocket) }
    }

    @Synchronized
    fun disconnect() {
        stopped = true
        val socket = currentSocket ?: return
        val job = socketJob
        closeSocket(socket)
        job?.cancel()
    }

    @Synchronized
    private fun closeSocket(socket: BluetoothSocket): Boolean {
        if (currentSocket !== socket) return false
        currentSocket = null
        socketJob = null
        BluetoothConnectionManager.currentSocket = null
        BluetoothConnectionManager.isConnected = false
        onLinkChanged(socket.remoteDevice.address, false)
        try {
            socket.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing socket", e)
        }
        return true
    }

    companion object {
        const val TAG: String = "BluetoothSocketManager"
    }
}
