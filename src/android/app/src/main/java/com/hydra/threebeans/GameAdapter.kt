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

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.hydra.threebeans.databinding.ItemGameBinding
import java.util.Locale

data class GameItem(
    val name: String,
    val uri: Uri,
    val size: Long,
    val metadata: GameInfo.Metadata = GameInfo.Metadata()
) {
    // SMDH title when available, otherwise the file name
    val displayTitle: String
        get() = metadata.title ?: name.substringBeforeLast('.')

    // Stable key for per-game settings: title ID when the header parses,
    // otherwise the file name
    val gameKey: String
        get() = metadata.titleId ?: name
}

class GameAdapter(
    private val onClick: (GameItem) -> Unit,
    private val onLongClick: (GameItem) -> Unit = {}
) : RecyclerView.Adapter<GameAdapter.Holder>() {

    private var games: List<GameItem> = emptyList()

    fun submit(list: List<GameItem>) {
        games = list
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemGameBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemGameBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = games.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val game = games[position]
        holder.binding.gameTitle.text = game.displayTitle
        val icon = game.metadata.icon
        holder.binding.gameIcon.isVisible = icon != null
        holder.binding.gameLetter.isVisible = icon == null
        if (icon != null) {
            holder.binding.gameIcon.setImageBitmap(icon)
        } else {
            holder.binding.gameLetter.text =
                game.displayTitle.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        }
        val size = formatSize(game.size)
        holder.binding.gameSize.text = game.metadata.publisher
            ?.let { "$it · $size" } ?: size
        holder.binding.root.setOnClickListener { onClick(game) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(game)
            true
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}
