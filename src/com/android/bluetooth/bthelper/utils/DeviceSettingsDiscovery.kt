/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils

import java.nio.charset.CharacterCodingException

/** SettingsLib reads three independent <TAG>value</TAG> fragments from metadata key 25. */
internal class DeviceSettingsDiscovery(packageName: String, className: String, action: String) {
    private class Field(tag: String, value: String) {
        val fragment = "<$tag>$value</$tag>"
        val bytes = fragment.encodeToByteArray()
        val marker = Regex("<\\s*/?\\s*$tag\\b")
    }

    private val fields = arrayOf(
        Field("DEVICE_SETTINGS_CONFIG_PACKAGE_NAME", packageName),
        Field("DEVICE_SETTINGS_CONFIG_CLASS", className),
        Field("DEVICE_SETTINGS_CONFIG_ACTION", action),
    )

    /** Returns only a changed payload. Invalid input, foreign discovery, and no-ops are untouched. */
    fun merge(current: ByteArray?, maxBytes: Int): ByteArray? {
        val text = try {
            current?.decodeToString(throwOnInvalidSequence = true).orEmpty()
        } catch (_: CharacterCodingException) {
            return null
        }
        var missing = 0
        var addedBytes = 0
        for (index in fields.indices) {
            val field = fields[index]
            val first = field.marker.find(text)
            if (first == null) {
                missing = missing or (1 shl index)
                addedBytes += field.bytes.size
            } else if (!text.startsWith(field.fragment, first.range.first) ||
                field.marker.find(text, first.range.first + field.fragment.length) != null
            ) {
                // Never replace any part of another endpoint or guess at repairing duplicates.
                return null
            }
        }
        val currentSize = current?.size ?: 0
        if (missing == 0 || currentSize + addedBytes > maxBytes) return null
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
