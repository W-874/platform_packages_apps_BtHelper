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
    private val onLinkChanged: (BluetoothSocket, Boolean) -> Unit,
    private val onPacket: (BluetoothSocket, ByteArray) -> Unit,
) {

    private var currentSocket: BluetoothSocket? = null
    private var socketJob: Job? = null
    private var stopped = false

    @Synchronized
    fun connectToSocket(device: BluetoothDevice?) {
        if (device == null || device.bondState != BluetoothDevice.BOND_BONDED || stopped || currentSocket != null) return

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
            }

            fun send(data: ByteArray) = AACPManager.sendPacket(AACPManager.createDataPacket(data), currentSocket)
            if (!AACPManager.sendPacket(AACPManager.createHandshakePacket(), currentSocket)) return@launch
            send(AACPManager.createSetFeatureFlagsPacket())
            send(AACPManager.createRequestNotificationPacket())
            val keyTypes = (AACPManager.ProximityKeyType.IRK.value + AACPManager.ProximityKeyType.ENC_KEY.value).toByte()
            send(AACPManager.createRequestProximityKeysPacket(keyTypes))

            synchronized(this@BluetoothSocketManager) {
                if (this@BluetoothSocketManager.currentSocket !== currentSocket) return@launch
                BluetoothConnectionManager.isConnected = true
                onLinkChanged(currentSocket, true)
            }

            // Continue reading while firmware finishes its delayed initialization.
            val finishInitialization = launch {
                delay(200)
                send(AACPManager.createSetFeatureFlagsPacket())
                delay(200)
                send(AACPManager.createRequestNotificationPacket())
                send(byteArrayOf(AACPManager.Opcodes.REQUEST_EAR_DETECTION, 0x00))
                delay(200)
                send(AACPManager.createRequestProximityKeysPacket(keyTypes))
            }

            try {
                val buffer = ByteArray(currentSocket.maxReceivePacketSize.coerceAtLeast(1024))
                val input = currentSocket.inputStream
                while (currentSocket.isConnected) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead > 0) onPacket(currentSocket, buffer.copyOf(bytesRead))
                }
            } catch (e: IOException) {
                if (currentSocket.isConnected) Log.w(TAG, "AACP read failed", e)
            } finally {
                finishInitialization.cancel()
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
        onLinkChanged(socket, false)
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
