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

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import androidx.preference.PreferenceManager

// Routes one or both 3DS screens to an external display (AYN Odin and
// similar dual-screen handhelds, or HDMI/cast targets) via the Presentation
// API. The screens render from
// FrameStore in Kotlin so no native surface plumbing is needed.
class SecondaryDisplay(
    private val activity: Activity,
    private val onActiveChanged: () -> Unit
) : DisplayManager.DisplayListener {
    private val displayManager =
        activity.getSystemService(Activity.DISPLAY_SERVICE) as DisplayManager
    private var presentation: EmulationPresentation? = null

    // The screens currently shown on the external display, empty when none
    val activeScreens: Set<ScreenLayout.Screen>
        get() = presentation?.screens ?: emptySet()

    fun register() = displayManager.registerDisplayListener(this, null)

    fun release() {
        displayManager.unregisterDisplayListener(this)
        dismiss()
    }

    fun dismiss() {
        if (presentation == null) return
        try {
            presentation?.dismiss()
        } catch (_: Exception) {
            // The display may have gone away asynchronously
        }
        presentation = null
        onActiveChanged()
    }

    // Show, move, or hide the presentation to match the current setting and
    // the set of connected displays
    fun update() {
        if (activity.isFinishing || activity.isDestroyed) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val screens = when (ScreenLayout.secondaryLayout(prefs)) {
            ScreenLayout.SecondaryDisplayLayout.NONE -> emptySet()
            ScreenLayout.SecondaryDisplayLayout.TOP_SCREEN -> setOf(ScreenLayout.Screen.TOP)
            ScreenLayout.SecondaryDisplayLayout.BOTTOM_SCREEN -> setOf(ScreenLayout.Screen.BOTTOM)
            ScreenLayout.SecondaryDisplayLayout.SIDE_BY_SIDE ->
                setOf(ScreenLayout.Screen.TOP, ScreenLayout.Screen.BOTTOM)
        }
        val display = if (screens.isEmpty()) null else findExternalDisplay()
        if (display == null) {
            dismiss()
            return
        }
        if (presentation?.display?.displayId == display.displayId &&
            presentation?.screens == screens
        ) return

        dismiss()
        try {
            presentation = EmulationPresentation(activity, display, screens)
            presentation?.show()
        } catch (_: WindowManager.BadTokenException) {
            // The display became invalid asynchronously; a display event
            // will retrigger this logic
            presentation = null
        } catch (_: WindowManager.InvalidDisplayException) {
            presentation = null
        }
        onActiveChanged()
    }

    // Find a connected display other than the one hosting the activity,
    // preferring external panels over built-in ones (AYN Odin second screen)
    private fun findExternalDisplay(): Display? {
        @Suppress("DEPRECATION")
        val currentId = activity.windowManager.defaultDisplay.displayId
        val candidates = displayManager.displays.filter {
            it.displayId != Display.DEFAULT_DISPLAY &&
                it.displayId != currentId &&
                it.state != Display.STATE_OFF &&
                it.isValid
        }
        return candidates.firstOrNull { !it.name.contains("Built", true) }
            ?: candidates.firstOrNull()
    }

    override fun onDisplayAdded(displayId: Int) = update()
    override fun onDisplayRemoved(displayId: Int) = update()
    override fun onDisplayChanged(displayId: Int) = update()
}

// Hosts a GLSurfaceView mirroring the chosen screens on the external
// display, forwarding touch on a visible bottom screen to the core
class EmulationPresentation(
    context: Activity, display: Display, val screens: Set<ScreenLayout.Screen>
) : Presentation(context, display) {
    private lateinit var surface: GLSurfaceView
    @Volatile private var touchRect: Rect? = null
    private var touchPointer = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val config = ScreenLayout.Config(
            mode = ScreenLayout.LayoutMode.SIDE_SCREEN,
            swapScreens = prefs.getBoolean(ScreenLayout.PREF_SWAP, false),
            screens = screens
        )

        surface = GLSurfaceView(context)
        surface.setEGLContextClientVersion(2)
        surface.setRenderer(EmulationRenderer(
            pullsFrames = false,
            computeLayout = { w, h -> ScreenLayout.compute(w, h, config) },
            onLayout = { layout -> touchRect = layout.touch }
        ))

        surface.setOnTouchListener { _, event ->
            val rect = touchRect ?: return@setOnTouchListener true
            val index = event.actionIndex
            val id = event.getPointerId(index)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (touchPointer < 0 &&
                        rect.contains(event.getX(index).toInt(), event.getY(index).toInt())
                    ) {
                        touchPointer = id
                        touchScreen(rect, event.getX(index), event.getY(index))
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val moveIndex = event.findPointerIndex(touchPointer)
                    if (moveIndex >= 0)
                        touchScreen(rect, event.getX(moveIndex), event.getY(moveIndex))
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    if (id == touchPointer || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        touchPointer = -1
                        NativeLibrary.releaseScreen()
                    }
                }
            }
            true
        }

        setContentView(surface)
    }

    private fun touchScreen(rect: Rect, x: Float, y: Float) {
        NativeLibrary.pressScreen(
            ((x - rect.left) * 320 / rect.width()).toInt().coerceIn(0, 319),
            ((y - rect.top) * 240 / rect.height()).toInt().coerceIn(0, 239)
        )
    }
}
