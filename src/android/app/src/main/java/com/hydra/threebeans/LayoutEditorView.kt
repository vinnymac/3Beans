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
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

// Drag-to-position editor for the custom screen layout: drag a screen to
// move it, drag its corner handle to resize at a fixed aspect ratio. Edits
// apply to the orientation the view is currently in.
class LayoutEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val rects = LinkedHashMap<ScreenLayout.Screen, RectF>()
    private var prefs: SharedPreferences? = null
    private var dragScreen: ScreenLayout.Screen? = null
    private var resizing = false
    private var grabX = 0f
    private var grabY = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(48, 255, 255, 255)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        textSize = 14 * density
    }

    private fun prefKey(): String =
        if (height > width) ScreenLayout.PREF_CUSTOM_PORTRAIT else ScreenLayout.PREF_CUSTOM_LANDSCAPE

    // Reload when the view size changes (e.g. the device was rotated
    // while editing), since rectangles are kept in pixel space
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        prefs?.let { begin(it) }
    }

    // Load the saved custom rectangles, falling back to the default layout
    // for this orientation as a starting point
    fun begin(prefs: SharedPreferences) {
        this.prefs = prefs
        rects.clear()
        // Wait for a real size; onSizeChanged re-seeds once laid out
        if (width <= 0 || height <= 0) return
        val saved = ScreenLayout.loadCustomRects(prefs, prefKey())
        if (saved.isNotEmpty()) {
            for ((screen, rect) in saved)
                rects[screen] = RectF(
                    rect.left * width, rect.top * height,
                    rect.right * width, rect.bottom * height
                )
        } else {
            reset()
            return
        }
        invalidate()
    }

    // Seed from the default layout for this orientation
    fun reset() {
        rects.clear()
        val layout = ScreenLayout.compute(width, height, ScreenLayout.Config())
        for (quad in layout.quads)
            rects[quad.screen] = RectF(quad.rect)
        invalidate()
    }

    fun save(prefs: SharedPreferences) {
        val normalized = rects.mapValues { (_, r) ->
            RectF(r.left / width, r.top / height, r.right / width, r.bottom / height)
        }
        ScreenLayout.saveCustomRects(prefs, prefKey(), normalized)
    }

    private fun handleRadius(): Float = 18 * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.argb(96, 0, 0, 0))
        for ((screen, rect) in rects) {
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, strokePaint)
            canvas.drawCircle(rect.right, rect.bottom, handleRadius() * 0.6f, handlePaint)
            canvas.drawText(
                context.getString(
                    if (screen == ScreenLayout.Screen.TOP) R.string.layout_editor_top
                    else R.string.layout_editor_bottom
                ),
                rect.centerX(), rect.centerY() + textPaint.textSize / 3, textPaint
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Hit-test corner handles first, then screen bodies, with
                // later-drawn (bottom) screens taking priority
                for ((screen, rect) in rects.entries.reversed()) {
                    if (hypot(x - rect.right, y - rect.bottom) <= handleRadius()) {
                        dragScreen = screen
                        resizing = true
                        return true
                    }
                }
                for ((screen, rect) in rects.entries.reversed()) {
                    if (rect.contains(x, y)) {
                        dragScreen = screen
                        resizing = false
                        grabX = x - rect.left
                        grabY = y - rect.top
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val screen = dragScreen ?: return true
                val rect = rects[screen] ?: return true
                if (resizing) {
                    // Resize around the top-left corner at a fixed aspect
                    val aspect = ScreenLayout.aspectWidth(screen) / 240f
                    val minW = 64 * density
                    val newW = (x - rect.left).coerceAtLeast(minW)
                    rect.right = rect.left + newW
                    rect.bottom = rect.top + newW / aspect
                } else {
                    rect.offsetTo(x - grabX, y - grabY)
                }
                clamp(rect)
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragScreen = null
        }
        return true
    }

    private fun clamp(rect: RectF) {
        if (rect.width() > width) {
            val aspect = rect.width() / rect.height()
            rect.right = rect.left + width
            rect.bottom = rect.top + width / aspect
        }
        if (rect.height() > height) {
            val aspect = rect.width() / rect.height()
            rect.bottom = rect.top + height
            rect.right = rect.left + height * aspect
        }
        if (rect.left < 0) rect.offsetTo(0f, rect.top)
        if (rect.top < 0) rect.offsetTo(rect.left, 0f)
        if (rect.right > width) rect.offsetTo(width - rect.width(), rect.top)
        if (rect.bottom > height) rect.offsetTo(rect.left, height - rect.height())
    }
}
