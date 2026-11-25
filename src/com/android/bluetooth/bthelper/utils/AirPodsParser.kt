/*
 * SPDX-FileCopyrightText: Federico Dossena
 * SPDX-FileCopyrightText: LibrePods Contributors
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils

import android.bluetooth.BluetoothDevice
import com.android.bluetooth.bthelper.Constants
import com.android.bluetooth.bthelper.utils.models.*

object AirPodsParser {

    val models: List<IPods> =
        listOf(
            AirPods1(),
            AirPods2(),
            AirPods3(),
            AirPods4(),
            AirPods4Anc(),
            AirPodsPro(),
            AirPodsPro2(),
            AirPodsPro2UsbC(),
            AirPodsPro3(),
            AirPodsMax(),
            AirPodsMaxUsbC(),
        )

    private val modelsByNumber = buildMap {
        for (model in models) {
            val numbers = when (model) {
                is RegularPods -> model.modelNumbers
                is SinglePods -> model.modelNumbers
                else -> continue
            }
            for (number in numbers) put(number, model)
        }
    }

    // AACP 0x001D carries NUL-terminated service, device name, and model number fields.
    internal fun parseDeviceInformation(packet: ByteArray): IPods? {
        if (packet.size < 10 || packet[0] != 4.toByte() || packet[1] != 0.toByte() ||
            packet[2] != 4.toByte() || packet[3] != 0.toByte() ||
            packet[4] != 0x1d.toByte() || packet[5] != 0.toByte()
        ) return null
        val end = 9 + ((packet[7].toInt() and 0xff) or ((packet[8].toInt() and 0xff) shl 8))
        if (end > packet.size) return null
        var start = 9
        repeat(2) {
            while (start < end && packet[start] != 0.toByte()) start++
            if (start == end) return null
            start++
        }
        var modelEnd = start
        while (modelEnd < end && packet[modelEnd] != 0.toByte()) modelEnd++
        if (modelEnd == end || modelEnd == start) return null
        return modelsByNumber[packet.decodeToString(start, modelEnd)]
    }

    // The short (07 13) format carries full-percent levels in fixed L/R/case order.
    // Unlike 07 19, its private tail is not an AES block and does not report wear or lid state.
    internal fun parseShortProximityMessage(address: String, data: ByteArray): BLEManager.AirPodsStatus? {
        if (data.size != 21 || data[0] != 0x07.toByte() || data[1] != 0x13.toByte() ||
            data[2] != 0x01.toByte()
        ) return null

        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val left = data[12].toInt() and 0x7F
        val right = data[13].toInt() and 0x7F
        val case = data[14].toInt() and 0x7F
        fun level(value: Int) = if (value <= 100) value else BluetoothDevice.BATTERY_LEVEL_UNKNOWN
        return BLEManager.AirPodsStatus(
            address = address,
            paired = true,
            model = getModel(modelId, data[9].toInt() and 0xFF),
            leftBattery = level(left),
            rightBattery = level(right),
            caseBattery = level(case),
            isLeftCharging = left <= 100 && data[12] < 0,
            isRightCharging = right <= 100 && data[13] < 0,
            isCaseCharging = case <= 100 && data[14] < 0,
            shortFormat = true,
        )
    }

    fun parseProximityMessage(address: String, data: ByteArray): BLEManager.AirPodsStatus {
        val paired = data[2].toInt() == 1

        val color = data[9].toInt()
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = getModel(modelId, color)

        val status = data[5].toInt() and 0xFF
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val conn = getConnectionState(data[10].toInt())

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftBatteryNibble =
            if (isFlipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightBatteryNibble =
            if (isFlipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F

        val caseBattery = flagsCase and 0x0F
        val flags = (flagsCase shr 4) and 0x0F

        val isLeftCharging = if (isFlipped) (flags and 0x02) != 0 else (flags and 0x01) != 0
        val isRightCharging = if (isFlipped) (flags and 0x01) != 0 else (flags and 0x02) != 0
        val isCaseCharging = (flags and 0x04) != 0

        val lidOpen = ((lid shr 3) and 0x01) == 0

        fun decodeBattery(n: Int): Int =
            when (n) {
                in 0x0..0x9 -> n * 10
                in 0xA..0xE -> 100
                0xF -> BluetoothDevice.BATTERY_LEVEL_UNKNOWN
                else -> BluetoothDevice.BATTERY_LEVEL_UNKNOWN
            }

        return BLEManager.AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = decodeBattery(leftBatteryNibble),
            rightBattery = decodeBattery(rightBatteryNibble),
            caseBattery = decodeBattery(caseBattery),
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            connectionState = conn,
        )
    }

    fun parseProximityMessageWithDecryption(
        address: String,
        data: ByteArray,
        decrypted: ByteArray,
    ): BLEManager.AirPodsStatus {
        val paired = data[2].toInt() == 1

        val color = data[9].toInt()
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = getModel(modelId, color)

        val status = data[5].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val conn = getConnectionState(data[10].toInt())

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftByteIndex = if (isFlipped) 2 else 1
        val rightByteIndex = if (isFlipped) 1 else 2

        val rawLeftBatteryByte = decrypted[leftByteIndex].toInt() and 0xFF
        val (isLeftCharging, rawLeftBattery) = formatBattery(rawLeftBatteryByte)
        val leftBattery =
            if (rawLeftBatteryByte == 0xFF || (isLeftCharging && rawLeftBattery == 127)) {
                BluetoothDevice.BATTERY_LEVEL_UNKNOWN
            } else {
                rawLeftBattery
            }

        val rawRightBatteryByte = decrypted[rightByteIndex].toInt() and 0xFF
        val (isRightCharging, rawRightBattery) = formatBattery(rawRightBatteryByte)
        val rightBattery =
            if (rawRightBatteryByte == 0xFF || (isRightCharging && rawRightBattery == 127)) {
                BluetoothDevice.BATTERY_LEVEL_UNKNOWN
            } else {
                rawRightBattery
            }

        val rawCaseBatteryByte = decrypted[3].toInt() and 0xFF
        val (isCaseCharging, rawCaseBattery) = formatBattery(rawCaseBatteryByte)
        val caseBattery =
            if (rawCaseBatteryByte == 0xFF || (isCaseCharging && rawCaseBattery == 127)) {
                BluetoothDevice.BATTERY_LEVEL_UNKNOWN
            } else {
                rawCaseBattery
            }

        val lidOpen = ((lid shr 3) and 0x01) == 0

        return BLEManager.AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = leftBattery,
            rightBattery = rightBattery,
            caseBattery = caseBattery,
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            connectionState = conn,
        )
    }

    private fun getModel(modelId: Int, color: Int): IPods? {
        val model = models.find { modelId == it.modelId }
        model?.color = color
        return model
    }

    private fun getConnectionState(data: Int): String {
        return when (data) {
            0x00 -> Constants.STATE_DISCONNECTED
            0x04 -> Constants.STATE_IDLE
            0x05 -> Constants.STATE_MUSIC
            0x06 -> Constants.STATE_CALL
            0x07 -> Constants.STATE_RINGING
            0x09 -> Constants.STATE_HANGING_UP
            0xFF -> Constants.STATE_UNKNOWN
            else -> Constants.STATE_UNKNOWN
        }
    }

    private fun formatBattery(byteVal: Int): Pair<Boolean, Int> {
        val charging = (byteVal and 0x80) != 0
        val level = byteVal and 0x7F
        return Pair(charging, level)
    }
}
