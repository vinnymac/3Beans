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
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hydra.threebeans.databinding.ActivitySystemFilesBinding
import com.hydra.threebeans.databinding.ItemSystemFileBinding

class SystemFilesActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySystemFilesBinding
    private val rows = HashMap<String, ItemSystemFileBinding>()
    private var picking: SystemFiles.Entry? = null

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val entry = picking ?: return@registerForActivityResult
        picking = null
        if (uri == null) return@registerForActivityResult
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (entry.writable) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
        SystemFiles.setPickedUri(this, entry, uri)
        updateRow(entry)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySystemFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.helpText.text = getString(R.string.system_files_help, SystemFiles.basePath(this).path)

        val titles = mapOf(
            "boot11.bin" to R.string.boot11_title,
            "boot9.bin" to R.string.boot9_title,
            "nand.bin" to R.string.nand_title,
            "sd.img" to R.string.sd_title
        )
        val descriptions = mapOf(
            "boot11.bin" to R.string.boot11_desc,
            "boot9.bin" to R.string.boot9_desc,
            "nand.bin" to R.string.nand_desc,
            "sd.img" to R.string.sd_desc
        )

        for (entry in SystemFiles.entries) {
            val row = ItemSystemFileBinding.inflate(LayoutInflater.from(this), binding.fileList, true)
            rows[entry.key] = row
            row.fileTitle.setText(titles.getValue(entry.fileName))
            row.fileDescription.setText(descriptions.getValue(entry.fileName))
            row.chooseButton.setOnClickListener {
                picking = entry
                pickFile.launch(arrayOf("*/*"))
            }
            row.clearButton.setOnClickListener {
                SystemFiles.setPickedUri(this, entry, null)
                updateRow(entry)
            }
            updateRow(entry)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun updateRow(entry: SystemFiles.Entry) {
        val row = rows.getValue(entry.key)
        when (SystemFiles.status(this, entry)) {
            SystemFiles.Status.DOCUMENT -> {
                val uri = SystemFiles.pickedUri(this, entry)
                val name = uri?.let { SystemFiles.displayName(this, it) } ?: "?"
                row.fileStatus.text = getString(R.string.status_document, name)
                row.clearButton.isEnabled = true
            }
            SystemFiles.Status.LOCAL -> {
                row.fileStatus.setText(R.string.status_local)
                row.clearButton.isEnabled = false
            }
            SystemFiles.Status.MISSING -> {
                row.fileStatus.setText(
                    if (entry.required) R.string.status_missing_required else R.string.status_missing
                )
                row.clearButton.isEnabled = false
            }
        }
    }
}
