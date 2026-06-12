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

import java.nio.ByteBuffer
import java.nio.ByteOrder

// The core hands out each frame exactly once, so when an external display
// mirrors emulation the latest frame is kept here for every GL surface to
// upload from. Only the primary surface pulls new frames from the core.
object FrameStore {
    private val frame: ByteBuffer =
        ByteBuffer.allocateDirect(NativeLibrary.FRAME_WIDTH * NativeLibrary.FRAME_HEIGHT * 4)
            .order(ByteOrder.nativeOrder())
    private var sequence = 0L

    // Forget the previous session's last frame when a new core starts
    fun reset() {
        synchronized(this) {
            sequence = 0
        }
    }

    // Pull a new frame from the core if one is ready
    fun pull() {
        synchronized(this) {
            if (NativeLibrary.copyFrame(frame))
                sequence++
        }
    }

    // Copy the latest frame into a renderer's buffer when it's newer than
    // the given sequence number; returns the current sequence number, which
    // stays at zero until a first frame has been pulled
    fun copyInto(dest: ByteBuffer, lastSequence: Long): Long {
        synchronized(this) {
            if (sequence != lastSequence && sequence > 0) {
                frame.rewind()
                dest.rewind()
                dest.put(frame)
            }
            return sequence
        }
    }
}
