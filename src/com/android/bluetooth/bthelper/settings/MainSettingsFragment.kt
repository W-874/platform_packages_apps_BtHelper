/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.settings

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.SwitchPreferenceCompat
import com.android.bluetooth.bthelper.Constants
import com.android.bluetooth.bthelper.R
import com.android.bluetooth.bthelper.compatibleModelKey
import com.android.bluetooth.bthelper.getSharedPreferences
import com.android.bluetooth.bthelper.isLowLatencySupported
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class MainSettingsFragment : SettingsBasePreferenceFragment(), OnPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        setPreferencesFromResource(R.xml.main_settings, rootKey)

        val address = requireActivity().intent.data?.takeIf { it.isHierarchical }
            ?.getQueryParameter(Constants.PARAM_MAC_ADDRESS)
        if (address != null && BluetoothAdapter.checkBluetoothAddress(address)) {
            val context = requireContext()
            val preferences = context.getSharedPreferences()
            if (!preferences.getBoolean("${address}_verified_proximity", false)) {
                for (index in 0 until preferenceScreen.preferenceCount) {
                    preferenceScreen.getPreference(index).isEnabled = false
                }
                val device = context.getSystemService(BluetoothManager::class.java)
                    ?.adapter?.getRemoteDevice(address)
                if (device?.bondState == BluetoothDevice.BOND_BONDED &&
                    device.uuids?.contains(Constants.PODS_UUID) == true
                ) {
                    val key = compatibleModelKey(address)
                    preferenceScreen.addPreference(SwitchPreferenceCompat(context).apply {
                        order = 0
                        isPersistent = false
                        setTitle(R.string.compatible_model_title)
                        setSummary(R.string.compatible_model_summary)
                        isChecked = preferences.getBoolean(key, false)
                        setOnPreferenceChangeListener { _, value ->
                            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                                false
                            } else {
                                val editor = preferences.edit()
                                if (value == true) editor.putBoolean(key, true) else editor.remove(key)
                                editor.apply()
                                true
                            }
                        }
                    })
                }
                return
            }
        }

        findPreference<SwitchPreferenceCompat>(Constants.KEY_AUTOMATIC_EAR_DETECTION)?.apply {
            isEnabled = true
            onPreferenceChangeListener = this@MainSettingsFragment
        }

        findPreference<SwitchPreferenceCompat>(Constants.KEY_CONVERSATIONAL_AWARENESS_PAUSE_MUSIC)
            ?.apply {
                isEnabled = true
                onPreferenceChangeListener = this@MainSettingsFragment
            }

        findPreference<SwitchPreferenceCompat>(
                Constants.KEY_RELATIVE_CONVERSATIONAL_AWARENESS_VOLUME
            )
            ?.apply {
                isEnabled = true
                onPreferenceChangeListener = this@MainSettingsFragment
            }

        findPreference<SwitchPreferenceCompat>(Constants.KEY_HEAD_GESTURES)?.apply {
            isEnabled = true
            onPreferenceChangeListener = this@MainSettingsFragment
        }

        findPreference<SwitchPreferenceCompat>(Constants.KEY_DISCONNECT_WHEN_NOT_WEARING)?.apply {
            isEnabled = true
            onPreferenceChangeListener = this@MainSettingsFragment
        }

        findPreference<SwitchPreferenceCompat>(Constants.KEY_LOW_LATENCY_AUDIO)?.apply {
            if (!requireContext().isLowLatencySupported()) {
                preferenceScreen.removePreference(this)
            } else {
                isEnabled = true
                onPreferenceChangeListener = this@MainSettingsFragment
            }
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        when (preference.getKey()) {
            else -> {}
        }
        return true
    }

    companion object {
        const val TAG: String = "MainSettingsFragment"
    }
}
