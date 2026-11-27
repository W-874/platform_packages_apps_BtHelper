/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils

import java.nio.charset.CharacterCodingException

/** Fast Pair customized metadata contains independent <TAG>value</TAG> fragments. */
internal class DeviceSettingsDiscovery(packageName: String, className: String, action: String) {
    enum class Advertisement { DIRECT, SECONDARY }

    private class Field(tag: String, value: String) {
        val fragment = "<$tag>$value</$tag>"
        val bytes = fragment.encodeToByteArray()
        val marker = Regex("<\\s*/?\\s*$tag\\b")

        fun isSingleMatch(text: String, first: MatchResult): Boolean =
            text.startsWith(fragment, first.range.first) &&
                marker.find(text, first.range.first + fragment.length) == null
    }

    private val directFields = endpoint(packageName, className, action)

    private companion object {
        const val BTHELPER_DEVICE_SETTINGS = "BTHELPER_DEVICE_SETTINGS"
        const val BTHELPER_DEVICE_SETTINGS_VERSION = "1"
        val secondaryField = Field(BTHELPER_DEVICE_SETTINGS, BTHELPER_DEVICE_SETTINGS_VERSION)
        val gmsFields = endpoint(
            "com.google.android.gms",
            "com.google.android.gms.chimera.GmsBoundBrokerService",
            "com.google.android.gms.fastpair.ACTION_BIND_DEVICE_SETTINGS_CONFIG_PROVIDER",
        )

        fun endpoint(packageName: String, className: String, action: String) = arrayOf(
            Field("DEVICE_SETTINGS_CONFIG_PACKAGE_NAME", packageName),
            Field("DEVICE_SETTINGS_CONFIG_CLASS", className),
            Field("DEVICE_SETTINGS_CONFIG_ACTION", action),
        )
    }

    /** Returns only a changed payload, appending without re-encoding any existing fields. */
    fun merge(current: ByteArray?, maxBytes: Int): ByteArray? {
        val text = decode(current) ?: return null
        if (secondaryField.marker.containsMatchIn(text)) return null

        val directMissing = missingFields(text, directFields)
        if (directMissing != null) return appendFields(current, maxBytes, directFields, directMissing)

        if (current == null || missingFields(text, gmsFields) != 0 ||
            secondaryField.bytes.size > maxBytes - current.size
        ) return null
        return current + secondaryField.bytes
    }

    fun advertisement(current: ByteArray?): Advertisement? {
        val text = decode(current) ?: return null
        if (missingFields(text, directFields) == 0) return Advertisement.DIRECT
        val marker = secondaryField.marker.find(text) ?: return null
        return Advertisement.SECONDARY.takeIf {
            secondaryField.isSingleMatch(text, marker) && missingFields(text, gmsFields) == 0
        }
    }

    private fun decode(current: ByteArray?): String? = try {
        current?.decodeToString(throwOnInvalidSequence = true).orEmpty()
    } catch (_: CharacterCodingException) {
        null
    }

    /** The bitmask of absent fields, or null for a conflicting, malformed or duplicate field. */
    private fun missingFields(text: String, fields: Array<Field>): Int? {
        var missing = 0
        for (index in fields.indices) {
            val field = fields[index]
            val first = field.marker.find(text)
            if (first == null) {
                missing = missing or (1 shl index)
            } else if (!field.isSingleMatch(text, first)) {
                return null
            }
        }
        return missing
    }

    private fun appendFields(
        current: ByteArray?,
        maxBytes: Int,
        fields: Array<Field>,
        missing: Int,
    ): ByteArray? {
        if (missing == 0) return null
        var addedBytes = 0
        for (index in fields.indices) {
            if (missing and (1 shl index) != 0) addedBytes += fields[index].bytes.size
        }
        val currentSize = current?.size ?: 0
        if (addedBytes > maxBytes - currentSize) return null
        return ByteArray(currentSize + addedBytes).also { output ->
            current?.copyInto(output)
            var position = currentSize
            for (index in fields.indices) {
                if (missing and (1 shl index) != 0) {
                    val bytes = fields[index].bytes
                    bytes.copyInto(output, position)
                    position += bytes.size
                }
            }
        }
    }
}
