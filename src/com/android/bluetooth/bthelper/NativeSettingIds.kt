/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.android.bluetooth.bthelper

import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId

/** Stable IDs shared with the native Settings and volume panel clients. Never recycle IDs. */
internal object NativeSettingIds {
    const val ANC = DeviceSettingId.DEVICE_SETTING_ID_ANC
    const val MORE_SETTINGS = DeviceSettingId.DEVICE_SETTING_ID_MORE_SETTINGS

    // Provider-owned IDs, separate from framework IDs and the 3000-series expandable slots.
    const val PERSONALIZED_VOLUME = 1102
    const val CONVERSATION_AWARENESS = 1103
    const val AUTOMATIC_EAR_DETECTION = 1104

    const val DISCONNECT_WHEN_NOT_WEARING = 1201
    const val PAUSE_MUSIC_WHEN_SPEAKING = 1202
    const val RELATIVE_CONVERSATION_VOLUME = 1203
    const val HEAD_GESTURES = 1204
    const val ALTERNATE_HEAD_TRACKING_PACKETS = 1205
    const val LOW_LATENCY_AUDIO = 1206
    const val OFF_LISTENING_MODE = 1207
    const val ONE_BUD_ANC = 1208
    const val VOLUME_SWIPE = 1209
    const val CASE_TONES = 1210
    const val PRESS_SPEED = 1211
    const val PRESS_HOLD_DURATION = 1212
    const val ADAPTIVE_NOISE_LEVEL = 1213
    const val CYCLE_NOISE_CANCELLATION = 1214
    const val CYCLE_TRANSPARENCY = 1215
    const val CYCLE_ADAPTIVE = 1216
    const val CYCLE_OFF = 1217
    const val MORE_SETTINGS_FOOTER = 1218
    const val SLEEP_DETECTION = 1219
    const val CONVERSATION_VOLUME = 1221
    const val MIC_MODE = 1222
    const val LEFT_PRESS_HOLD_ACTION = 1223
    const val RIGHT_PRESS_HOLD_ACTION = 1224
    const val VOLUME_SWIPE_DURATION = 1225
    const val CHIME_VOLUME = 1226
    const val CROWN_REVERSED = 1227
    const val ACCESSORY_EAR_DETECTION = 1228
    const val AUTO_CONNECT = 1229
}
