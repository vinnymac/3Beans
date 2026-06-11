/*
    Copyright 2023-2026 Hydr8gon

    This file is part of 3Beans.

    3Beans is free software: you can redistribute it and/or modify it
    under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    3Beans is distributed in the hope that it will be useful, but
    WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
    General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with 3Beans. If not, see <https://www.gnu.org/licenses/>.
*/

package com.hydra.threebeans

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.hydra.threebeans.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(context)

            // Core settings stored in 3beans.ini through the JNI bridge
            val coreCategory = PreferenceCategory(context).apply {
                title = getString(R.string.pref_category_core)
            }
            screen.addPreference(coreCategory)

            coreCategory.addPreference(coreSwitch("fpsLimiter", R.string.pref_fps_limiter, R.string.pref_fps_limiter_desc))
            coreCategory.addPreference(coreSwitch("cartAutoBoot", R.string.pref_cart_auto_boot, R.string.pref_cart_auto_boot_desc))
            coreCategory.addPreference(coreList(
                "dspBackend", R.string.pref_dsp_backend, R.string.pref_restart_required,
                arrayOf(getString(R.string.dsp_interpreter), getString(R.string.dsp_hle))
            ))
            coreCategory.addPreference(coreSwitch("threadedGpu", R.string.pref_threaded_gpu, R.string.pref_threaded_gpu_desc))
            coreCategory.addPreference(coreList(
                "unitType", R.string.pref_unit_type, R.string.pref_restart_required,
                arrayOf(getString(R.string.unit_retail), getString(R.string.unit_dev))
            ))

            // Frontend preferences stored in default SharedPreferences
            val appCategory = PreferenceCategory(context).apply {
                title = getString(R.string.pref_category_app)
            }
            screen.addPreference(appCategory)

            appCategory.addPreference(SwitchPreferenceCompat(context).apply {
                key = "showOverlay"
                setTitle(R.string.pref_show_overlay)
                setDefaultValue(true)
                isIconSpaceReserved = false
            })
            appCategory.addPreference(SeekBarPreference(context).apply {
                key = "overlayScale"
                setTitle(R.string.pref_overlay_scale)
                min = 50
                max = 150
                setDefaultValue(100)
                showSeekBarValue = true
                isIconSpaceReserved = false
            })
            appCategory.addPreference(SeekBarPreference(context).apply {
                key = "overlayOpacity"
                setTitle(R.string.pref_overlay_opacity)
                min = 10
                max = 100
                setDefaultValue(50)
                showSeekBarValue = true
                isIconSpaceReserved = false
            })
            appCategory.addPreference(SwitchPreferenceCompat(context).apply {
                key = "haptics"
                setTitle(R.string.pref_haptics)
                setDefaultValue(true)
                isIconSpaceReserved = false
            })
            appCategory.addPreference(SwitchPreferenceCompat(context).apply {
                key = "showFps"
                setTitle(R.string.pref_show_fps)
                setDefaultValue(false)
                isIconSpaceReserved = false
            })

            preferenceScreen = screen
        }

        private fun coreSwitch(name: String, title: Int, summary: Int): SwitchPreferenceCompat =
            SwitchPreferenceCompat(requireContext()).apply {
                key = name
                setTitle(title)
                setSummary(summary)
                isPersistent = false
                isChecked = NativeLibrary.getSettingInt(name) != 0
                isIconSpaceReserved = false
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                    NativeLibrary.setSettingInt(name, if (value as Boolean) 1 else 0)
                    NativeLibrary.saveSettings()
                    true
                }
            }

        private fun coreList(name: String, title: Int, summary: Int, options: Array<String>): ListPreference =
            ListPreference(requireContext()).apply {
                key = name
                setTitle(title)
                entries = options
                entryValues = options.indices.map { it.toString() }.toTypedArray()
                isPersistent = false
                value = NativeLibrary.getSettingInt(name).toString()
                summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                    "${pref.entry} — ${getString(summary)}"
                }
                isIconSpaceReserved = false
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                    NativeLibrary.setSettingInt(name, (value as String).toInt())
                    NativeLibrary.saveSettings()
                    true
                }
            }
    }
}
