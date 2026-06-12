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

// Kotlin face of the JNI bridge in src/android/app/src/main/cpp/interface.cpp
object NativeLibrary {
    init {
        System.loadLibrary("3beans")
    }

    // 3DS key indices shared with the core (see src/core/io/input.cpp)
    const val KEY_A = 0
    const val KEY_B = 1
    const val KEY_SELECT = 2
    const val KEY_START = 3
    const val KEY_RIGHT = 4
    const val KEY_LEFT = 5
    const val KEY_UP = 6
    const val KEY_DOWN = 7
    const val KEY_R = 8
    const val KEY_L = 9
    const val KEY_X = 10
    const val KEY_Y = 11

    const val FRAME_WIDTH = 400
    const val FRAME_HEIGHT = 480
    const val STICK_RANGE = 0x7FF

    external fun loadSettings(basePath: String): Boolean
    external fun saveSettings(): Boolean
    external fun getSettingInt(name: String): Int
    external fun setSettingInt(name: String, value: Int)
    external fun getSettingString(name: String): String
    external fun setSettingString(name: String, value: String)

    // Virtual SD (folder-backed FAT32) overlay management. prepare/commit return
    // 0 = nothing to commit, 1 = committed, 2 = folder drifted, 3 = error.
    external fun prepareVirtualSd(rootPath: String, overlayPath: String): Int
    external fun commitVirtualSd(rootPath: String, overlayPath: String, allowDrift: Boolean): Int
    external fun resetVirtualSdOverlay(overlayPath: String)
    external fun exportSdImage(rootPath: String, overlayPath: String, destPath: String): Boolean

    external fun startCore(cartPath: String): Int
    external fun resumeCore()
    external fun pauseCore()
    external fun stopCore()
    external fun isRunning(): Boolean
    external fun getFps(): Int

    external fun copyFrame(out: java.nio.ByteBuffer): Boolean
    external fun readAudio(out: ShortArray, count: Int)

    external fun pressKey(key: Int)
    external fun releaseKey(key: Int)
    external fun pressHome()
    external fun releaseHome()
    external fun setLStick(x: Int, y: Int)
    external fun pressScreen(x: Int, y: Int)
    external fun releaseScreen()
}
