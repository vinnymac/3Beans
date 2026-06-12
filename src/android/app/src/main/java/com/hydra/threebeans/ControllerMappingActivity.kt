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
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hydra.threebeans.databinding.ActivityControllerMappingBinding

// Rebind physical controller buttons to 3DS keys: tap an input and press a
// button, or auto-map an entire Nintendo/Xbox face button layout at once
class ControllerMappingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityControllerMappingBinding

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = KeyMap.INPUTS.size
        override fun getItem(position: Int): Any = KeyMap.INPUTS[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            val (key, label) = KeyMap.INPUTS[position]
            view.findViewById<TextView>(android.R.id.text1).text = getString(label)
            val binding = KeyMap.effectiveBinding(this@ControllerMappingActivity, key)
            view.findViewById<TextView>(android.R.id.text2).text =
                binding?.let { KeyMap.keyName(it) } ?: getString(R.string.mapping_unbound)
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControllerMappingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.pref_controller_mapping)

        binding.inputList.adapter = adapter
        binding.inputList.setOnItemClickListener { _, _, position, _ ->
            captureBinding(KeyMap.INPUTS[position])
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_controller_mapping, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_auto_map -> {
            autoMap()
            true
        }
        R.id.action_reset_mapping -> {
            KeyMap.reset(this)
            adapter.notifyDataSetChanged()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // Wait for a button press and bind it to the chosen 3DS input
    private fun captureBinding(input: Pair<Int, Int>) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(input.second))
            .setMessage(R.string.mapping_press_button)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnKeyListener { d, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || !isMappable(keyCode))
                return@setOnKeyListener false
            KeyMap.bind(this, input.first, keyCode)
            adapter.notifyDataSetChanged()
            d.dismiss()
            true
        }
        dialog.show()
    }

    // Detect the controller's face button layout from a single press of
    // the right-side (east) button to detect the face button layout
    private fun autoMap() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mapping_auto_map)
            .setMessage(R.string.mapping_auto_map_prompt)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnKeyListener { d, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN)
                return@setOnKeyListener false
            when (keyCode) {
                // Nintendo layout: the east button reports BUTTON_A
                KeyEvent.KEYCODE_BUTTON_A -> KeyMap.autoMap(this, true)
                // Xbox layout: the east button reports BUTTON_B
                KeyEvent.KEYCODE_BUTTON_B -> KeyMap.autoMap(this, false)
                else -> return@setOnKeyListener true // Wait for a face button
            }
            adapter.notifyDataSetChanged()
            d.dismiss()
            true
        }
        dialog.show()
    }

    // Anything a gamepad or keyboard can report except system keys
    private fun isMappable(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_UNKNOWN -> false
        else -> true
    }
}
