/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.android.bluetooth.bthelper

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.core.graphics.drawable.toBitmap
import com.android.bluetooth.bthelper.pods.AccessoryControls.Control
import com.android.bluetooth.bthelper.pods.AccessoryControls.State
import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreference
import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreferenceState
import com.android.settingslib.bluetooth.devicesettings.DeviceSetting
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingContract.PREFERENCE_UPDATE_TOKEN
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingFooterPreference
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingGroup
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingPreference
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreference
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreferenceState
import com.android.settingslib.bluetooth.devicesettings.ToggleInfo
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class NativeSettingsAction(
    val address: String,
    val session: Long,
    val control: Control,
    val value: Int,
)

/** Adapts the single accessory session to the SettingsLib device-settings ABI. */
internal class NativeDeviceSettings(private val context: Context) {
    private data class Switch(
        val id: Int,
        val control: Control,
        val title: Int,
        val summary: Int = 0,
    )

    private data class Segments(
        val id: Int, val control: Control, val title: Int, val labels: IntArray, val icons: IntArray,
    )

    private data class Choice(val value: Int, val label: String, val icon: Int)

    private companion object {
        const val SOUND_GROUP = 0
        const val PRESS_GROUP = 1
        const val ADVANCED_GROUP = 2

        // Put Off last: the headset can independently revoke it without shifting the other modes.
        val listeningModes = intArrayOf(2, 3, 4, 1)
        val listeningIcons = intArrayOf(
            R.drawable.bt_mode_off, R.drawable.bt_mode_noise_cancellation,
            R.drawable.bt_mode_transparency, R.drawable.bt_mode_adaptive,
        )
        val cycleIds = intArrayOf(
            NativeSettingIds.CYCLE_OFF, NativeSettingIds.CYCLE_NOISE_CANCELLATION,
            NativeSettingIds.CYCLE_TRANSPARENCY, NativeSettingIds.CYCLE_ADAPTIVE,
        )
        val panelSwitches = arrayOf(
            Switch(NativeSettingIds.PERSONALIZED_VOLUME, Control.PERSONALIZED_VOLUME,
                R.string.personalized_volume_title, R.string.personalized_volume_summary),
            Switch(NativeSettingIds.CONVERSATION_AWARENESS, Control.CONVERSATION_AWARENESS,
                R.string.conversational_awareness_title, R.string.conversational_awareness_summary),
            Switch(NativeSettingIds.AUTOMATIC_EAR_DETECTION, Control.AUTOMATIC_EAR_DETECTION,
                R.string.automatic_ear_detection_title),
        )
        val soundSwitches = arrayOf(
            Switch(NativeSettingIds.OFF_LISTENING_MODE, Control.OFF_LISTENING_MODE,
                R.string.native_off_listening_mode),
            Switch(NativeSettingIds.ONE_BUD_ANC, Control.ONE_BUD_ANC, R.string.native_one_bud_anc),
            Switch(NativeSettingIds.PAUSE_MUSIC_WHEN_SPEAKING, Control.PAUSE_MUSIC_WHEN_SPEAKING,
                R.string.conversational_awareness_pause_music_title,
                R.string.conversational_awareness_pause_music_summary),
            Switch(NativeSettingIds.RELATIVE_CONVERSATION_VOLUME, Control.RELATIVE_CONVERSATION_VOLUME,
                R.string.relative_conversational_awareness_volume_title,
                R.string.relative_conversational_awareness_volume_summary),
            Switch(NativeSettingIds.ACCESSORY_EAR_DETECTION, Control.ACCESSORY_EAR_DETECTION,
                R.string.native_accessory_ear_detection),
        )
        val pressSwitches = arrayOf(
            Switch(NativeSettingIds.VOLUME_SWIPE, Control.VOLUME_SWIPE, R.string.native_volume_swipe),
            Switch(NativeSettingIds.HEAD_GESTURES, Control.HEAD_GESTURES,
                R.string.head_gestures_title, R.string.head_gestures_summary),
            Switch(NativeSettingIds.CROWN_REVERSED, Control.CROWN_REVERSED,
                R.string.native_crown_reversed),
        )
        val advancedSwitches = arrayOf(
            Switch(NativeSettingIds.DISCONNECT_WHEN_NOT_WEARING, Control.DISCONNECT_WHEN_NOT_WEARING,
                R.string.disconnect_when_not_wearing_title),
            Switch(NativeSettingIds.ALTERNATE_HEAD_TRACKING_PACKETS, Control.ALTERNATE_HEAD_TRACKING_PACKETS,
                R.string.use_alternate_head_tracking_packets_title,
                R.string.use_alternate_head_tracking_packets_summary),
            Switch(NativeSettingIds.LOW_LATENCY_AUDIO, Control.LOW_LATENCY_AUDIO,
                R.string.low_latency_audio_title, R.string.low_latency_audio_summary),
            Switch(NativeSettingIds.CASE_TONES, Control.CASE_TONES, R.string.native_case_tones),
            Switch(NativeSettingIds.SLEEP_DETECTION, Control.SLEEP_DETECTION,
                R.string.native_sleep_detection),
            Switch(NativeSettingIds.AUTO_CONNECT, Control.AUTO_CONNECT, R.string.native_auto_connect),
        )
        val switchesById = (panelSwitches + soundSwitches + pressSwitches + advancedSwitches)
            .associateBy { it.id }
        val pressLabels = intArrayOf(
            R.string.native_press_default, R.string.native_press_slower, R.string.native_press_slowest,
        )
        val holdLabels = intArrayOf(
            R.string.native_press_default, R.string.native_press_longer, R.string.native_press_longest,
        )
        val pressIcons = intArrayOf(
            R.drawable.bt_mode_speed, R.drawable.bt_mode_speed_slower, R.drawable.bt_mode_speed_slowest,
        )
        val pressHoldActionLabels = intArrayOf(R.string.listening_mode_title, R.string.native_siri)
        val pressHoldActionIcons = intArrayOf(R.drawable.bt_mode_noise_cancellation, R.drawable.bt_mode_mic)
        val soundSegments = arrayOf(
            Segments(NativeSettingIds.MIC_MODE, Control.MIC_MODE, R.string.native_microphone,
                intArrayOf(R.string.native_microphone_auto, R.string.native_right, R.string.native_left),
                intArrayOf(R.drawable.bt_mode_mic, R.drawable.bt_mode_right, R.drawable.bt_mode_left)),
        )
        val pressSegments = arrayOf(
            Segments(NativeSettingIds.PRESS_SPEED, Control.PRESS_SPEED, R.string.native_press_speed,
                pressLabels, pressIcons),
            Segments(NativeSettingIds.PRESS_HOLD_DURATION, Control.PRESS_HOLD_DURATION,
                R.string.native_press_hold_duration, holdLabels, pressIcons),
            Segments(NativeSettingIds.LEFT_PRESS_HOLD_ACTION, Control.LEFT_PRESS_HOLD_ACTION,
                R.string.native_left_press_hold, pressHoldActionLabels, pressHoldActionIcons),
            Segments(NativeSettingIds.RIGHT_PRESS_HOLD_ACTION, Control.RIGHT_PRESS_HOLD_ACTION,
                R.string.native_right_press_hold, pressHoldActionLabels, pressHoldActionIcons),
            Segments(NativeSettingIds.VOLUME_SWIPE_DURATION, Control.VOLUME_SWIPE_DURATION,
                R.string.native_volume_swipe_duration, holdLabels, pressIcons),
        )
        val segmentsById = (soundSegments + pressSegments).associateBy { it.id }
        val adaptivePresets = intArrayOf(0, 50, 100)
        val conversationPresets = intArrayOf(0, Control.CONVERSATION_VOLUME.default, 100)
    }

    private val icons = ConcurrentHashMap<Int, Bitmap>()
    private val instance = UUID.randomUUID().toString()

    // SettingsLib caches the configuration, but observes the models. Keep the slots stable
    // across reconnects; unsupported controls and unreported values never produce a live model.
    fun config(): DeviceSettingsConfig = DeviceSettingsConfig(
        mainContentItems = buildList {
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_HEADER, "bluetooth_details_banner"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_GENERAL_BLUETOOTH_DEVICE_HEADER,
                "general_bluetooth_device_header"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_ADVANCED_HEADER, "advanced_bluetooth_device_header"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_LE_AUDIO_HEADER, "le_audio_bluetooth_device_header"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_HEARING_AID_PAIR_OTHER_BUTTON, "hearing_aid_pair_other_button"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_HEARING_AID_SPACE_LAYOUT, "hearing_aid_space_layout"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_ACTION_BUTTONS, "action_buttons"))
            add(provided(NativeSettingIds.ANC))
            panelSwitches.forEach { add(provided(it.id)) }
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_SPATIAL_AUDIO_MULTI_TOGGLE, "spatial_audio_group"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_EXTRA_CONTROL, "audio_sharing_control"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_DEVICE_COMPANION_APPS, "device_companion_apps"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_HEARING_DEVICE_GROUP, "hearing_device_group"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_AUDIO_DEVICE_TYPE_GROUP, "bluetooth_audio_device_type_group"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_PROFILES, "bluetooth_profiles"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_EXTRA_OPTIONS, "bt_extra_options"))
            add(DeviceSettingItem(NativeSettingIds.MORE_SETTINGS))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_RELATED_TOOLS, "bluetooth_related_tools"))
            add(builtin(DeviceSettingId.DEVICE_SETTING_ID_DEVICE_DETAILS_FOOTER, "device_details_footer"))
        },
        moreSettingsItems = buildList {
            soundSwitches.forEach { add(provided(it.id, SOUND_GROUP)) }
            add(provided(NativeSettingIds.ADAPTIVE_NOISE_LEVEL, SOUND_GROUP))
            add(provided(NativeSettingIds.CONVERSATION_VOLUME, SOUND_GROUP))
            soundSegments.forEach { add(provided(it.id, SOUND_GROUP)) }
            listeningModes.forEach { add(provided(cycleIds[it - 1], PRESS_GROUP)) }
            pressSegments.forEach { add(provided(it.id, PRESS_GROUP)) }
            pressSwitches.forEach { add(provided(it.id, PRESS_GROUP)) }
            advancedSwitches.forEach { add(provided(it.id, ADVANCED_GROUP)) }
            add(provided(NativeSettingIds.CHIME_VOLUME, ADVANCED_GROUP))
            add(provided(NativeSettingIds.MORE_SETTINGS_FOOTER))
        },
        settingGroups = listOf(
            DeviceSettingGroup(context.getString(R.string.native_sound_controls)),
            DeviceSettingGroup(context.getString(R.string.native_press_controls)),
            DeviceSettingGroup(context.getString(R.string.native_advanced_controls)),
        ),
    )

    fun settings(state: State): List<DeviceSetting> = buildList {
        if (state.connected && state.address != null) {
            val mode = state.reported(Control.LISTENING_MODE)?.takeIf(state::supportsMode)
            if (mode != null) {
                val modes = availableModes(state)
                if (modes.size > 1) {
                    val labels = context.resources.getStringArray(R.array.listening_modes)
                    add(multiToggle(NativeSettingIds.ANC, R.string.listening_mode_title,
                        modes.map { Choice(it, labels[it - 1], listeningIcons[it - 1]) },
                        mode, state.pending(Control.LISTENING_MODE) == null,
                        actionExtras(state, NativeSettingIds.ANC, Control.LISTENING_MODE)))
                }
            }
            switchesById.values.forEach { row ->
                val value = state.reported(row.control)
                if (value == 0 || value == 1) {
                    add(switchSetting(row.id, context.getString(row.title), value == 1,
                        state.pending(row.control) == null,
                        actionExtras(state, row.id, row.control),
                        row.summary.takeIf { it != 0 }?.let { context.getString(it) }))
                }
            }
            val cycle = cycleMask(state)
            if (cycle != null) {
                val labels = context.resources.getStringArray(R.array.listening_modes)
                for (value in listeningModes) {
                    if (!state.supportsMode(value)) continue
                    val bit = modeBit(value)
                    val checked = cycle and bit != 0
                    val id = cycleIds[value - 1]
                    add(switchSetting(id,
                        context.getString(R.string.native_cycle_mode, labels[value - 1]), checked,
                        state.pending(Control.LISTENING_MODE_CYCLE) == null && (!checked || cycle != bit),
                        actionExtras(state, id, Control.LISTENING_MODE_CYCLE)))
                }
            }
            segmentsById.values.forEach { addSegmentSetting(state, it) }
            addPercentSetting(state, Control.ADAPTIVE_NOISE_LEVEL, NativeSettingIds.ADAPTIVE_NOISE_LEVEL,
                R.string.native_adaptive_noise_level, adaptivePresets)
            addPercentSetting(state, Control.CONVERSATION_VOLUME, NativeSettingIds.CONVERSATION_VOLUME,
                R.string.native_conversation_volume, conversationPresets)
            addPercentSetting(state, Control.CHIME_VOLUME, NativeSettingIds.CHIME_VOLUME,
                R.string.native_chime_volume, adaptivePresets)
        }
        add(setting(NativeSettingIds.MORE_SETTINGS_FOOTER,
            DeviceSettingFooterPreference.Builder()
                .setFooterText(context.getString(R.string.native_settings_footer))
                .build()))
    }

    fun decode(state: State, update: DeviceSettingState): NativeSettingsAction? {
        val address = state.address ?: return null
        if (!state.connected) return null
        val id = update.settingId
        val preference = update.preferenceState
        val row = switchesById[id]
        val segments = segmentsById[id]
        val control: Control
        val value: Int
        if (row != null) {
            control = row.control
            if (state.reported(control)?.let { it in 0..1 } != true) return null
            value = if ((preference as? ActionSwitchPreferenceState)?.checked ?: return null) 1 else 0
        } else if (segments != null) {
            control = segments.control
            if (state.reported(control)?.let { it in segments.labels.indices } != true) return null
            value = (preference as? MultiTogglePreferenceState)?.state
                ?.takeIf { it in segments.labels.indices } ?: return null
        } else when (id) {
            NativeSettingIds.ANC -> {
                control = Control.LISTENING_MODE
                val current = state.reported(control) ?: return null
                if (!state.supportsMode(current)) return null
                val modes = availableModes(state)
                if (modes.size < 2) return null
                val index = (preference as? MultiTogglePreferenceState)?.state ?: return null
                value = modes.getOrNull(index) ?: return null
            }
            NativeSettingIds.ADAPTIVE_NOISE_LEVEL, NativeSettingIds.CONVERSATION_VOLUME,
            NativeSettingIds.CHIME_VOLUME -> {
                control = when (id) {
                    NativeSettingIds.ADAPTIVE_NOISE_LEVEL -> Control.ADAPTIVE_NOISE_LEVEL
                    NativeSettingIds.CONVERSATION_VOLUME -> Control.CONVERSATION_VOLUME
                    else -> Control.CHIME_VOLUME
                }
                val presets = if (control == Control.CONVERSATION_VOLUME) conversationPresets else adaptivePresets
                if (state.reported(control)?.let { it in 0..100 } != true) return null
                val index = (preference as? MultiTogglePreferenceState)?.state ?: return null
                value = presets.getOrNull(index) ?: return null
            }
            else -> {
                val mode = cycleIds.indexOf(id) + 1
                if (!state.supportsMode(mode)) return null
                control = Control.LISTENING_MODE_CYCLE
                val mask = cycleMask(state) ?: return null
                val checked = (preference as? ActionSwitchPreferenceState)?.checked ?: return null
                val bit = modeBit(mode)
                value = (if (checked) mask or bit else mask and bit.inv()).takeIf { it != 0 } ?: return null
            }
        }
        if (!state.accepts(control, value) || state.pending(control) != null || state.value(control) == value) return null
        val token = when (preference) {
            is ActionSwitchPreferenceState -> preference.extras.getString(PREFERENCE_UPDATE_TOKEN)
            is MultiTogglePreferenceState -> preference.extras.getString(PREFERENCE_UPDATE_TOKEN)
            else -> return null
        }
        if (token != actionToken(state, id, control)) return null
        return NativeSettingsAction(address, state.session, control, value)
    }

    private fun State.reported(control: Control): Int? = if (supports(control)) value(control) else null

    private fun availableModes(state: State) = listeningModes.filter(state::supportsMode)

    private fun modeBit(mode: Int) = 1 shl (mode - 1)

    private fun cycleMask(state: State) = state.reported(Control.LISTENING_MODE_CYCLE)
        ?.takeIf { state.accepts(Control.LISTENING_MODE_CYCLE, it) }

    private fun actionExtras(state: State, id: Int, control: Control) = Bundle().apply {
        putString(PREFERENCE_UPDATE_TOKEN, actionToken(state, id, control))
    }

    private fun actionToken(state: State, id: Int, control: Control): String {
        // SettingsLib will send this token back through the callback for this rendered model.
        // Including the mode mask makes positional clicks safe when options change in flight.
        val mask = if (control == Control.LISTENING_MODE || control == Control.LISTENING_MODE_CYCLE) {
            listeningModes.fold(0) { bits, mode -> if (state.supportsMode(mode)) bits or modeBit(mode) else bits }
        } else {
            0
        }
        return "$instance/${state.address}/${state.session}/$id/${state.reported(control)}/$mask"
    }

    private fun MutableList<DeviceSetting>.addSegmentSetting(state: State, row: Segments) {
        val value = state.reported(row.control)?.takeIf { it in row.labels.indices } ?: return
        add(multiToggle(row.id, row.title, row.labels.indices.map {
            Choice(it, context.getString(row.labels[it]), row.icons[it])
        }, value, state.pending(row.control) == null, actionExtras(state, row.id, row.control)))
    }

    private fun MutableList<DeviceSetting>.addPercentSetting(
        state: State, control: Control, id: Int, title: Int, presets: IntArray,
    ) {
        val value = state.reported(control)?.takeIf { it in control.range } ?: return
        // The ABI exposes segments, not a slider. Keep a nonpreset reported value selected
        // at the end without making that display-only segment a distinct write target.
        val choices = if (value in presets) presets else presets + value
        add(multiToggle(id, title, choices.map {
            Choice(it, context.getString(R.string.native_percent, it), R.drawable.bt_mode_adaptive)
        }, value, state.pending(control) == null, actionExtras(state, id, control)))
    }

    private fun multiToggle(
        id: Int, title: Int, choices: List<Choice>, value: Int, enabled: Boolean, extras: Bundle,
    ): DeviceSetting {
        val preference = MultiTogglePreference.Builder()
            .setTitle(context.getString(title))
            .setState(choices.indexOfFirst { it.value == value })
            .setIsActive(true)
            .setAllowChangingState(enabled)
            .setExtras(extras)
        for (choice in choices) {
            val icon = icons.computeIfAbsent(choice.icon) {
                requireNotNull(context.getDrawable(choice.icon)).toBitmap(24, 24, Bitmap.Config.ARGB_8888)
            }
            preference.addToggleInfo(ToggleInfo.Builder().setLabel(choice.label).setIcon(icon).build())
        }
        return setting(id, preference.build())
    }

    private fun switchSetting(
        id: Int, title: String, checked: Boolean, enabled: Boolean, extras: Bundle, summary: String? = null,
    ) =
        setting(id, ActionSwitchPreference.Builder()
            .setTitle(title)
            .setSummary(summary)
            .setHasSwitch(true)
            .setChecked(checked)
            .setAllowedChangingState(enabled)
            .setExtras(extras)
            .build())

    private fun builtin(id: Int, key: String) = DeviceSettingItem(id, preferenceKey = key)

    private fun provided(id: Int, group: Int? = null) = DeviceSettingItem(
        id,
        packageName = context.packageName,
        className = BtHelperDeviceSettingsProviderService::class.java.name,
        intentAction = DEVICE_SETTINGS_PROVIDER_ACTION,
        groupIndex = group,
    )

    private fun setting(id: Int, preference: DeviceSettingPreference) =
        DeviceSetting.Builder().setSettingId(id).setPreference(preference).build()
}
