/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.android.bluetooth.bthelper.pods

import android.content.SharedPreferences
import com.android.bluetooth.bthelper.Constants
import com.android.bluetooth.bthelper.utils.models.AirPodsMax
import com.android.bluetooth.bthelper.utils.models.AirPodsMaxUsbC
import com.android.bluetooth.bthelper.utils.models.Constants.Capability
import com.android.bluetooth.bthelper.utils.models.IPods
import com.android.bluetooth.bthelper.utils.models.RegularPods
import com.android.bluetooth.bthelper.utils.models.SinglePods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bond-scoped feature evidence; settings values belong only to the live AACP session. */
internal object AccessoryControls {
    const val WEAR = 1
    const val PERSONAL_VOLUME = 2
    const val CONVERSATION_CONFIG = 4
    const val CONVERSATION_EVENTS = 8
    const val HEAD_GESTURES = 16
    private const val OFF = 256
    private const val BASIC_MODES = OFF or (OFF shl 1) or (OFF shl 2)
    private const val ADAPTIVE = OFF shl 3

    fun profile(model: IPods?): Int {
        val caps = when (model) {
            is RegularPods -> model.capabilities
            is SinglePods -> model.capabilities
            else -> return 0
        }
        var bits = WEAR
        if (Capability.LISTENING_MODE in caps || model is AirPodsMax || model is AirPodsMaxUsbC) bits = bits or BASIC_MODES
        if (Capability.ADAPTIVE_AUDIO in caps) bits = bits or ADAPTIVE
        if (Capability.ADAPTIVE_VOLUME in caps) bits = bits or PERSONAL_VOLUME
        if (Capability.CONVERSATION_AWARENESS in caps) bits = bits or CONVERSATION_CONFIG or CONVERSATION_EVENTS
        if (Capability.HEAD_GESTURES in caps) bits = bits or HEAD_GESTURES
        return bits
    }

    data class State(
        val address: String? = null,
        val connected: Boolean = false,
        val declared: Int = 0,
        val learned: Int = 0,
        val sessionReports: Int = 0,
        val mode: Int = 0,
        val offAllowed: Boolean? = null,
        val personalizedVolume: Boolean? = null,
        val conversation: Boolean? = null,
    ) {
        fun allows(feature: Int) = connected && ((declared or learned) and feature) != 0
        fun supportsMode(value: Int) = value in 1..4 && allows(OFF shl (value - 1)) && (value != 1 || offAllowed != false)
        fun setting(key: String): Boolean? = when (key) {
            Constants.KEY_PERSONALIZED_VOLUME -> personalizedVolume
            Constants.KEY_CONVERSATIONAL_AWARENESS -> conversation
            else -> null
        }

        fun enables(key: String): Boolean = when (key) {
            Constants.KEY_AUTOMATIC_EAR_DETECTION, Constants.KEY_DISCONNECT_WHEN_NOT_WEARING -> allows(WEAR)
            Constants.KEY_PERSONALIZED_VOLUME -> allows(PERSONAL_VOLUME)
            Constants.KEY_CONVERSATIONAL_AWARENESS -> allows(CONVERSATION_CONFIG)
            Constants.KEY_CONVERSATIONAL_AWARENESS_PAUSE_MUSIC,
            Constants.KEY_RELATIVE_CONVERSATIONAL_AWARENESS_VOLUME -> allows(CONVERSATION_EVENTS)
            Constants.KEY_HEAD_GESTURES, Constants.KEY_USE_ALTERNATE_HEAD_TRACKING_PACKETS -> allows(HEAD_GESTURES)
            Constants.KEY_LOW_LATENCY_AUDIO -> address != null // Android audio policy, not an AACP command
            else -> false
        }
    }

    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()

    private var preferences: SharedPreferences? = null
    private fun key(address: String) = "aacp_capabilities:$address"

    @Synchronized fun begin(address: String, storage: SharedPreferences) {
        preferences = storage
        current.value = State(address = address, learned = storage.getInt(key(address), 0))
    }

    @Synchronized fun end(address: String) {
        if (current.value.address != address) return
        current.value = State()
        preferences = null
    }

    @Synchronized fun forget(address: String, storage: SharedPreferences) {
        end(address)
        storage.edit().remove(key(address)).apply()
    }

    @Synchronized fun link(address: String, connected: Boolean) {
        val s = current.value
        if (s.address == address) current.value = State(address, connected, s.declared, s.learned)
    }

    @Synchronized fun declare(address: String, features: Int) {
        val s = current.value
        if (s.address == address && s.declared != features) current.value = s.copy(declared = features)
    }

    private inline fun report(address: String, features: Int, change: (State) -> State) {
        val s = current.value
        if (!s.connected || s.address != address) return
        val learned = s.learned or features
        if (s.learned != learned) preferences?.edit()?.putInt(key(address), learned)?.apply()
        val seen = s.sessionReports or features
        current.value = change(if (s.learned == learned && s.sessionReports == seen) s else s.copy(learned = learned, sessionReports = seen))
    }

    @Synchronized fun observed(address: String, feature: Int) = report(address, feature) { it }

    @Synchronized fun listeningMode(address: String, mode: Int) {
        if (mode in 1..4) report(address, OFF shl (mode - 1)) {
            if (it.mode == mode) it else it.copy(mode = mode)
        }
    }

    // The configured stem cycle can confirm a mode, but omitting it does not revoke support.
    @Synchronized fun reportCycle(address: String, mask: Int) {
        if (mask in 1..15) report(address, mask * OFF) { it }
    }

    @Synchronized fun reportOption(address: String, feature: Int, enabled: Boolean) {
        report(address, feature) {
            when (feature) {
                PERSONAL_VOLUME -> it.copy(personalizedVolume = enabled)
                CONVERSATION_CONFIG -> it.copy(conversation = enabled)
                else -> it
            }
        }
    }

    @Synchronized fun allowOff(address: String, allowed: Boolean) {
        report(address, 0) { it.copy(offAllowed = allowed) }
    }
}
