/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils.models

import com.android.bluetooth.bthelper.Constants.Icons

internal object AirPodsPro2Compatible : RegularPods() {
    override val model = "AirPods Pro 2 Compatible"
    override val drawable = Icons.AirPods_Pro
    override val leftDrawable = Icons.AirPods_Pro_Left
    override val rightDrawable = Icons.AirPods_Pro_Right
    override val caseDrawable = Icons.AirPods_Pro_Case
}
