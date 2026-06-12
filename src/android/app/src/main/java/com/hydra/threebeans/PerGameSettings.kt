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

import android.content.Context
import android.content.SharedPreferences

// Per-game overrides of the numeric core settings, keyed by title ID (or
// file name when a ROM's header can't be parsed). Overrides are applied to
// the core just before a game starts and never written to 3beans.ini; the
// global settings are reloaded when emulation ends.
object PerGameSettings {
    private const val PREFS = "per_game"

    // The numeric core settings that may be overridden per game
    val CORE_SETTINGS = listOf("fpsLimiter", "cartAutoBoot", "dspBackend", "threadedGpu", "unitType")

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(gameKey: String, setting: String): String = "$gameKey:$setting"

    fun get(context: Context, gameKey: String, setting: String): Int? {
        val prefs = prefs(context)
        val key = key(gameKey, setting)
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    fun set(context: Context, gameKey: String, setting: String, value: Int?) {
        prefs(context).edit().apply {
            if (value == null) remove(key(gameKey, setting))
            else putInt(key(gameKey, setting), value)
        }.apply()
    }

    fun clear(context: Context, gameKey: String) {
        prefs(context).edit().apply {
            for (setting in CORE_SETTINGS)
                remove(key(gameKey, setting))
        }.apply()
    }

    fun hasOverrides(context: Context, gameKey: String): Boolean =
        CORE_SETTINGS.any { get(context, gameKey, it) != null }

    // Apply this game's overrides on top of the loaded global settings,
    // without persisting them
    fun apply(context: Context, gameKey: String) {
        for (setting in CORE_SETTINGS) {
            val value = get(context, gameKey, setting) ?: continue
            NativeLibrary.setSettingInt(setting, value)
        }
    }
}
