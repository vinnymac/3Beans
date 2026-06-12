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
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hydra.threebeans.databinding.ActivitySystemFilesBinding
import com.hydra.threebeans.databinding.ItemSystemFileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SystemFilesActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySystemFilesBinding
    private val rows = HashMap<String, ItemSystemFileBinding>()
    private val sdEntry = SystemFiles.entries.first { it.fileName == "sd.img" }
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
        if (entry.fileName == "sd.img") updateSdCard() else updateRow(entry)
    }

    private val importFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { importToSdFolder(uris) }
            Toast.makeText(this@SystemFilesActivity,
                getString(R.string.sd_import_done, count), Toast.LENGTH_SHORT).show()
            updateSdCard()
        }
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
            "nand.bin" to R.string.nand_title
        )
        val descriptions = mapOf(
            "boot11.bin" to R.string.boot11_desc,
            "boot9.bin" to R.string.boot9_desc,
            "nand.bin" to R.string.nand_desc
        )

        // The boot ROMs and NAND are simple file pickers; the SD card has its own
        // card below (folder or image), so it's handled separately
        for (entry in SystemFiles.entries) {
            if (entry.fileName == "sd.img") continue
            val row = ItemSystemFileBinding.inflate(LayoutInflater.from(this), binding.fileList, true)
            rows[entry.key] = row
            row.fileTitle.setText(titles.getValue(entry.fileName))
            row.fileDescription.setText(descriptions.getValue(entry.fileName))
            row.createButton.visibility = View.GONE
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

        // Reflect the saved SD mode, then react to the user switching it
        binding.sdModeGroup.check(
            if (SystemFiles.sdMode(this) == SystemFiles.MODE_FOLDER) binding.sdModeFolder.id
            else binding.sdModeImage.id
        )
        binding.sdModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            SystemFiles.setSdMode(this,
                if (checkedId == binding.sdModeFolder.id) SystemFiles.MODE_FOLDER else SystemFiles.MODE_IMAGE)
            updateSdCard()
        }
        updateSdCard()
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

    // ---- SD card section (folder-backed virtual SD, or a single image file) ----

    private fun updateSdCard() {
        val primary = binding.sdActionPrimary
        val secondary = binding.sdActionSecondary
        val tertiary = binding.sdActionTertiary

        if (SystemFiles.sdMode(this) == SystemFiles.MODE_FOLDER) {
            val folder = SystemFiles.sdFolder(this)
            val pending = SystemFiles.hasPendingSdWrites(this)
            binding.sdStatus.text = getString(
                if (pending) R.string.sd_status_folder_pending else R.string.sd_status_folder, folder.path)

            primary.visibility = View.VISIBLE
            primary.setText(R.string.sd_import)
            primary.setOnClickListener { importFiles.launch(arrayOf("*/*")) }

            secondary.visibility = View.VISIBLE
            secondary.setText(R.string.sd_export)
            secondary.setOnClickListener { exportSdImage() }

            // Pending (unsynced) writes only linger after a crash; offer to flush
            // them now, or discard them on a long press
            tertiary.visibility = if (pending) View.VISIBLE else View.GONE
            tertiary.setText(R.string.sd_sync_now)
            tertiary.setOnClickListener { syncNow() }
            tertiary.setOnLongClickListener { confirmResetWrites(); true }
        }
        else {
            updateSdImageButtons(primary, secondary, tertiary)
        }
    }

    private fun updateSdImageButtons(primary: Button, secondary: Button, tertiary: Button) {
        primary.visibility = View.VISIBLE
        primary.setText(R.string.choose_file)
        primary.setOnClickListener {
            picking = sdEntry
            pickFile.launch(arrayOf("*/*"))
        }
        secondary.setText(R.string.clear_file)
        secondary.setOnClickListener {
            SystemFiles.setPickedUri(this, sdEntry, null)
            updateSdCard()
        }
        tertiary.setOnLongClickListener(null)

        when (SystemFiles.status(this, sdEntry)) {
            SystemFiles.Status.DOCUMENT -> {
                val uri = SystemFiles.pickedUri(this, sdEntry)
                val name = uri?.let { SystemFiles.displayName(this, it) } ?: "?"
                binding.sdStatus.text = getString(R.string.status_document, name)
                secondary.visibility = View.VISIBLE
                tertiary.visibility = View.GONE
            }
            SystemFiles.Status.LOCAL -> {
                binding.sdStatus.setText(R.string.status_local)
                secondary.visibility = View.GONE
                tertiary.visibility = View.VISIBLE
                tertiary.setText(R.string.delete_sd_image)
                tertiary.setOnClickListener { confirmDeleteSdImage() }
            }
            SystemFiles.Status.MISSING -> {
                binding.sdStatus.setText(R.string.status_missing)
                secondary.visibility = View.GONE
                tertiary.visibility = View.VISIBLE
                tertiary.setText(R.string.create_sd_image)
                tertiary.setOnClickListener { createSdImage() }
            }
        }
    }

    private fun importToSdFolder(uris: List<Uri>): Int {
        val folder = SystemFiles.sdFolder(this).apply { mkdirs() }
        var count = 0
        for (uri in uris) {
            val raw = SystemFiles.displayName(this, uri) ?: continue
            val name = raw.substringAfterLast('/').replace('\\', '_')
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    File(folder, name).outputStream().use { input.copyTo(it) }
                }
                count++
            } catch (e: Exception) {
                // Skip files that can't be read; report the rest
            }
        }
        return count
    }

    private fun exportSdImage() {
        val dest = File(SystemFiles.basePath(this), "sd_export.img")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                SystemFiles.exportSdImage(this@SystemFilesActivity, dest.path)
            }
            Toast.makeText(this@SystemFilesActivity,
                if (ok) getString(R.string.sd_export_done, dest.path) else getString(R.string.sd_export_failed),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun syncNow() {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { SystemFiles.commitVirtualSd(this@SystemFilesActivity) }
            Toast.makeText(this@SystemFilesActivity,
                if (res == 0 || res == 1) getString(R.string.sd_sync_done) else getString(R.string.sd_sync_failed),
                Toast.LENGTH_SHORT).show()
            updateSdCard()
        }
    }

    private fun confirmResetWrites() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sd_reset_confirm_title)
            .setMessage(R.string.sd_reset_confirm_message)
            .setPositiveButton(R.string.sd_reset_writes) { _, _ ->
                SystemFiles.resetVirtualSd(this)
                updateSdCard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createSdImage() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { SystemFiles.createEmptySdImage(this@SystemFilesActivity) }
            } catch (e: Exception) {
                AlertDialog.Builder(this@SystemFilesActivity)
                    .setTitle(R.string.error_sd_create_title)
                    .setMessage(R.string.error_sd_create)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            updateSdCard()
        }
    }

    private fun confirmDeleteSdImage() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_sd_confirm_title)
            .setMessage(R.string.delete_sd_confirm_message)
            .setPositiveButton(R.string.delete_sd_image) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        SystemFiles.localFile(this@SystemFilesActivity, sdEntry).delete()
                    }
                    updateSdCard()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
