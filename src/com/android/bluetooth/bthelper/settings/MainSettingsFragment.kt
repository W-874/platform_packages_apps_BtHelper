/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.settings

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.android.bluetooth.bthelper.Constants
import com.android.bluetooth.bthelper.R
import com.android.bluetooth.bthelper.isLowLatencySupported
import com.android.bluetooth.bthelper.pods.AccessoryControls
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainSettingsFragment : SettingsBasePreferenceFragment() {
    private var observation: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        setPreferencesFromResource(R.xml.main_settings, rootKey)

        if (!requireContext().isLowLatencySupported()) {
            findPreference<Preference>(Constants.KEY_LOW_LATENCY_AUDIO)?.let(preferenceScreen::removePreference)
        }
        findPreference<ListPreference>("listening_mode")!!.setOnPreferenceChangeListener { _, value ->
            val state = AccessoryControls.state.value
            val address = requestedAddress()
            val mode = (value as? String)?.toIntOrNull()
            if (address != null && address == state.address && mode != null && state.reportedMode(mode)) {
                val context = requireContext()
                context.sendBroadcast(Intent(Constants.ACTION_SET_ANC_MODE).setPackage(context.packageName)
                    .putExtra(Constants.PARAM_MAC_ADDRESS, address).putExtra(Constants.EXTRA_MODE, mode))
            }
            false // Show the new mode only when acknowledged by the headset.
        }
        updateControls(AccessoryControls.state.value)
    }

    override fun onStart() {
        super.onStart()
        observation = viewLifecycleOwner.lifecycleScope.launch {
            AccessoryControls.state.collect { updateControls(it) }
        }
    }

    override fun onStop() {
        observation?.cancel()
        observation = null
        super.onStop()
    }

    private fun requestedAddress() = requireActivity().intent.data?.takeIf { it.isHierarchical }
        ?.getQueryParameter(Constants.PARAM_MAC_ADDRESS)

    private fun updateControls(state: AccessoryControls.State) {
        val address = requestedAddress()
        for (i in 0 until preferenceScreen.preferenceCount) {
            val pref = preferenceScreen.getPreference(i)
            if (pref is ListPreference && pref.key == "listening_mode") {
                pref.isVisible = address != null && address == state.address && state.reportedMode(state.mode)
                if (pref.isVisible) {
                    val labels = resources.getStringArray(R.array.listening_modes)
                    val modes = (1..4).filter(state::reportedMode)
                    pref.entries = modes.map { labels[it - 1] }.toTypedArray()
                    pref.entryValues = modes.map { it.toString() }.toTypedArray()
                    pref.value = state.mode.toString()
                    pref.summary = labels[state.mode - 1]
                    pref.isEnabled = modes.size > 1
                }
                continue
            }
            pref.isEnabled = state.address != null && state.address == address && state.enables(pref.key)
        }
    }
}
