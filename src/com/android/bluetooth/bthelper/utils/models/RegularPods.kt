/*
 * SPDX-FileCopyrightText: Federico Dossena
 * SPDX-FileCopyrightText: LibrePods contributors
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.utils.models

data class RegularPodsMetadata(
    val manufacturer: String = Constants.UNKNOWN,
    val model: String = Constants.UNKNOWN,
    val lowBattThreshold: Int = 20,
    val drawable: Int = 0,
    val leftDrawable: Int = 0,
    val rightDrawable: Int = 0,
    val caseDrawable: Int = 0,
)

open class RegularPods : IPods() {
    open val modelNumbers: List<String>
        get() = emptyList()

    open val drawable: Int
        get() = 0

    open val leftDrawable: Int
        get() = 0

    open val rightDrawable: Int
        get() = 0

    open val caseDrawable: Int
        get() = 0

    override val model: String
        get() = Constants.UNKNOWN

    override val isSingle: Boolean
        get() = false

    override val lowBattThreshold: Int
        get() = // Most AirPods have same Low Battery Threshold to 20
        20

    override val manufacturer: String
        get() = Constants.UNKNOWN

    open val capabilities: Set<Constants.Capability>
        get() = emptySet()
}
