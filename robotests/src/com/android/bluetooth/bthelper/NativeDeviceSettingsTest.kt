/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.android.bluetooth.bthelper

import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.bthelper.pods.AccessoryControls
import com.android.bluetooth.bthelper.pods.AccessoryControls.Control
import com.android.bluetooth.bthelper.pods.AccessoryControls.State
import com.android.bluetooth.bthelper.utils.models.AirPodsPro2
import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreference
import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreferenceState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingPreferenceState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingState
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreference
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreferenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDeviceSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val mapper = NativeDeviceSettings(context)
    private val connected = State(
        address = "AA:BB:CC:DD:EE:FF",
        session = 1,
        connected = true,
        declared = AccessoryControls.profile(AirPodsPro2()),
        confirmed = mapOf(Control.LISTENING_MODE to 3),
    )

    @Test
    fun listeningModeUsesFilteredOrderAndDecodesPositionalSelection() {
        val modes = anc(connected)
        val labels = context.resources.getStringArray(R.array.listening_modes)
        assertEquals(listOf(labels[1], labels[2], labels[3], labels[0]), modes.toggleInfos.map { it.label })
        assertEquals(1, modes.state)
        assertEquals(4, mapper.decode(connected, toggle(NativeSettingIds.ANC, 2, modes.extras))?.value)
        assertEquals(1, mapper.decode(connected, toggle(NativeSettingIds.ANC, 3, modes.extras))?.value)

        val offDisabled = connected.copy(confirmed = connected.confirmed + (Control.OFF_LISTENING_MODE to 0))
        val filtered = anc(offDisabled)
        assertEquals(listOf(labels[1], labels[2], labels[3]), filtered.toggleInfos.map { it.label })
        assertEquals(1, filtered.state)
        assertNull(mapper.decode(offDisabled, toggle(NativeSettingIds.ANC, 0, modes.extras)))
        assertNull(mapper.decode(offDisabled, toggle(NativeSettingIds.ANC, 3, filtered.extras)))
        assertEquals(2, mapper.decode(offDisabled, toggle(NativeSettingIds.ANC, 0, filtered.extras))?.value)
    }

    @Test
    fun unknownValuesWrongTypesAndPendingChangesCannotBecomeCommands() {
        val anc = anc(connected)
        assertNull(mapper.decode(connected, toggle(NativeSettingIds.ANC, 0, Bundle.EMPTY)))
        assertNull(mapper.decode(connected, toggle(NativeSettingIds.ANC, Int.MAX_VALUE, anc.extras)))
        assertNull(mapper.decode(connected, toggle(9999, 0, anc.extras)))
        val wrong = ActionSwitchPreferenceState.Builder().setChecked(true).setExtras(anc.extras).build()
        assertNull(mapper.decode(connected, update(NativeSettingIds.ANC, wrong)))
        val invalid = connected.copy(confirmed = mapOf(Control.LISTENING_MODE to 99))
        assertFalse(mapper.settings(invalid).any { it.settingId == NativeSettingIds.ANC })
        assertFalse(mapper.settings(connected).any { it.settingId == NativeSettingIds.PERSONALIZED_VOLUME })
        assertNull(mapper.decode(invalid, toggle(NativeSettingIds.ANC, 0, anc.extras)))
        val pending = connected.copy(requested = mapOf(Control.LISTENING_MODE to AccessoryControls.Pending(1, 2)))
        assertEquals(anc.state, anc(pending).state)
        assertFalse(anc(pending).isAllowedChangingState)
        assertNull(mapper.decode(pending, toggle(NativeSettingIds.ANC, 0, anc.extras)))
    }

    @Test
    fun reconnectRestoresRowsWithoutReusingValuesOrCallbacks() {
        val layout = mapper.config()
        val stale = toggle(NativeSettingIds.ANC, 0, anc(connected).extras)
        val reconnecting = connected.copy(session = 2, connected = false, confirmed = emptyMap())
        assertEquals(listOf(NativeSettingIds.MORE_SETTINGS_FOOTER), mapper.settings(reconnecting).map { it.settingId })
        assertNull(mapper.decode(reconnecting, stale))
        val awaitingReports = reconnecting.copy(connected = true)
        assertFalse(mapper.settings(awaitingReports).any { it.settingId == NativeSettingIds.ANC })
        val reconnected = awaitingReports.copy(confirmed = connected.confirmed)
        assertNull(mapper.decode(reconnected, stale))
        assertNull(NativeDeviceSettings(context).decode(connected, stale))
        assertEquals(2L, mapper.decode(reconnected, toggle(NativeSettingIds.ANC, 0, anc(reconnected).extras))?.session)
        assertEquals(layout.mainContentItems, mapper.config().mainContentItems)
        assertEquals(layout.moreSettingsItems, mapper.config().moreSettingsItems)
    }

    @Test
    fun nativeLayoutUsesUniqueBoundedIdsAndKeepsPlatformRows() {
        val config = mapper.config()
        val items = config.mainContentItems + config.moreSettingsItems
        val provided = items.filter { it.className != null }
        assertEquals(items.size, items.map { it.settingId }.toSet().size)
        assertEquals(1001, provided.single { it.settingId == DeviceSettingId.DEVICE_SETTING_ID_ANC }.settingId)
        assertTrue(provided.filter { it.settingId != 1001 }.all { it.settingId in 1100..1299 })
        assertTrue(provided.all {
            it.className == BtHelperDeviceSettingsProviderService::class.java.name &&
                it.intentAction == DEVICE_SETTINGS_PROVIDER_ACTION && !it.isOptional
        })
        assertEquals("bluetooth_profiles", items.single { it.settingId == DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_PROFILES }.preferenceKey)
        assertEquals("spatial_audio_group", items.single { it.settingId == DeviceSettingId.DEVICE_SETTING_ID_SPATIAL_AUDIO_MULTI_TOGGLE }.preferenceKey)
        assertTrue(config.mainContentItems.any { it.settingId == NativeSettingIds.MORE_SETTINGS })
    }

    @Test
    fun advancedRowsDecodeOnlyReportedSupportedState() {
        val state = connected.copy(confirmed = connected.confirmed + mapOf(
            Control.LISTENING_MODE_CYCLE to 2,
            Control.PRESS_SPEED to 1,
            Control.ADAPTIVE_NOISE_LEVEL to 37,
        ))
        val settings = mapper.settings(state).associate { it.settingId to it.preference }
        val cycleAnc = settings[NativeSettingIds.CYCLE_NOISE_CANCELLATION] as ActionSwitchPreference
        val cycleTransparency = settings[NativeSettingIds.CYCLE_TRANSPARENCY] as ActionSwitchPreference
        assertFalse(cycleAnc.isAllowedChangingState)
        assertNull(mapper.decode(state, switch(NativeSettingIds.CYCLE_NOISE_CANCELLATION, false, cycleAnc.extras)))
        assertEquals(6, mapper.decode(state, switch(NativeSettingIds.CYCLE_TRANSPARENCY, true, cycleTransparency.extras))?.value)

        val speed = settings[NativeSettingIds.PRESS_SPEED] as MultiTogglePreference
        assertEquals(1, speed.state)
        assertEquals(2, mapper.decode(state, toggle(NativeSettingIds.PRESS_SPEED, 2, speed.extras))?.value)
        val noise = settings[NativeSettingIds.ADAPTIVE_NOISE_LEVEL] as MultiTogglePreference
        assertEquals(3, noise.state)
        assertEquals(context.getString(R.string.native_percent, 37), noise.toggleInfos[3].label)
        assertNull(mapper.decode(state, toggle(NativeSettingIds.ADAPTIVE_NOISE_LEVEL, 3, noise.extras)))
        assertEquals(50, mapper.decode(state, toggle(NativeSettingIds.ADAPTIVE_NOISE_LEVEL, 1, noise.extras))?.value)
    }

    private fun anc(state: State) = mapper.settings(state)
        .single { it.settingId == NativeSettingIds.ANC }.preference as MultiTogglePreference

    private fun toggle(id: Int, index: Int, extras: Bundle) = update(id,
        MultiTogglePreferenceState.Builder().setState(index).setExtras(extras).build())

    private fun switch(id: Int, checked: Boolean, extras: Bundle) = update(id,
        ActionSwitchPreferenceState.Builder().setChecked(checked).setExtras(extras).build())

    private fun update(id: Int, state: DeviceSettingPreferenceState) =
        DeviceSettingState.Builder().setSettingId(id).setPreferenceState(state).build()
}
