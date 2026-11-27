/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils

import com.android.bluetooth.bthelper.utils.DeviceSettingsDiscovery.Advertisement

private const val PACKAGE = "com.android.bluetooth.bthelper"
private const val CLASS = "$PACKAGE.BtHelperDeviceSettingsConfigService"
private const val ACTION = "$PACKAGE.action.DEVICE_SETTINGS_CONFIG"
private const val PACKAGE_TAG = "DEVICE_SETTINGS_CONFIG_PACKAGE_NAME"
private const val CLASS_TAG = "DEVICE_SETTINGS_CONFIG_CLASS"
private const val ACTION_TAG = "DEVICE_SETTINGS_CONFIG_ACTION"
private const val GMS_PACKAGE = "com.google.android.gms"
private const val GMS_CLASS = "com.google.android.gms.chimera.GmsBoundBrokerService"
private const val GMS_ACTION = "com.google.android.gms.fastpair.ACTION_BIND_DEVICE_SETTINGS_CONFIG_PROVIDER"
private const val SECONDARY_TAG = "BTHELPER_DEVICE_SETTINGS"
private const val MAX = 2048

private fun field(tag: String, value: String) = "<$tag>$value</$tag>"

fun main() {
    val discovery = DeviceSettingsDiscovery(PACKAGE, CLASS, ACTION)
    val packageField = field(PACKAGE_TAG, PACKAGE)
    val classField = field(CLASS_TAG, CLASS)
    val actionField = field(ACTION_TAG, ACTION)
    val endpoint = packageField + classField + actionField
    val gmsPackageField = field(PACKAGE_TAG, GMS_PACKAGE)
    val gmsClassField = field(CLASS_TAG, GMS_CLASS)
    val gmsActionField = field(ACTION_TAG, GMS_ACTION)
    val secondaryField = field(SECONDARY_TAG, "1")
    val unrelated = "<TEMP_BOND_TYPE>1</TEMP_BOND_TYPE>\n" +
        "<CUSTOM_NAME>\u00e9</CUSTOM_NAME><HEARABLE_CONTROL_SLICE_WITH_WIDTH>content://partner?width=</HEARABLE_CONTROL_SLICE_WITH_WIDTH>"

    check(discovery.merge(null, MAX)?.decodeToString() == endpoint)
    check(discovery.merge(byteArrayOf(), MAX)?.decodeToString() == endpoint)
    val initial = unrelated.encodeToByteArray()
    val published = checkNotNull(discovery.merge(initial, MAX))
    check(published.copyOfRange(0, initial.size).contentEquals(initial))
    for ((tag, expected) in listOf(PACKAGE_TAG to PACKAGE, CLASS_TAG to CLASS, ACTION_TAG to ACTION)) {
        // Same first-match regex as SettingsLib BluetoothUtils.extraTagValue.
        check(Regex(field(tag, "(.*?)")).find(published.decodeToString())?.groupValues?.get(1) == expected)
    }
    check(discovery.merge(published, MAX) == null)
    check(discovery.advertisement(published) == Advertisement.DIRECT)
    check(discovery.merge((classField + unrelated).encodeToByteArray(), MAX)?.decodeToString() ==
        classField + unrelated + packageField + actionField)

    for (rejected in listOf(
        field(PACKAGE_TAG, "another.owner"),
        packageField + field(CLASS_TAG, "another.owner.Provider"),
        packageField + classField + field(ACTION_TAG, "another.owner.ACTION"),
        packageField + packageField,
        packageField + "</$PACKAGE_TAG>",
        "<$CLASS_TAG>$CLASS",
        "<$ACTION_TAG />",
        field(ACTION_TAG, ""),
    )) {
        check(discovery.merge((unrelated + rejected).encodeToByteArray(), MAX) == null) { rejected }
    }
    check(discovery.merge(byteArrayOf(0xc3.toByte()), MAX) == null)
    val remaining = MAX - endpoint.encodeToByteArray().size
    val exact = ("\u00e9" + "x".repeat(remaining - 2)).encodeToByteArray()
    check(discovery.merge(exact, MAX)?.size == MAX)
    check(discovery.merge(exact + byteArrayOf(1), MAX) == null)

    // Late GMS registration replaces key 25 with its normal endpoint and other custom fields.
    val gms = unrelated + gmsPackageField + gmsClassField + gmsActionField
    val gmsPayload = gms.encodeToByteArray()
    check(discovery.advertisement(gmsPayload) == null)
    val secondary = checkNotNull(discovery.merge(gmsPayload, MAX))
    check(secondary.contentEquals(gmsPayload + secondaryField.encodeToByteArray()))
    check(discovery.advertisement(secondary) == Advertisement.SECONDARY)
    check(discovery.merge(secondary, MAX) == null)

    val exactGms = (gms + "é" + "x".repeat(MAX - secondary.size - 2)).encodeToByteArray()
    check(discovery.merge(exactGms, MAX)?.size == MAX)
    check(discovery.merge(exactGms + byteArrayOf(1), MAX) == null)

    for (otherEndpoint in listOf(
        gmsPackageField + gmsClassField,
        gmsPackageField + gmsActionField,
        gmsClassField + gmsActionField,
        gmsPackageField + gmsClassField + actionField,
        gmsPackageField + classField + gmsActionField,
        packageField + gmsClassField + gmsActionField,
        field(PACKAGE_TAG, "another.owner") + field(CLASS_TAG, "another.owner.Provider") +
            field(ACTION_TAG, "another.owner.ACTION"),
        gms + gmsClassField,
        gms + "</$ACTION_TAG>",
        gms + "<$PACKAGE_TAG />",
    )) {
        check(discovery.merge(otherEndpoint.encodeToByteArray(), MAX) == null) { otherEndpoint }
        check(discovery.advertisement((otherEndpoint + secondaryField).encodeToByteArray()) == null) {
            otherEndpoint
        }
    }

    for (otherMarker in listOf(
        field(SECONDARY_TAG, "0"),
        field(SECONDARY_TAG, " 1 "),
        secondaryField + secondaryField,
        secondaryField + "</$SECONDARY_TAG>",
        "<$SECONDARY_TAG>1",
        "<$SECONDARY_TAG />",
    )) {
        val malformed = (gms + otherMarker).encodeToByteArray()
        check(discovery.merge(malformed, MAX) == null) { otherMarker }
        check(discovery.advertisement(malformed) == null) { otherMarker }
    }
    check(discovery.advertisement(secondaryField.encodeToByteArray()) == null)
    check(discovery.advertisement(secondary + byteArrayOf(0xc3.toByte())) == null)
    println("DeviceSettingsDiscovery host checks passed")
}
