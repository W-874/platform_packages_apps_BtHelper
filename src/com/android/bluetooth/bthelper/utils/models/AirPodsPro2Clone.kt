/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils.models

import com.android.bluetooth.bthelper.Constants.Icons

internal class AirPodsPro2Clone : RegularPods() {
    override val modelId: Int
        get() = 0x1420

    override val drawable: Int
        get() = Icons.AirPods_Pro

    override val leftDrawable: Int
        get() = Icons.AirPods_Pro_Left

    override val rightDrawable: Int
        get() = Icons.AirPods_Pro_Right

    override val caseDrawable: Int
        get() = Icons.AirPods_Pro_Case

    override val model: String
        get() = "AirPods Pro 2 Clone"
}
