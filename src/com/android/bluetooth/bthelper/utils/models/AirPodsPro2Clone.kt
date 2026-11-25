/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils.models

internal class AirPodsPro2Clone : AirPodsPro2() {
    override val manufacturer: String
        get() = Constants.UNKNOWN

    override val modelNumbers: List<String>
        get() = emptyList()

    override val model: String
        get() = "AirPods Pro 2 Clone"
}
