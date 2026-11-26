/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.adapter

import android.content.ContentResolver
import android.net.Uri

object Constants {
    /* Authority (package name) */
    const val AUTHORITY_BTHELPER: String = "com.android.bluetooth.bthelper"
    const val AUTHORITY_SLICE: String = AUTHORITY_BTHELPER + ".sliceprovider"
    const val ACTIVITY_BTHELPER_MAIN_SETTINGS =
        AUTHORITY_BTHELPER + ".settings.MainSettingsActivity"

    /* Slices Paths */
    const val PATH_BTHELPER: String = "bthelper"
    const val SLICE_BTHELPER: String = "/" + PATH_BTHELPER
    const val PARAM_MAC_ADDRESS: String = "address"

    /* Intent Actions and extras */
    const val ACTION_SET_SLICE: String = AUTHORITY_BTHELPER + ".SET_SLICE"
    const val EXTRA_METADATA_KEY: String = "metadata_key"
}

fun getSliceUri(macAddress: String? = null): Uri =
    Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(Constants.AUTHORITY_SLICE)
        .appendPath(Constants.PATH_BTHELPER)
        .apply {
            if (!macAddress.isNullOrEmpty()) {
                appendQueryParameter(Constants.PARAM_MAC_ADDRESS, macAddress)
            }
        }
        .build()
