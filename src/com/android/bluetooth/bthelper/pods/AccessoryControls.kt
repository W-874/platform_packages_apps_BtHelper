/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.android.bluetooth.bthelper.pods

import com.android.bluetooth.bthelper.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live evidence for the active bonded headset. Never restore controls from pairing history. */
internal object AccessoryControls {
    const val WEAR = 1
    const val PERSONAL_VOLUME = 2
    const val CONVERSATION_CONFIG = 4
    const val CONVERSATION_EVENTS = 8

    data class State(
        val address: String? = null,
        val connected: Boolean = false,
        val standardModel: Boolean = false,
        val observed: Int = 0,
        val mode: Int = 0,
        val modes: Int = 0,
    ) {
        fun allows(feature: Int) = connected && (standardModel || (observed and feature) != 0)
        fun reportedMode(value: Int) = connected && value in 1..4 && (modes and (1 shl value)) != 0

        fun enables(key: String): Boolean = when (key) {
            Constants.KEY_AUTOMATIC_EAR_DETECTION, Constants.KEY_DISCONNECT_WHEN_NOT_WEARING -> allows(WEAR)
            Constants.KEY_PERSONALIZED_VOLUME -> allows(PERSONAL_VOLUME)
            Constants.KEY_CONVERSATIONAL_AWARENESS -> allows(CONVERSATION_CONFIG)
            Constants.KEY_CONVERSATIONAL_AWARENESS_PAUSE_MUSIC,
            Constants.KEY_RELATIVE_CONVERSATIONAL_AWARENESS_VOLUME -> allows(CONVERSATION_EVENTS)
            Constants.KEY_HEAD_GESTURES, Constants.KEY_USE_ALTERNATE_HEAD_TRACKING_PACKETS -> connected && standardModel
            Constants.KEY_LOW_LATENCY_AUDIO -> address != null // Android audio policy, not an AACP command
            else -> false
        }
    }

    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()

    fun begin(address: String) { current.value = State(address) }
    fun end(address: String) { current.update { if (it.address == address) State() else it } }
    fun link(address: String, connected: Boolean) {
        current.update { if (it.address == address) it.copy(connected = connected, observed = 0, mode = 0, modes = 0) else it }
    }
    fun verified(address: String) {
        current.update { if (it.address == address) it.copy(standardModel = true) else it }
    }
    fun listeningMode(address: String, mode: Int) {
        if (mode !in 1..4) return
        current.update {
            if (it.connected && it.address == address && it.mode != mode) it.copy(mode = mode, modes = it.modes or (1 shl mode)) else it
        }
    }
    fun observed(address: String, feature: Int) {
        current.update {
            if (it.connected && it.address == address && it.observed and feature == 0)
                it.copy(observed = it.observed or feature) else it
        }
    }
}
