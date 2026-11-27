/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.android.bluetooth.bthelper.pods

import android.content.SharedPreferences
import com.android.bluetooth.bthelper.Constants
import com.android.bluetooth.bthelper.utils.AACPManager.ControlCommand
import com.android.bluetooth.bthelper.utils.AACPManager.ControlCommandIdentifiers as Wire
import com.android.bluetooth.bthelper.utils.models.AirPodsMax
import com.android.bluetooth.bthelper.utils.models.AirPodsMaxUsbC
import com.android.bluetooth.bthelper.utils.models.Constants.Capability
import com.android.bluetooth.bthelper.utils.models.IPods
import com.android.bluetooth.bthelper.utils.models.RegularPods
import com.android.bluetooth.bthelper.utils.models.SinglePods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One current AACP session. Only capability evidence and Android policy survive a link. */
internal object AccessoryControls {
    const val WEAR = 1
    private const val PERSONAL_VOLUME = 2
    private const val CONVERSATION_CONFIG = 4
    const val CONVERSATION_EVENTS = 8
    const val HEAD_GESTURE_REPORTS = 16
    private const val OFF = 256
    private const val BASIC_MODES = OFF or (OFF shl 1) or (OFF shl 2)
    private const val ADAPTIVE = OFF shl 3
    private const val SWIPE = 1 shl 12
    private const val SLEEP = 1 shl 13
    private const val STEM = 1 shl 15

    enum class Control(
        val wire: Wire? = null,
        val range: IntRange = 0..1,
        private val trueFirst: Boolean = false,
        val preference: String? = null,
        val default: Int = 0,
    ) {
        LISTENING_MODE(Wire.LISTENING_MODE, 1..4),
        PERSONALIZED_VOLUME(Wire.ADAPTIVE_VOLUME_CONFIG, trueFirst = true),
        CONVERSATION_AWARENESS(Wire.CONVERSATION_DETECT_CONFIG, trueFirst = true),
        OFF_LISTENING_MODE(Wire.ALLOW_OFF_OPTION, trueFirst = true),
        LISTENING_MODE_CYCLE(Wire.LISTENING_MODE_CONFIGS, 1..15),
        ONE_BUD_ANC(Wire.ONE_BUD_ANC_MODE, trueFirst = true),
        VOLUME_SWIPE(Wire.VOLUME_SWIPE_MODE, trueFirst = true),
        CASE_TONES(Wire.IN_CASE_TONE_CONFIG, trueFirst = true),
        ADAPTIVE_NOISE_LEVEL(Wire.AUTO_ANC_STRENGTH, 0..100),
        PRESS_SPEED(Wire.DOUBLE_CLICK_INTERVAL, 0..2),
        PRESS_HOLD_DURATION(Wire.CLICK_HOLD_INTERVAL, 0..2),
        SLEEP_DETECTION(Wire.SLEEP_DETECTION_CONFIG, trueFirst = true),
        MIC_MODE(Wire.MIC_MODE, 0..2),
        LEFT_PRESS_HOLD_ACTION(Wire.CLICK_HOLD_MODE),
        RIGHT_PRESS_HOLD_ACTION(Wire.CLICK_HOLD_MODE),
        VOLUME_SWIPE_DURATION(Wire.VOLUME_SWIPE_INTERVAL, 0..2),
        CHIME_VOLUME(Wire.CHIME_VOLUME, 0..100),
        CROWN_REVERSED(Wire.CROWN_ROTATION_DIRECTION, trueFirst = true),
        ACCESSORY_EAR_DETECTION(Wire.EAR_DETECTION_CONFIG, trueFirst = true),
        AUTO_CONNECT(Wire.AUTOMATIC_CONNECTION_CONFIG, trueFirst = true),
        AUTOMATIC_EAR_DETECTION(preference = Constants.KEY_AUTOMATIC_EAR_DETECTION, default = 1),
        DISCONNECT_WHEN_NOT_WEARING(preference = Constants.KEY_DISCONNECT_WHEN_NOT_WEARING),
        PAUSE_MUSIC_WHEN_SPEAKING(preference = Constants.KEY_CONVERSATIONAL_AWARENESS_PAUSE_MUSIC),
        RELATIVE_CONVERSATION_VOLUME(preference = Constants.KEY_RELATIVE_CONVERSATIONAL_AWARENESS_VOLUME, default = 1),
        CONVERSATION_VOLUME(range = 0..100, preference = Constants.KEY_CONVERSATIONAL_AWARENESS_VOLUME, default = 43),
        HEAD_GESTURES(preference = Constants.KEY_HEAD_GESTURES, default = 1),
        ALTERNATE_HEAD_TRACKING_PACKETS(preference = Constants.KEY_USE_ALTERNATE_HEAD_TRACKING_PACKETS),
        LOW_LATENCY_AUDIO(preference = Constants.KEY_LOW_LATENCY_AUDIO);

        fun fromWire(value: Int): Int? = when {
            trueFirst -> when (value) { 1 -> 1; 2 -> 0; else -> null }
            else -> value.takeIf { it in range }
        }

        fun toWire(value: Int): Int = when {
            trueFirst -> if (value == 1) 1 else 2
            else -> value
        }
    }

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
        if (Capability.HEAD_GESTURES in caps) bits = bits or HEAD_GESTURE_REPORTS
        if (Capability.SWIPE_FOR_VOLUME in caps) bits = bits or SWIPE
        if (Capability.SLEEP_DETECTION in caps) bits = bits or SLEEP
        if (Capability.STEM_CONFIG in caps) bits = bits or STEM
        return bits
    }

    data class Pending(val request: Long, val value: Int)

    data class State(
        val address: String? = null,
        val session: Long = 0,
        val connected: Boolean = false,
        val declared: Int = 0,
        val learned: Int = 0,
        val sessionReports: Int = 0,
        val latencySupported: Boolean = false,
        internal val confirmed: Map<Control, Int> = emptyMap(),
        internal val requested: Map<Control, Pending> = emptyMap(),
    ) {
        val mode get() = value(Control.LISTENING_MODE) ?: 0
        fun value(control: Control): Int? = confirmed[control]
        fun pending(control: Control): Int? = requested[control]?.value
        fun allows(feature: Int) = connected && hasFeature(feature)
        private fun hasFeature(feature: Int) = ((declared or learned) and feature) != 0
        fun hasStemConfig() = allows(STEM)
        fun supportsMode(value: Int): Boolean = connected && hasMode(value) &&
            (value != 1 || this.value(Control.OFF_LISTENING_MODE) != 0)
        private fun hasMode(value: Int) = value in 1..4 && hasFeature(OFF shl (value - 1))

        fun supports(control: Control): Boolean {
            if (!connected) return false
            return when (control) {
                Control.LISTENING_MODE -> (1..4).any(::supportsMode)
                Control.LISTENING_MODE_CYCLE -> confirmed.containsKey(control)
                Control.PERSONALIZED_VOLUME -> hasFeature(PERSONAL_VOLUME)
                Control.CONVERSATION_AWARENESS -> hasFeature(CONVERSATION_CONFIG)
                Control.VOLUME_SWIPE -> hasFeature(SWIPE) || confirmed.containsKey(control)
                Control.SLEEP_DETECTION -> hasFeature(SLEEP) || confirmed.containsKey(control)
                Control.AUTOMATIC_EAR_DETECTION, Control.DISCONNECT_WHEN_NOT_WEARING -> hasFeature(WEAR)
                Control.PAUSE_MUSIC_WHEN_SPEAKING, Control.RELATIVE_CONVERSATION_VOLUME,
                Control.CONVERSATION_VOLUME -> hasFeature(CONVERSATION_EVENTS)
                Control.HEAD_GESTURES, Control.ALTERNATE_HEAD_TRACKING_PACKETS -> hasFeature(HEAD_GESTURE_REPORTS)
                Control.LOW_LATENCY_AUDIO -> latencySupported
                else -> confirmed.containsKey(control) // Opcode names alone do not establish support.
            }
        }

        fun accepts(control: Control, value: Int): Boolean = supports(control) && value in control.range && when (control) {
            Control.LISTENING_MODE -> supportsMode(value)
            Control.LISTENING_MODE_CYCLE -> (1..4).all { mode -> value and (1 shl (mode - 1)) == 0 || supportsMode(mode) }
            Control.LEFT_PRESS_HOLD_ACTION, Control.RIGHT_PRESS_HOLD_ACTION ->
                pending(Control.LEFT_PRESS_HOLD_ACTION) == null && pending(Control.RIGHT_PRESS_HOLD_ACTION) == null
            else -> true
        }
    }

    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()
    private val wireControls = Control.entries.mapNotNull { control -> control.wire?.value?.let { it to control } }.toMap()
    private val hostControls = Control.entries.filter { it.preference != null }
    private var scheduleExpiration: ((Runnable, Long) -> Unit)? = null
    private var preferences: SharedPreferences? = null
    private var send: ((Control, Int) -> Boolean)? = null
    private var generation = 0L
    private var nextRequest = 0L
    private fun evidenceKey(address: String) = "aacp_capabilities:$address"
    private fun hostKey(address: String, control: Control) = "${control.preference}:$address"

    @Synchronized fun begin(address: String, storage: SharedPreferences, latencySupported: Boolean, schedule: (Runnable, Long) -> Unit) {
        scheduleExpiration = schedule
        preferences = storage
        send = null
        current.value = State(address, ++generation, learned = storage.getInt(evidenceKey(address), 0), latencySupported = latencySupported)
    }

    @Synchronized fun end(address: String) {
        if (current.value.address != address) return
        current.value = State(session = ++generation)
        preferences = null
        send = null
        scheduleExpiration = null
    }

    @Synchronized fun forget(address: String, storage: SharedPreferences) {
        end(address)
        val editor = storage.edit().remove(evidenceKey(address))
        for (control in hostControls) editor.remove(hostKey(address, control))
        editor.apply()
    }

    @Synchronized fun link(address: String, writer: ((Control, Int) -> Boolean)?): Long? {
        val s = current.value
        val storage = preferences ?: return null
        if (s.address != address) return null
        send = writer
        val host = if (writer == null) emptyMap() else hostControls.associateWith {
            val fallback = if (it == Control.CONVERSATION_VOLUME) it.default else if (storage.getBoolean(it.preference, it.default == 1)) 1 else 0
            storage.getInt(hostKey(address, it), fallback).takeIf(it.range::contains) ?: fallback
        }
        current.value = s.copy(session = ++generation, connected = writer != null, confirmed = host, requested = emptyMap(), sessionReports = 0)
        return generation
    }

    @Synchronized fun declare(address: String, features: Int) {
        val s = current.value
        if (s.address == address && s.declared != features) current.value = s.copy(declared = features)
    }

    private inline fun report(session: Long, features: Int, change: (State) -> State) {
        val s = current.value
        val address = s.address ?: return
        if (!s.connected || s.session != session) return
        val learned = s.learned or features
        if (s.learned != learned) preferences?.edit()?.putInt(evidenceKey(address), learned)?.apply()
        val seen = s.sessionReports or features
        current.value = change(if (s.learned == learned && s.sessionReports == seen) s else s.copy(learned = learned, sessionReports = seen))
    }

    @Synchronized fun observed(session: Long, feature: Int) = report(session, feature) { it }

    @Synchronized fun received(session: Long, command: ControlCommand) {
        if (command.identifier == Wire.CLICK_HOLD_MODE.value) {
            if (command.value.size != 2) return
            val right = stemAction(command.value[0]) ?: return
            val left = stemAction(command.value[1]) ?: return
            report(session, 0) {
                it.copy(
                    confirmed = it.confirmed + mapOf(Control.LEFT_PRESS_HOLD_ACTION to left, Control.RIGHT_PRESS_HOLD_ACTION to right),
                    requested = it.requested - Control.LEFT_PRESS_HOLD_ACTION - Control.RIGHT_PRESS_HOLD_ACTION,
                )
            }
            return
        }
        val control = wireControls[command.identifier] ?: return
        val value = control.fromWire(command.value.singleOrNull()?.toUByte()?.toInt() ?: return) ?: return
        val features = when (control) {
            Control.LISTENING_MODE -> OFF shl (value - 1)
            Control.LISTENING_MODE_CYCLE -> value * OFF
            Control.PERSONALIZED_VOLUME -> PERSONAL_VOLUME
            Control.CONVERSATION_AWARENESS -> CONVERSATION_CONFIG
            else -> 0
        }
        report(session, features) {
            it.copy(confirmed = it.confirmed + (control to value), requested = it.requested - control)
        }
    }

    private fun stemAction(value: Byte): Int? = when (value.toInt()) { 1 -> 0; 5 -> 1; else -> null }

    fun encode(s: State, control: Control, value: Int): ByteArray? = when (control) {
        Control.LEFT_PRESS_HOLD_ACTION, Control.RIGHT_PRESS_HOLD_ACTION -> {
            val left = if (control == Control.LEFT_PRESS_HOLD_ACTION) value else s.value(Control.LEFT_PRESS_HOLD_ACTION)
            val right = if (control == Control.RIGHT_PRESS_HOLD_ACTION) value else s.value(Control.RIGHT_PRESS_HOLD_ACTION)
            if (right == null || left == null) null else byteArrayOf(if (right == 1) 5 else 1, if (left == 1) 5 else 1)
        }
        else -> byteArrayOf(control.toWire(value).toByte())
    }

    /** Writes target the captured link, even if Settings sends an update during a reconnect. */
    @Synchronized fun request(address: String, session: Long, control: Control, value: Int): Boolean {
        val s = current.value
        if (s.address != address || s.session != session || !s.accepts(control, value) || s.pending(control) != null) return false
        if (s.value(control) == value) return true
        if (send?.invoke(control, value) != true) return false
        if (control.preference != null) {
            preferences?.edit()?.putInt(hostKey(address, control), value)?.apply()
            current.value = s.copy(confirmed = s.confirmed + (control to value))
        } else {
            val request = ++nextRequest
            current.value = s.copy(requested = s.requested + (control to Pending(request, value)))
            scheduleExpiration?.invoke(Runnable { expire(session, control, request) }, 3000L)
        }
        return true
    }

    @Synchronized private fun expire(session: Long, control: Control, request: Long) {
        val s = current.value
        if (s.session == session && s.requested[control]?.request == request) {
            current.value = s.copy(requested = s.requested - control)
        }
    }
}
