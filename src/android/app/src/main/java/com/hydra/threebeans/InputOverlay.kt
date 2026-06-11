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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Custom-drawn touch controls: circle pad, D-pad, A/B/X/Y, shoulders,
// Start/Select/Home, plus bottom-screen touch passthrough
class InputOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var haptics = true
    var bottomScreen = Rect()

    private var opacity = 128
    private var scale = 1f
    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private abstract inner class Control {
        var pointer = -1
        abstract fun contains(x: Float, y: Float): Boolean
        abstract fun draw(canvas: Canvas)
        open fun press(x: Float, y: Float) {}
        open fun move(x: Float, y: Float) {}
        open fun release() {}
    }

    private inner class Button(
        val label: String, val key: Int, val cx: Float, val cy: Float, val radius: Float
    ) : Control() {
        override fun contains(x: Float, y: Float) = hypot(x - cx, y - cy) <= radius * 1.2f

        override fun draw(canvas: Canvas) {
            fillPaint.alpha = if (pointer >= 0) opacity * 3 / 2 else opacity
            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius, strokePaint)
            textPaint.textSize = radius
            canvas.drawText(label, cx, cy + radius * 0.35f, textPaint)
        }

        override fun press(x: Float, y: Float) {
            if (key >= 0) NativeLibrary.pressKey(key) else NativeLibrary.pressHome()
        }

        override fun release() {
            if (key >= 0) NativeLibrary.releaseKey(key) else NativeLibrary.releaseHome()
        }
    }

    private inner class Pill(
        val label: String, val key: Int, val rect: RectF
    ) : Control() {
        override fun contains(x: Float, y: Float) =
            x >= rect.left - rect.height() / 2 && x <= rect.right + rect.height() / 2 &&
                y >= rect.top - rect.height() / 2 && y <= rect.bottom + rect.height() / 2

        override fun draw(canvas: Canvas) {
            val r = rect.height() / 2
            fillPaint.alpha = if (pointer >= 0) opacity * 3 / 2 else opacity
            canvas.drawRoundRect(rect, r, r, fillPaint)
            canvas.drawRoundRect(rect, r, r, strokePaint)
            textPaint.textSize = rect.height() * 0.55f
            canvas.drawText(label, rect.centerX(), rect.centerY() + rect.height() * 0.2f, textPaint)
        }

        override fun press(x: Float, y: Float) = NativeLibrary.pressKey(key)
        override fun release() = NativeLibrary.releaseKey(key)
    }

    private inner class Dpad(val cx: Float, val cy: Float, val extent: Float) : Control() {
        private val pressed = BooleanArray(4) // right, left, up, down

        override fun contains(x: Float, y: Float): Boolean {
            val dx = abs(x - cx)
            val dy = abs(y - cy)
            return dx <= extent && dy <= extent && (dx <= extent / 2.5f || dy <= extent / 2.5f)
        }

        override fun draw(canvas: Canvas) {
            val arm = extent / 2.5f
            fillPaint.alpha = if (pointer >= 0) opacity * 3 / 2 else opacity
            canvas.drawRoundRect(
                RectF(cx - extent, cy - arm, cx + extent, cy + arm), arm / 2, arm / 2, fillPaint
            )
            canvas.drawRoundRect(
                RectF(cx - arm, cy - extent, cx + arm, cy + extent), arm / 2, arm / 2, fillPaint
            )
        }

        override fun press(x: Float, y: Float) = move(x, y)

        override fun move(x: Float, y: Float) {
            val dx = x - cx
            val dy = y - cy
            val dead = extent / 4
            update(NativeLibrary.KEY_RIGHT, 0, dx > dead)
            update(NativeLibrary.KEY_LEFT, 1, dx < -dead)
            update(NativeLibrary.KEY_UP, 2, dy < -dead)
            update(NativeLibrary.KEY_DOWN, 3, dy > dead)
        }

        override fun release() {
            update(NativeLibrary.KEY_RIGHT, 0, false)
            update(NativeLibrary.KEY_LEFT, 1, false)
            update(NativeLibrary.KEY_UP, 2, false)
            update(NativeLibrary.KEY_DOWN, 3, false)
        }

        private fun update(key: Int, index: Int, state: Boolean) {
            if (pressed[index] == state) return
            pressed[index] = state
            if (state) NativeLibrary.pressKey(key) else NativeLibrary.releaseKey(key)
        }
    }

    private inner class CirclePad(val cx: Float, val cy: Float, val radius: Float) : Control() {
        private var thumbX = cx
        private var thumbY = cy

        override fun contains(x: Float, y: Float) = hypot(x - cx, y - cy) <= radius * 1.3f

        override fun draw(canvas: Canvas) {
            fillPaint.alpha = opacity / 2
            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius, strokePaint)
            fillPaint.alpha = if (pointer >= 0) opacity * 3 / 2 else opacity
            canvas.drawCircle(thumbX, thumbY, radius / 2, fillPaint)
            canvas.drawCircle(thumbX, thumbY, radius / 2, strokePaint)
        }

        override fun press(x: Float, y: Float) = move(x, y)

        override fun move(x: Float, y: Float) {
            var dx = x - cx
            var dy = y - cy
            val dist = hypot(dx, dy)
            if (dist > radius) {
                val angle = atan2(dy, dx)
                dx = radius * cos(angle)
                dy = radius * sin(angle)
            }
            thumbX = cx + dx
            thumbY = cy + dy
            NativeLibrary.setLStick(
                (dx * NativeLibrary.STICK_RANGE / radius).toInt(),
                (-dy * NativeLibrary.STICK_RANGE / radius).toInt()
            )
            invalidate()
        }

        override fun release() {
            thumbX = cx
            thumbY = cy
            NativeLibrary.setLStick(0, 0)
        }
    }

    private val controls = ArrayList<Control>()
    private var screenPointer = -1

    fun applyPreferences(scalePercent: Int, opacityPercent: Int, haptics: Boolean) {
        scale = scalePercent / 100f
        opacity = (opacityPercent * 255 / 100).coerceIn(0, 255)
        this.haptics = haptics
        layoutControls()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutControls()
    }

    private fun dp(value: Float): Float = value * density * scale

    private fun layoutControls() {
        controls.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val buttonR = dp(26f)
        val clusterR = dp(52f)

        // A/B/X/Y diamond on the right
        val abxyX = w - dp(86f)
        val abxyY = h - dp(160f)
        controls.add(Button("A", NativeLibrary.KEY_A, abxyX + clusterR, abxyY, buttonR))
        controls.add(Button("B", NativeLibrary.KEY_B, abxyX, abxyY + clusterR, buttonR))
        controls.add(Button("X", NativeLibrary.KEY_X, abxyX, abxyY - clusterR, buttonR))
        controls.add(Button("Y", NativeLibrary.KEY_Y, abxyX - clusterR, abxyY, buttonR))

        // D-pad and circle pad on the left
        controls.add(Dpad(dp(90f), h - dp(120f), dp(70f)))
        controls.add(CirclePad(dp(96f), h - dp(300f), dp(64f)))

        // Shoulder buttons above each cluster
        controls.add(Pill("L", NativeLibrary.KEY_L, RectF(dp(16f), h - dp(440f), dp(116f), h - dp(400f))))
        controls.add(Pill("R", NativeLibrary.KEY_R, RectF(w - dp(116f), h - dp(440f), w - dp(16f), h - dp(400f))))

        // Select, Home, and Start along the bottom center
        val pillW = dp(64f)
        val pillH = dp(30f)
        val pillY = h - dp(44f)
        controls.add(Pill("SELECT", NativeLibrary.KEY_SELECT, centeredPill(w / 2 - dp(86f), pillY, pillW, pillH)))
        controls.add(Button("⌂", -1, w / 2, pillY + pillH / 2, dp(17f)))
        controls.add(Pill("START", NativeLibrary.KEY_START, centeredPill(w / 2 + dp(86f), pillY, pillW, pillH)))
    }

    private fun centeredPill(cx: Float, top: Float, width: Float, height: Float) =
        RectF(cx - width / 2, top, cx + width / 2, top + height)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isEnabled) return
        fillPaint.color = Color.LTGRAY
        strokePaint.color = Color.WHITE
        strokePaint.alpha = opacity
        textPaint.color = Color.DKGRAY
        textPaint.alpha = (opacity * 3 / 2).coerceAtMost(255)
        for (control in controls)
            control.draw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                pointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount)
                    pointerMove(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointerUp(event.getPointerId(event.actionIndex))
            }

            MotionEvent.ACTION_CANCEL -> {
                for (control in controls) {
                    if (control.pointer < 0) continue
                    control.pointer = -1
                    control.release()
                }
                if (screenPointer >= 0) {
                    screenPointer = -1
                    NativeLibrary.releaseScreen()
                }
                invalidate()
            }
        }
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        if (isEnabled) {
            for (control in controls) {
                if (control.pointer >= 0 || !control.contains(x, y)) continue
                control.pointer = id
                control.press(x, y)
                if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return
            }
        }

        // Pointers that miss every control touch the bottom screen
        if (screenPointer < 0 && bottomScreen.contains(x.toInt(), y.toInt())) {
            screenPointer = id
            touchScreen(x, y)
        }
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        if (id == screenPointer) {
            touchScreen(x, y)
            return
        }
        for (control in controls) {
            if (control.pointer != id) continue
            control.move(x, y)
            return
        }
    }

    private fun pointerUp(id: Int) {
        if (id == screenPointer) {
            screenPointer = -1
            NativeLibrary.releaseScreen()
            return
        }
        for (control in controls) {
            if (control.pointer != id) continue
            control.pointer = -1
            control.release()
            invalidate()
            return
        }
    }

    private fun touchScreen(x: Float, y: Float) {
        NativeLibrary.pressScreen(
            ((x - bottomScreen.left) * 320 / bottomScreen.width()).toInt(),
            ((y - bottomScreen.top) * 240 / bottomScreen.height()).toInt()
        )
    }
}
