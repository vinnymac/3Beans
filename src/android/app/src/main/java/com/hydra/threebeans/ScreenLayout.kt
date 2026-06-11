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

import android.graphics.Rect

// Positions the 400x240 top and 320x240 bottom screens within a view:
// stacked at the top edge in portrait, side by side and centered in landscape
object ScreenLayout {
    data class Layout(val top: Rect, val bottom: Rect)

    fun compute(width: Int, height: Int): Layout {
        if (width <= 0 || height <= 0)
            return Layout(Rect(), Rect())

        if (height > width) { // Portrait
            val scale = width.toFloat() / 400
            val topH = (240 * scale).toInt()
            val bottomW = (320 * scale).toInt()
            val bottomH = topH
            val bottomX = (width - bottomW) / 2
            return Layout(
                Rect(0, 0, width, topH),
                Rect(bottomX, topH, bottomX + bottomW, topH + bottomH)
            )
        }

        // Landscape
        val scale = minOf(height.toFloat() / 240, width.toFloat() / (400 + 320))
        val topW = (400 * scale).toInt()
        val bottomW = (320 * scale).toInt()
        val screenH = (240 * scale).toInt()
        val x = (width - topW - bottomW) / 2
        val y = (height - screenH) / 2
        return Layout(
            Rect(x, y, x + topW, y + screenH),
            Rect(x + topW, y, x + topW + bottomW, y + screenH)
        )
    }
}
