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
import android.graphics.Bitmap
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset

// Reads title, publisher, and icon from a cart ROM's embedded SMDH data
// (NCSD -> NCCH partition 0 -> ExeFS -> "icon" file). Works on decrypted
// dumps; encrypted ROMs still yield their title ID from the plain header.
object GameInfo {
    data class Metadata(
        val title: String? = null,
        val publisher: String? = null,
        val icon: Bitmap? = null,
        val titleId: String? = null
    )

    private const val MEDIA_UNIT = 0x200L

    fun parse(context: Context, uri: Uri): Metadata {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { parse(it) }
            } ?: Metadata()
        } catch (e: Exception) {
            // Unreadable or malformed ROMs fall back to file-name display
            Metadata()
        }
    }

    private fun parse(channel: FileChannel): Metadata {
        // NCSD header: magic at 0x100, media ID at 0x108, partition table
        // of offset/size pairs in media units at 0x120
        val ncsd = read(channel, 0, 0x200) ?: return Metadata()
        if (!magic(ncsd, 0x100, "NCSD")) return Metadata()
        val titleId = String.format("%016X", ncsd.getLong(0x108))
        val partitionOffset = (ncsd.getInt(0x120).toLong() and 0xFFFFFFFFL) * MEDIA_UNIT
        val partitionSize = (ncsd.getInt(0x124).toLong() and 0xFFFFFFFFL) * MEDIA_UNIT
        if (partitionOffset == 0L || partitionSize == 0L) return Metadata(titleId = titleId)

        // NCCH header of the game partition; SMDH lives in its ExeFS, which
        // is unreadable here unless the contents are decrypted (NoCrypto)
        val ncch = read(channel, partitionOffset, 0x200) ?: return Metadata(titleId = titleId)
        if (!magic(ncch, 0x100, "NCCH")) return Metadata(titleId = titleId)
        val noCrypto = (ncch.get(0x188 + 7).toInt() and 0x04) != 0
        if (!noCrypto) return Metadata(titleId = titleId)
        val exefsOffset = (ncch.getInt(0x1A0).toLong() and 0xFFFFFFFFL) * MEDIA_UNIT
        if (exefsOffset == 0L) return Metadata(titleId = titleId)
        val exefsStart = partitionOffset + exefsOffset

        // ExeFS header: ten 16-byte entries of name, offset, size, with
        // data offsets relative to the end of the header
        val exefs = read(channel, exefsStart, 0x200) ?: return Metadata(titleId = titleId)
        for (entry in 0 until 10) {
            val base = entry * 16
            val nameBytes = ByteArray(8)
            exefs.position(base)
            exefs.get(nameBytes)
            val name = String(nameBytes, Charset.forName("US-ASCII")).trimEnd('\u0000')
            if (name != "icon") continue
            val offset = (exefs.getInt(base + 8).toLong() and 0xFFFFFFFFL)
            val size = (exefs.getInt(base + 12).toLong() and 0xFFFFFFFFL)
            if (size < 0x36C0) break
            val smdh = read(channel, exefsStart + 0x200 + offset, 0x36C0) ?: break
            return parseSmdh(smdh, titleId)
        }
        return Metadata(titleId = titleId)
    }

    private fun parseSmdh(smdh: ByteBuffer, titleId: String): Metadata {
        if (!magic(smdh, 0, "SMDH")) return Metadata(titleId = titleId)

        // Sixteen 0x200-byte title entries: short title (0x80), long title
        // (0x100), publisher (0x80); prefer English, fall back to Japanese
        var title: String? = null
        var publisher: String? = null
        for (language in intArrayOf(1, 0)) {
            val base = 0x08 + language * 0x200
            val long = utf16(smdh, base + 0x80, 0x100)
            val short = utf16(smdh, base, 0x80)
            if (title.isNullOrEmpty()) title = long.ifEmpty { short }
            if (publisher.isNullOrEmpty()) publisher = utf16(smdh, base + 0x180, 0x80)
        }

        // Large 48x48 icon at 0x24C0: RGB565 in 8x8 tiles, Z-order within
        return Metadata(
            title = title?.takeIf { it.isNotEmpty() },
            publisher = publisher?.takeIf { it.isNotEmpty() },
            icon = decodeIcon(smdh, 0x24C0, 48),
            titleId = titleId
        )
    }

    private fun decodeIcon(smdh: ByteBuffer, offset: Int, size: Int): Bitmap {
        val pixels = IntArray(size * size)
        var pos = offset
        for (tileY in 0 until size step 8) {
            for (tileX in 0 until size step 8) {
                for (k in 0 until 64) {
                    // De-interleave the Z-order index into x/y within the tile
                    val x = (k and 1) or ((k and 4) shr 1) or ((k and 16) shr 2)
                    val y = ((k and 2) shr 1) or ((k and 8) shr 2) or ((k and 32) shr 3)
                    val v = smdh.getShort(pos).toInt() and 0xFFFF
                    pos += 2
                    val r = (v ushr 11) and 0x1F
                    val g = (v ushr 5) and 0x3F
                    val b = v and 0x1F
                    pixels[(tileY + y) * size + tileX + x] =
                        (0xFF shl 24) or ((r * 255 / 31) shl 16) or
                            ((g * 255 / 63) shl 8) or (b * 255 / 31)
                }
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun utf16(buffer: ByteBuffer, offset: Int, length: Int): String {
        val bytes = ByteArray(length)
        buffer.position(offset)
        buffer.get(bytes)
        val text = String(bytes, Charset.forName("UTF-16LE"))
        val end = text.indexOf('\u0000')
        return (if (end >= 0) text.substring(0, end) else text).trim()
    }

    private fun magic(buffer: ByteBuffer, offset: Int, expected: String): Boolean {
        for (i in expected.indices) {
            if (buffer.get(offset + i).toInt().toChar() != expected[i])
                return false
        }
        return true
    }

    private fun read(channel: FileChannel, position: Long, size: Int): ByteBuffer? {
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        var pos = position
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, pos)
            if (count <= 0) return null
            pos += count
        }
        buffer.rewind()
        return buffer
    }
}
