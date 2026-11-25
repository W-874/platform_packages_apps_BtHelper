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
    private val onSocketOpened: (BluetoothDevice) -> Unit,
    private val onIncomingCall: () -> Unit,
    private val onCallReceived: () -> Unit,
    private val onDeviceConnected: () -> Unit,
    private val onSocketClosed: () -> Unit,
) {

    private var currentSocket: BluetoothSocket? = null
    private var socketJob: Job? = null

    @Synchronized
    fun connectToSocket(device: BluetoothDevice?) {
        if (device == null) return
        if (currentSocket != null) return

        currentSocket =
            try {
                BluetoothConnectionManager.createAirPodsSocket(device)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating bluetooth socket", e)
                return
            }

        val currentSocket = this.currentSocket ?: return

        try {
            currentSocket.connect()
            BluetoothConnectionManager.isConnected = true
            BluetoothConnectionManager.currentSocket = currentSocket

            AACPManager.sendPacket(AACPManager.createHandshakePacket())
            AACPManager.sendSetFeatureFlagsPacket()
            AACPManager.sendNotificationRequest()
            AACPManager.sendRequestProximityKeys(
                (AACPManager.ProximityKeyType.IRK.value +
                        AACPManager.ProximityKeyType.ENC_KEY.value)
                    .toByte()
            )
            onSocketOpened(device)

            socketJob =
                serviceScope.launch {
                    AACPManager.sendPacket(AACPManager.createHandshakePacket())
                    delay(200)
                    AACPManager.sendSetFeatureFlagsPacket()
                    delay(200)
                    AACPManager.sendNotificationRequest()
                    delay(200)
                    AACPManager.sendRequestProximityKeys(
                        (AACPManager.ProximityKeyType.IRK.value +
                                AACPManager.ProximityKeyType.ENC_KEY.value)
                            .toByte()
                    )
                    onIncomingCall()

                    val repeatHandshake = launch {
                        delay(5000)
                        AACPManager.sendPacket(AACPManager.createHandshakePacket())
                        AACPManager.sendSetFeatureFlagsPacket()
                        AACPManager.sendNotificationRequest()
                        AACPManager.sendRequestProximityKeys(AACPManager.ProximityKeyType.IRK.value)
                        onCallReceived()
                    }

                    onDeviceConnected()

                    val buffer = ByteArray(1024)
                    val input = currentSocket.inputStream
                    try {
                        while (currentSocket.isConnected) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead < 0) break
                            if (bytesRead > 0) {
                                AACPManager.receivePacket(buffer.copyOf(bytesRead))
                            }
                        }
                    } catch (e: IOException) {
                        if (currentSocket.isConnected) Log.w(TAG, "AACP read failed", e)
                    } finally {
                        repeatHandshake.cancel()
                    }
                }
            socketJob?.invokeOnCompletion { closeSocket(currentSocket) }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to socket", e)
            closeSocket(currentSocket)
        }
    }

    @Synchronized
    fun disconnect() {
        val socket = currentSocket ?: return
        val job = socketJob
        closeSocket(socket)
        job?.cancel()
    }

    @Synchronized
    private fun closeSocket(socket: BluetoothSocket) {
        if (currentSocket !== socket) return
        currentSocket = null
        socketJob = null
        BluetoothConnectionManager.currentSocket = null
        BluetoothConnectionManager.isConnected = false
        try {
            socket.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing socket", e)
        } finally {
            onSocketClosed()
        }
    }

    companion object {
        const val TAG: String = "BluetoothSocketManager"
    }
}
