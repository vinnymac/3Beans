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
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The core opens files with plain fopen(), so documents picked through the
// Storage Access Framework are exposed as paths by symlinking to the magic
// /proc/self/fd entry of an open descriptor. Descriptors are kept open for
// as long as the linked path may be in use.
object SystemFiles {
    enum class Status { LOCAL, DOCUMENT, MISSING }

    class Entry(
        val key: String, // SharedPreferences key for the picked document URI
        val settingName: String, // Core path setting receiving the resolved path
        val fileName: String, // Local file name in the app's external files dir
        val writable: Boolean, // Whether the core needs read-write access
        val required: Boolean // Whether the core refuses to boot without it
    )

    val entries = listOf(
        Entry("boot11Uri", "boot11Path", "boot11.bin", false, true),
        Entry("boot9Uri", "boot9Path", "boot9.bin", false, true),
        Entry("nandUri", "nandPath", "nand.bin", true, false),
        Entry("sdUri", "sdPath", "sd.img", true, false)
    )

    private const val PREFS = "system_files"
    private val descriptors = HashMap<String, ParcelFileDescriptor>()
    private var cartDescriptor: ParcelFileDescriptor? = null

    fun basePath(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    fun localFile(context: Context, entry: Entry): File = File(basePath(context), entry.fileName)

    fun pickedUri(context: Context, entry: Entry): Uri? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(entry.key, null) ?: return null
        return Uri.parse(value)
    }

    fun setPickedUri(context: Context, entry: Entry, uri: Uri?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (uri == null) remove(entry.key) else putString(entry.key, uri.toString()) }
            .apply()
    }

    fun status(context: Context, entry: Entry): Status = when {
        pickedUri(context, entry) != null -> Status.DOCUMENT
        localFile(context, entry).isFile -> Status.LOCAL
        else -> Status.MISSING
    }

    // Resolve every system file to a path the core can fopen(), preferring
    // picked documents over files placed manually in the app directory
    fun prepare(context: Context): List<Entry> {
        val missing = ArrayList<Entry>()
        for (entry in entries) {
            val uri = pickedUri(context, entry)
            var path: String? = null
            if (uri != null)
                path = linkDocument(context, entry, uri)
            if (path == null) {
                val local = localFile(context, entry)
                if (local.isFile) path = local.path
            }
            if (path == null) {
                // Fall back to the default location so the core's error
                // handling stays predictable, and report required files
                path = localFile(context, entry).path
                if (entry.required) missing.add(entry)
            }
            NativeLibrary.setSettingString(entry.settingName, path)
        }
        NativeLibrary.saveSettings()
        return missing
    }

    private fun linkDocument(context: Context, entry: Entry, uri: Uri): String? {
        val mode = if (entry.writable) "rw" else "r"
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, mode)
        } catch (e: Exception) {
            null
        } ?: return null
        descriptors.remove(entry.key)?.close()
        descriptors[entry.key] = pfd
        return makeLink(context, "sys", entry.fileName, pfd.fd)
    }

    // Open a cart ROM document and expose it as a path; the matching .sav
    // file lands beside the symlink in app storage, keeping saves writable
    fun openCart(context: Context, uri: Uri): String? {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return null
        cartDescriptor?.close()
        cartDescriptor = pfd
        val name = sanitize(displayName(context, uri) ?: "game.3ds")
        return makeLink(context, "rom", name, pfd.fd)
    }

    fun closeCart() {
        cartDescriptor?.close()
        cartDescriptor = null
    }

    // Creates a minimal empty FAT32 image at the sd.img local path.
    // Only ~600 bytes of real data are written; ftruncate(2 GB) creates a sparse
    // file on Android's internal storage so actual disk usage starts near zero.
    // Throws IOException on failure (e.g. storage full) — caller shows the error.
    fun createEmptySdImage(context: Context) {
        val sdEntry = entries.first { it.fileName == "sd.img" }
        val file = localFile(context, sdEntry)
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val totalBytes = 64L * 1024 * 1024 * 1024
                val secPerClus = 64  // 32 KB clusters — standard for FAT32 above 32 GB
                val rsvdSecCnt = 32
                val numFATs = 2
                val totalSectors = (totalBytes / 512).toInt()  // 4,194,304

                // Standard FAT spec formula for FAT size (conservative upper bound)
                val tmpVal2 = (256 * secPerClus + numFATs) / numFATs  // 1025
                val fatSz = (totalSectors - rsvdSecCnt + tmpVal2 - 1) / tmpVal2  // 4092

                // ── Boot sector (BPB) ──
                val boot = ByteArray(512)
                boot[0] = 0xEB.toByte(); boot[1] = 0x58.toByte(); boot[2] = 0x90.toByte()
                "MSWIN4.1".toByteArray().copyInto(boot, 3)
                val buf = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
                buf.position(11)
                buf.putShort(512.toShort())          // BytsPerSec
                buf.put(secPerClus.toByte())          // SecPerClus
                buf.putShort(rsvdSecCnt.toShort())   // RsvdSecCnt
                buf.put(numFATs.toByte())             // NumFATs
                buf.putShort(0); buf.putShort(0)      // RootEntCnt = 0, TotSec16 = 0
                buf.put(0xF8.toByte()); buf.putShort(0)  // Media, FATSz16 = 0
                buf.putShort(63); buf.putShort(255.toShort())  // SecPerTrk, NumHeads
                buf.putInt(0); buf.putInt(totalSectors)        // HiddSec, TotSec32
                buf.putInt(fatSz)                    // FATSz32
                buf.putShort(0); buf.putShort(0)     // ExtFlags, FSVer
                buf.putInt(2)                        // RootClus = 2
                buf.putShort(1); buf.putShort(6)     // FSInfo sector, BkBootSec
                buf.position(64)
                buf.put(0x80.toByte()); buf.put(0); buf.put(0x29.toByte()); buf.putInt(0)
                "NO NAME    ".toByteArray().copyInto(boot, 71)
                "FAT32   ".toByteArray().copyInto(boot, 82)
                boot[510] = 0x55; boot[511] = 0xAA.toByte()
                raf.write(boot)

                // ── FSInfo sector (sector 1) ──
                val fsinfo = ByteArray(512)
                fsinfo[0] = 0x52; fsinfo[1] = 0x52; fsinfo[2] = 0x61; fsinfo[3] = 0x41   // LeadSig
                fsinfo[484] = 0x72; fsinfo[485] = 0x72; fsinfo[486] = 0x41; fsinfo[487] = 0x61  // StrucSig
                for (i in 488..495) fsinfo[i] = 0xFF.toByte()  // FreeCount + NxtFree = unknown
                fsinfo[508] = 0x00; fsinfo[509] = 0x00; fsinfo[510] = 0x55; fsinfo[511] = 0xAA.toByte()  // TrailSig
                raf.seek(512); raf.write(fsinfo)

                // ── Backup boot sector (sector 6) ──
                raf.seek(6L * 512); raf.write(boot)

                // ── FAT opening entries: media (0x0FFFFFF8), EOC×2 (0x0FFFFFFF) ──
                val fatHead = byteArrayOf(
                    0xF8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F,
                    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F,
                    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F
                )
                raf.seek(rsvdSecCnt.toLong() * 512); raf.write(fatHead)               // FAT1
                raf.seek((rsvdSecCnt + fatSz).toLong() * 512); raf.write(fatHead)     // FAT2

                raf.setLength(totalBytes)
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")

    private fun makeLink(context: Context, dir: String, name: String, fd: Int): String {
        val link = File(File(context.filesDir, dir), name)
        link.parentFile?.mkdirs()
        link.delete()
        Os.symlink("/proc/self/fd/$fd", link.path)
        return link.path
    }
}
