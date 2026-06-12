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
import android.view.KeyEvent

// Maps physical controller key codes to 3DS keys. Explicit bindings from
// the controller mapping screen override the built-in defaults, and an
// auto-map applies Nintendo- or Xbox-style face button layouts wholesale.
object KeyMap {
    private const val PREFS = "keymap"

    // The bindable 3DS inputs in display order, with label resources
    val INPUTS = listOf(
        NativeLibrary.KEY_A to R.string.input_a,
        NativeLibrary.KEY_B to R.string.input_b,
        NativeLibrary.KEY_X to R.string.input_x,
        NativeLibrary.KEY_Y to R.string.input_y,
        NativeLibrary.KEY_L to R.string.input_l,
        NativeLibrary.KEY_R to R.string.input_r,
        NativeLibrary.KEY_START to R.string.input_start,
        NativeLibrary.KEY_SELECT to R.string.input_select,
        NativeLibrary.KEY_UP to R.string.input_dpad_up,
        NativeLibrary.KEY_DOWN to R.string.input_dpad_down,
        NativeLibrary.KEY_LEFT to R.string.input_dpad_left,
        NativeLibrary.KEY_RIGHT to R.string.input_dpad_right
    )

    // Nintendo-style layout: the east face button reports KEYCODE_BUTTON_A
    private val DEFAULTS = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to NativeLibrary.KEY_A,
        KeyEvent.KEYCODE_BUTTON_B to NativeLibrary.KEY_B,
        KeyEvent.KEYCODE_BUTTON_X to NativeLibrary.KEY_X,
        KeyEvent.KEYCODE_BUTTON_Y to NativeLibrary.KEY_Y,
        KeyEvent.KEYCODE_BUTTON_L1 to NativeLibrary.KEY_L,
        KeyEvent.KEYCODE_BUTTON_R1 to NativeLibrary.KEY_R,
        KeyEvent.KEYCODE_BUTTON_START to NativeLibrary.KEY_START,
        KeyEvent.KEYCODE_BUTTON_SELECT to NativeLibrary.KEY_SELECT,
        KeyEvent.KEYCODE_DPAD_UP to NativeLibrary.KEY_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to NativeLibrary.KEY_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to NativeLibrary.KEY_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to NativeLibrary.KEY_RIGHT
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // The key code explicitly bound to a 3DS key, if any
    fun binding(context: Context, threeDsKey: Int): Int? {
        val prefs = prefs(context)
        val key = "key_$threeDsKey"
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    // Bind a key code to a 3DS key, stealing it from any other 3DS key
    fun bind(context: Context, threeDsKey: Int, keyCode: Int) {
        val editor = prefs(context).edit()
        for ((input, _) in INPUTS) {
            if (input != threeDsKey && binding(context, input) == keyCode)
                editor.remove("key_$input")
        }
        editor.putInt("key_$threeDsKey", keyCode)
        editor.apply()
    }

    fun reset(context: Context) = prefs(context).edit().clear().apply()

    // Apply a full face button layout: Nintendo keeps the defaults, Xbox
    // swaps A/B and X/Y so physical positions match the 3DS
    fun autoMap(context: Context, nintendoLayout: Boolean) {
        reset(context)
        if (nintendoLayout) return
        val editor = prefs(context).edit()
        editor.putInt("key_${NativeLibrary.KEY_A}", KeyEvent.KEYCODE_BUTTON_B)
        editor.putInt("key_${NativeLibrary.KEY_B}", KeyEvent.KEYCODE_BUTTON_A)
        editor.putInt("key_${NativeLibrary.KEY_X}", KeyEvent.KEYCODE_BUTTON_Y)
        editor.putInt("key_${NativeLibrary.KEY_Y}", KeyEvent.KEYCODE_BUTTON_X)
        editor.apply()
    }

    // Resolve the effective key code -> 3DS key map: defaults minus any
    // key codes or 3DS keys that have explicit bindings, plus the bindings
    fun load(context: Context): Map<Int, Int> {
        val explicit = HashMap<Int, Int>()
        for ((input, _) in INPUTS) {
            val code = binding(context, input) ?: continue
            explicit[code] = input
        }
        if (explicit.isEmpty()) return DEFAULTS
        val map = HashMap<Int, Int>()
        for ((code, input) in DEFAULTS) {
            if (code !in explicit && !explicit.containsValue(input))
                map[code] = input
        }
        map.putAll(explicit)
        return map
    }

    // The effective key code for a 3DS key, for display
    fun effectiveBinding(context: Context, threeDsKey: Int): Int? {
        binding(context, threeDsKey)?.let { return it }
        return load(context).entries.firstOrNull { it.value == threeDsKey }?.key
    }

    fun keyName(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
}
