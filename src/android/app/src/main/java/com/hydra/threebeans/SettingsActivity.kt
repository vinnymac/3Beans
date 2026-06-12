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

import android.content.Intent
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

        // A game key switches the screen to per-game override mode
        val gameName = intent.getStringExtra(EXTRA_GAME_NAME)
        if (gameName != null)
            supportActionBar?.title = getString(R.string.per_game_title, gameName)

        if (savedInstanceState == null) {
            val fragment = SettingsFragment().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_GAME_KEY, intent.getStringExtra(EXTRA_GAME_KEY))
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, fragment)
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val gameKey: String?
            get() = arguments?.getString(EXTRA_GAME_KEY)

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(context)

            // Core settings stored in 3beans.ini through the JNI bridge,
            // or as per-game overrides when editing a single game
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

            // In per-game mode the core settings above act as overrides for
            // one game; every other category still edits the global values
            val game = gameKey
            if (game != null) {
                coreCategory.addPreference(Preference(context).apply {
                    setTitle(R.string.per_game_clear)
                    setSummary(R.string.per_game_clear_desc)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        PerGameSettings.clear(context, game)
                        // Rebuild so every row shows the global value again
                        onCreatePreferences(null, null)
                        true
                    }
                })
            }

            // Screen layout preferences used by the GL renderer
            val layoutCategory = PreferenceCategory(context).apply {
                title = getString(R.string.pref_category_layout)
            }
            screen.addPreference(layoutCategory)

            layoutCategory.addPreference(listPref(
                ScreenLayout.PREF_MODE, R.string.pref_layout_mode,
                ScreenLayout.LayoutMode.SIDE_SCREEN.ordinal,
                arrayOf(
                    getString(R.string.layout_original),
                    getString(R.string.layout_single_screen),
                    getString(R.string.layout_large_screen),
                    getString(R.string.layout_side_by_side),
                    getString(R.string.layout_hybrid),
                    getString(R.string.layout_custom)
                )
            ))
            layoutCategory.addPreference(listPref(
                ScreenLayout.PREF_SMALL_POSITION, R.string.pref_small_position,
                ScreenLayout.SmallScreenPosition.BOTTOM_RIGHT.ordinal,
                arrayOf(
                    getString(R.string.position_top_right),
                    getString(R.string.position_middle_right),
                    getString(R.string.position_bottom_right),
                    getString(R.string.position_top_left),
                    getString(R.string.position_middle_left),
                    getString(R.string.position_bottom_left),
                    getString(R.string.position_above),
                    getString(R.string.position_below)
                )
            ))
            layoutCategory.addPreference(listPref(
                ScreenLayout.PREF_PORTRAIT, R.string.pref_portrait_layout,
                ScreenLayout.PortraitLayout.TOP_FULL_WIDTH.ordinal,
                arrayOf(
                    getString(R.string.portrait_top_full),
                    getString(R.string.layout_original),
                    getString(R.string.layout_custom)
                )
            ))
            layoutCategory.addPreference(SwitchPreferenceCompat(context).apply {
                key = ScreenLayout.PREF_SWAP
                setTitle(R.string.pref_swap_screens)
                setSummary(R.string.pref_swap_screens_desc)
                setDefaultValue(false)
                isIconSpaceReserved = false
            })
            layoutCategory.addPreference(listPref(
                ScreenLayout.PREF_SECONDARY, R.string.pref_secondary_display,
                ScreenLayout.SecondaryDisplayLayout.NONE.ordinal,
                arrayOf(
                    getString(R.string.secondary_none),
                    getString(R.string.secondary_top),
                    getString(R.string.secondary_bottom),
                    getString(R.string.layout_side_by_side)
                ),
                R.string.pref_secondary_display_desc
            ))

            // Physical controller input
            val inputCategory = PreferenceCategory(context).apply {
                title = getString(R.string.pref_category_input)
            }
            screen.addPreference(inputCategory)

            inputCategory.addPreference(Preference(context).apply {
                setTitle(R.string.pref_controller_mapping)
                setSummary(R.string.pref_controller_mapping_desc)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    startActivity(Intent(context, ControllerMappingActivity::class.java))
                    true
                }
            })

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

        // A persistent ListPreference for frontend settings, stored by
        // enum ordinal in string form
        private fun listPref(
            name: String, title: Int, default: Int, options: Array<String>, summary: Int? = null
        ): ListPreference = ListPreference(requireContext()).apply {
            key = name
            setTitle(title)
            entries = options
            entryValues = options.indices.map { it.toString() }.toTypedArray()
            setDefaultValue(default.toString())
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                if (summary != null) "${pref.entry} — ${getString(summary)}" else "${pref.entry}"
            }
            isIconSpaceReserved = false
        }

        // The current value shown for a core setting: the per-game override
        // when editing a game, otherwise the global value
        private fun coreValue(name: String): Int {
            val game = gameKey ?: return NativeLibrary.getSettingInt(name)
            return PerGameSettings.get(requireContext(), game, name)
                ?: NativeLibrary.getSettingInt(name)
        }

        private fun storeCoreValue(name: String, value: Int) {
            val game = gameKey
            if (game != null) {
                PerGameSettings.set(requireContext(), game, name, value)
            } else {
                NativeLibrary.setSettingInt(name, value)
                NativeLibrary.saveSettings()
            }
        }

        private fun coreSwitch(name: String, title: Int, summary: Int): SwitchPreferenceCompat =
            SwitchPreferenceCompat(requireContext()).apply {
                key = name
                setTitle(title)
                setSummary(summary)
                isPersistent = false
                isChecked = coreValue(name) != 0
                isIconSpaceReserved = false
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                    storeCoreValue(name, if (value as Boolean) 1 else 0)
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
                value = coreValue(name).toString()
                summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                    "${pref.entry} — ${getString(summary)}"
                }
                isIconSpaceReserved = false
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                    storeCoreValue(name, (value as String).toInt())
                    true
                }
            }
    }

    companion object {
        const val EXTRA_GAME_KEY = "gameKey"
        const val EXTRA_GAME_NAME = "gameName"
    }
}
