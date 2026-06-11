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
import androidx.recyclerview.widget.RecyclerView
import com.hydra.threebeans.databinding.ItemGameBinding
import java.util.Locale

data class GameItem(val name: String, val uri: Uri, val size: Long)

class GameAdapter(private val onClick: (GameItem) -> Unit) :
    RecyclerView.Adapter<GameAdapter.Holder>() {

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
        val title = game.name.substringBeforeLast('.')
        holder.binding.gameTitle.text = title
        holder.binding.gameLetter.text =
            title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        holder.binding.gameSize.text = formatSize(game.size)
        holder.binding.root.setOnClickListener { onClick(game) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}
