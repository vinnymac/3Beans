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
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hydra.threebeans.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = GameAdapter(
        onClick = { game -> launchGame(game) },
        onLongClick = { game -> showGameMenu(game) }
    )

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        prefs().edit().putString(KEY_GAMES_FOLDER, uri.toString()).apply()
        scanGames()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val span = resources.configuration.screenWidthDp / 180
        binding.gameList.layoutManager = GridLayoutManager(this, span.coerceAtLeast(2))
        binding.gameList.adapter = adapter

        binding.chooseFolderButton.setOnClickListener { pickFolder.launch(null) }
        binding.systemFilesCard.setOnClickListener {
            startActivity(Intent(this, SystemFilesActivity::class.java))
        }
        binding.homeMenuFab.setOnClickListener { launchGame(null) }
    }

    private fun showGameMenu(game: GameItem) {
        val items = arrayOf(
            getString(R.string.game_menu_play),
            getString(R.string.game_menu_settings)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(game.displayTitle)
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> launchGame(game)
                    1 -> {
                        val intent = Intent(this, SettingsActivity::class.java)
                        intent.putExtra(SettingsActivity.EXTRA_GAME_KEY, game.gameKey)
                        intent.putExtra(SettingsActivity.EXTRA_GAME_NAME, game.displayTitle)
                        startActivity(intent)
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateSystemFilesCard()
        scanGames()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_choose_folder -> {
            pickFolder.launch(null)
            true
        }
        R.id.action_system_files -> {
            startActivity(Intent(this, SystemFilesActivity::class.java))
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun prefs() = getSharedPreferences("main", Context.MODE_PRIVATE)

    private fun updateSystemFilesCard() {
        // Boot ROMs are mandatory and a NAND dump is needed to reach the home
        // menu, so surface a warning until all three are configured
        val missing = SystemFiles.entries.filter {
            it.fileName != "sd.img" && SystemFiles.status(this, it) == SystemFiles.Status.MISSING
        }
        binding.systemFilesCard.isVisible = missing.isNotEmpty()
    }

    private fun scanGames() {
        val folder = prefs().getString(KEY_GAMES_FOLDER, null)
        lifecycleScope.launch {
            val games = withContext(Dispatchers.IO) {
                if (folder == null) emptyList()
                else scanFolder(DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(folder)))
            }
            adapter.submit(games)
            binding.emptyView.isVisible = games.isEmpty()
            binding.gameList.isVisible = games.isNotEmpty()
            binding.emptyText.setText(
                if (folder == null) R.string.empty_no_folder else R.string.empty_no_games
            )
        }
    }

    private fun scanFolder(root: DocumentFile?, depth: Int = 2): List<GameItem> {
        if (root == null || !root.isDirectory) return emptyList()
        val games = ArrayList<GameItem>()
        for (file in root.listFiles()) {
            if (file.isDirectory && depth > 0) {
                games.addAll(scanFolder(file, depth - 1))
            } else if (file.isFile) {
                val name = file.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext == "3ds" || ext == "cci")
                    games.add(GameItem(name, file.uri, file.length(), GameInfo.parse(this, file.uri)))
            }
        }
        return games.sortedBy { it.displayTitle.lowercase() }
    }

    private fun launchGame(game: GameItem?) {
        val intent = Intent(this, EmulationActivity::class.java)
        if (game != null) {
            intent.putExtra(EmulationActivity.EXTRA_CART_URI, game.uri.toString())
            intent.putExtra(EmulationActivity.EXTRA_GAME_KEY, game.gameKey)
            intent.putExtra(EmulationActivity.EXTRA_GAME_NAME, game.displayTitle)
        }
        startActivity(intent)
    }

    companion object {
        private const val KEY_GAMES_FOLDER = "gamesFolderUri"
    }
}
