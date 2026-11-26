/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.settings

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import com.android.bluetooth.bthelper.Constants
import com.android.bluetooth.bthelper.R
import com.android.bluetooth.bthelper.pods.AccessoryControls
import com.android.bluetooth.bthelper.pods.AccessoryControls.Control
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainSettingsFragment : SettingsBasePreferenceFragment() {
    private var observation: Job? = null
    private var renderedSession: AccessoryControls.State? = null
    private lateinit var modePreference: ListPreference
    private lateinit var switches: Map<Control, Pair<SwitchPreferenceCompat, CharSequence?>>

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        setPreferencesFromResource(R.xml.main_settings, rootKey)

        modePreference = findPreference<ListPreference>("listening_mode")!!.apply {
            setOnPreferenceChangeListener { _, value ->
                (value as? String)?.toIntOrNull()?.let { request(Control.LISTENING_MODE, it) }
                false
            }
        }
        switches = switchKeys.mapValues { (control, key) ->
            val pref = findPreference<SwitchPreferenceCompat>(key)!!
            pref.setOnPreferenceChangeListener { _, value ->
                if (value is Boolean) request(control, if (value) 1 else 0)
                false
            }
            pref to pref.summary
        }
        updateControls(AccessoryControls.state.value)
    }

    override fun onStart() {
        super.onStart()
        observation = viewLifecycleOwner.lifecycleScope.launch {
            AccessoryControls.state.collect(::updateControls)
        }
    }

    override fun onStop() {
        observation?.cancel()
        observation = null
        renderedSession = null
        super.onStop()
    }

    private fun request(control: Control, value: Int) {
        val snapshot = renderedSession ?: return
        val address = snapshot.address ?: return
        AccessoryControls.request(address, snapshot.session, control, value)
    }

    private fun updateControls(state: AccessoryControls.State) {
        val address = requireActivity().intent.data?.takeIf { it.isHierarchical }
            ?.getQueryParameter(Constants.PARAM_MAC_ADDRESS)
        val matching = state.takeIf { address != null && it.address == address && it.connected }
        renderedSession = matching

        val labels = resources.getStringArray(R.array.listening_modes)
        val modes = if (matching == null) emptyList() else (1..labels.size).filter(matching::supportsMode)
        modePreference.entries = modes.map { labels[it - 1] }.toTypedArray()
        modePreference.entryValues = modes.map(Int::toString).toTypedArray()
        modePreference.value = matching?.value(Control.LISTENING_MODE)?.takeIf { it in modes }?.toString()
        modePreference.summary = when {
            matching?.pending(Control.LISTENING_MODE) != null -> getString(R.string.accessory_state_pending)
            else -> modePreference.entry ?: getString(R.string.accessory_state_unknown)
        }
        modePreference.isEnabled = matching?.pending(Control.LISTENING_MODE) == null &&
            (modes.size > 1 || (modes.isNotEmpty() && modePreference.value == null))
        modePreference.isVisible = modes.isNotEmpty()

        for ((control, pair) in switches) {
            val (pref, description) = pair
            val supported = matching?.supports(control) == true
            val reported = matching?.value(control)
            val pending = matching?.pending(control) != null
            pref.isVisible = supported
            pref.isEnabled = supported && !pending
            pref.isChecked = reported == 1
            pref.summary = when {
                pending -> getString(R.string.accessory_state_pending)
                reported == null && !description.isNullOrEmpty() -> getString(R.string.accessory_option_unknown, description)
                reported == null -> getString(R.string.accessory_state_unknown)
                else -> description
            }
        }
    }

    private companion object {
        val switchKeys = mapOf(
            Control.AUTOMATIC_EAR_DETECTION to Constants.KEY_AUTOMATIC_EAR_DETECTION,
            Control.PERSONALIZED_VOLUME to Constants.KEY_PERSONALIZED_VOLUME,
            Control.CONVERSATION_AWARENESS to Constants.KEY_CONVERSATIONAL_AWARENESS,
            Control.PAUSE_MUSIC_WHEN_SPEAKING to Constants.KEY_CONVERSATIONAL_AWARENESS_PAUSE_MUSIC,
            Control.RELATIVE_CONVERSATION_VOLUME to Constants.KEY_RELATIVE_CONVERSATIONAL_AWARENESS_VOLUME,
            Control.HEAD_GESTURES to Constants.KEY_HEAD_GESTURES,
            Control.DISCONNECT_WHEN_NOT_WEARING to Constants.KEY_DISCONNECT_WHEN_NOT_WEARING,
            Control.ALTERNATE_HEAD_TRACKING_PACKETS to Constants.KEY_USE_ALTERNATE_HEAD_TRACKING_PACKETS,
            Control.LOW_LATENCY_AUDIO to Constants.KEY_LOW_LATENCY_AUDIO,
        )
    }
}
