/*
 * SPDX-FileCopyrightText: LibrePods Contributors
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */

package com.android.bluetooth.bthelper.utils

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.android.bluetooth.bthelper.Constants

object BluetoothConnectionManager {
    // The platform factory exists at runtime but is absent from the Bluetooth compile stubs.
    private val createL2capSocket by lazy {
        BluetoothDevice::class.java.getMethod("createL2capSocket", Int::class.javaPrimitiveType)
    }

    internal fun createAirPodsSocket(device: BluetoothDevice): BluetoothSocket =
        createL2capSocket.invoke(device, Constants.AIRPODS_PSM) as BluetoothSocket

    var currentSocket: BluetoothSocket? = null
        @Synchronized get
        @Synchronized set

    var isConnected = false
        @Synchronized get
        @Synchronized set

    var currentAttSocket: BluetoothSocket? = null
        @Synchronized get
        @Synchronized set

    var isAttConnected = false
        @Synchronized get
        @Synchronized set
}
