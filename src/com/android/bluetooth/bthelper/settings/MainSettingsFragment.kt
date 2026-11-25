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
        findPreference<ListPreference>(LISTENING_MODE)!!.setOnPreferenceChangeListener { _, value ->
            val state = AccessoryControls.state.value
            val address = requestedAddress()
            val mode = (value as? String)?.toIntOrNull()
            if (address != null && address == state.address && mode != null && state.supportsMode(mode)) {
                val context = requireContext()
                context.sendBroadcast(Intent(Constants.ACTION_SET_ANC_MODE).setPackage(context.packageName)
                    .putExtra(Constants.PARAM_MAC_ADDRESS, address).putExtra(Constants.EXTRA_MODE, mode))
            }
            false // Show the new mode only when reported by the headset.
        }
        bindAccessoryOption(Constants.KEY_PERSONALIZED_VOLUME)
        bindAccessoryOption(Constants.KEY_CONVERSATIONAL_AWARENESS)
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

    private fun bindAccessoryOption(key: String) {
        findPreference<ListPreference>(key)!!.setOnPreferenceChangeListener { _, value ->
            val state = AccessoryControls.state.value
            val address = requestedAddress()
            val enabled = (value as? String)?.toBooleanStrictOrNull()
            if (address != null && address == state.address && enabled != null && state.enables(key)) {
                val context = requireContext()
                context.sendBroadcast(
                    Intent(Constants.ACTION_SET_ACCESSORY_OPTION).setPackage(context.packageName)
                        .putExtra(Constants.PARAM_MAC_ADDRESS, address)
                        .putExtra(Constants.EXTRA_OPTION_KEY, key)
                        .putExtra(Constants.EXTRA_OPTION_ENABLED, enabled)
                )
            }
            false // Do not apply or persist the choice locally.
        }
    }

    private fun requestedAddress() = requireActivity().intent.data?.takeIf { it.isHierarchical }
        ?.getQueryParameter(Constants.PARAM_MAC_ADDRESS)

    private fun updateControls(state: AccessoryControls.State) {
        val address = requestedAddress()
        val matchingState = state.takeIf { address != null && it.address == address }
        for (i in 0 until preferenceScreen.preferenceCount) {
            val pref = preferenceScreen.getPreference(i)
            when (pref.key) {
                LISTENING_MODE -> updateListeningMode(pref as ListPreference, matchingState)
                Constants.KEY_PERSONALIZED_VOLUME -> updateAccessoryOption(
                    pref as ListPreference, matchingState, R.string.personalized_volume_summary
                )
                Constants.KEY_CONVERSATIONAL_AWARENESS -> updateAccessoryOption(
                    pref as ListPreference, matchingState, R.string.conversational_awareness_summary
                )
                else -> pref.isEnabled = matchingState?.enables(pref.key) == true
            }
        }
    }

    private fun updateListeningMode(pref: ListPreference, state: AccessoryControls.State?) {
        val labels = resources.getStringArray(R.array.listening_modes)
        val modes = if (state == null) emptyList() else (1..labels.size).filter(state::supportsMode)
        pref.entries = modes.map { labels[it - 1] }.toTypedArray()
        pref.entryValues = modes.map { it.toString() }.toTypedArray()
        pref.value = state?.mode?.takeIf { it in modes }?.toString()
        pref.summary = pref.entry ?: getString(R.string.accessory_state_unknown)
        pref.isEnabled = modes.size > 1 || (modes.isNotEmpty() && pref.value == null)
        pref.isVisible = modes.isNotEmpty()
    }

    private fun updateAccessoryOption(
        pref: ListPreference, state: AccessoryControls.State?, description: Int
    ) {
        pref.isEnabled = state?.enables(pref.key) == true
        pref.value = state?.setting(pref.key)?.toString()
        pref.summary = getString(
            R.string.accessory_option_summary,
            getString(description),
            pref.entry ?: getString(R.string.accessory_state_unknown)
        )
    }

    private companion object {
        const val LISTENING_MODE = "listening_mode"
    }
}
