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

import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.RectF

// Positions the 400x240 top and 320x240 bottom screens within a view,
// supporting the standard layout modes plus a free-form custom layout
object ScreenLayout {
    const val TOP_WIDTH = 400
    const val TOP_HEIGHT = 240
    const val BOTTOM_WIDTH = 320
    const val BOTTOM_HEIGHT = 240

    enum class Screen { TOP, BOTTOM }

    // Landscape layout modes
    enum class LayoutMode {
        ORIGINAL, // Top screen stacked above the bottom screen, like the console
        SINGLE_SCREEN, // Only one screen, maximized
        LARGE_SCREEN, // One large screen with the other small beside it
        SIDE_SCREEN, // Both screens side by side at equal height
        HYBRID_SCREEN, // Large screen plus small copies of both screens stacked beside it
        CUSTOM_LAYOUT // Free-form rectangles from the layout editor
    }

    // Where the small screen sits in LARGE_SCREEN mode
    enum class SmallScreenPosition {
        TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT,
        TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT,
        ABOVE, BELOW
    }

    // Portrait layout modes
    enum class PortraitLayout {
        TOP_FULL_WIDTH, // Top screen spans the full width, bottom below
        ORIGINAL, // Both screens fit and centered like the console
        CUSTOM_LAYOUT // Free-form rectangles from the layout editor
    }

    // What an external display shows
    enum class SecondaryDisplayLayout { NONE, TOP_SCREEN, BOTTOM_SCREEN, SIDE_BY_SIDE }

    data class Quad(val screen: Screen, val rect: Rect)

    // touch is the rectangle mapped to the 3DS touchscreen, when one is shown
    data class Layout(val quads: List<Quad>, val touch: Rect?)

    data class Config(
        val mode: LayoutMode = LayoutMode.SIDE_SCREEN,
        val smallPosition: SmallScreenPosition = SmallScreenPosition.BOTTOM_RIGHT,
        val portrait: PortraitLayout = PortraitLayout.TOP_FULL_WIDTH,
        val swapScreens: Boolean = false,
        val customLandscape: Map<Screen, RectF> = emptyMap(),
        val customPortrait: Map<Screen, RectF> = emptyMap(),
        // Which screens this surface shows; the primary display drops a
        // screen when an external display is already showing it
        val screens: Set<Screen> = setOf(Screen.TOP, Screen.BOTTOM)
    )

    // Preference keys shared with SettingsActivity and the layout editor
    const val PREF_MODE = "layoutMode"
    const val PREF_SMALL_POSITION = "smallScreenPosition"
    const val PREF_PORTRAIT = "portraitLayout"
    const val PREF_SWAP = "swapScreens"
    const val PREF_SECONDARY = "secondaryDisplayLayout"
    const val PREF_CUSTOM_LANDSCAPE = "customLayoutLandscape"
    const val PREF_CUSTOM_PORTRAIT = "customLayoutPortrait"

    fun loadConfig(prefs: SharedPreferences): Config = Config(
        mode = enumPref(prefs, PREF_MODE, LayoutMode.SIDE_SCREEN),
        smallPosition = enumPref(prefs, PREF_SMALL_POSITION, SmallScreenPosition.BOTTOM_RIGHT),
        portrait = enumPref(prefs, PREF_PORTRAIT, PortraitLayout.TOP_FULL_WIDTH),
        swapScreens = prefs.getBoolean(PREF_SWAP, false),
        customLandscape = loadCustomRects(prefs, PREF_CUSTOM_LANDSCAPE),
        customPortrait = loadCustomRects(prefs, PREF_CUSTOM_PORTRAIT)
    )

    fun secondaryLayout(prefs: SharedPreferences): SecondaryDisplayLayout =
        enumPref(prefs, PREF_SECONDARY, SecondaryDisplayLayout.NONE)

    // ListPreference persists entry values as strings, so enums are stored
    // by ordinal in string form
    private inline fun <reified T : Enum<T>> enumPref(
        prefs: SharedPreferences, key: String, default: T
    ): T {
        val ordinal = prefs.getString(key, null)?.toIntOrNull() ?: return default
        return enumValues<T>().getOrElse(ordinal) { default }
    }

    // Custom rectangles persist as "l,t,r,b;l,t,r,b" (top;bottom) with
    // coordinates normalized to the view size
    fun loadCustomRects(prefs: SharedPreferences, key: String): Map<Screen, RectF> {
        val value = prefs.getString(key, null) ?: return emptyMap()
        val parts = value.split(';')
        if (parts.size != 2) return emptyMap()
        val rects = HashMap<Screen, RectF>()
        for ((index, part) in parts.withIndex()) {
            val nums = part.split(',').mapNotNull { it.toFloatOrNull() }
            if (nums.size != 4) return emptyMap()
            rects[if (index == 0) Screen.TOP else Screen.BOTTOM] =
                RectF(nums[0], nums[1], nums[2], nums[3])
        }
        return rects
    }

    fun saveCustomRects(prefs: SharedPreferences, key: String, rects: Map<Screen, RectF>) {
        val top = rects[Screen.TOP]
        val bottom = rects[Screen.BOTTOM]
        if (top == null || bottom == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val encode = { r: RectF -> "${r.left},${r.top},${r.right},${r.bottom}" }
        prefs.edit().putString(key, "${encode(top)};${encode(bottom)}").apply()
    }

    fun aspectWidth(screen: Screen): Int =
        if (screen == Screen.TOP) TOP_WIDTH else BOTTOM_WIDTH

    fun compute(width: Int, height: Int, config: Config): Layout {
        if (width <= 0 || height <= 0)
            return Layout(emptyList(), null)

        // A surface restricted to one screen just shows it maximized
        if (config.screens.size == 1)
            return finish(listOf(Quad(config.screens.first(), fitRect(width, height, aspectWidth(config.screens.first()), 240))))

        val big = if (config.swapScreens) Screen.BOTTOM else Screen.TOP
        val small = if (config.swapScreens) Screen.TOP else Screen.BOTTOM

        if (height > width) { // Portrait
            return when (config.portrait) {
                PortraitLayout.TOP_FULL_WIDTH -> portraitFullWidth(width, height, big, small)
                PortraitLayout.ORIGINAL -> original(width, height, big, small)
                PortraitLayout.CUSTOM_LAYOUT ->
                    custom(width, height, config.customPortrait)
                        ?: portraitFullWidth(width, height, big, small)
            }
        }

        return when (config.mode) {
            LayoutMode.ORIGINAL -> original(width, height, big, small)
            LayoutMode.SINGLE_SCREEN ->
                finish(listOf(Quad(big, fitRect(width, height, aspectWidth(big), 240))))
            LayoutMode.LARGE_SCREEN -> largeScreen(width, height, big, small, config.smallPosition)
            LayoutMode.SIDE_SCREEN -> sideBySide(width, height, big, small)
            LayoutMode.HYBRID_SCREEN -> hybrid(width, height, big, small)
            LayoutMode.CUSTOM_LAYOUT ->
                custom(width, height, config.customLandscape)
                    ?: sideBySide(width, height, big, small)
        }
    }

    // The touchscreen maps to the first quad showing the bottom screen
    private fun finish(quads: List<Quad>): Layout =
        Layout(quads, quads.firstOrNull { it.screen == Screen.BOTTOM }?.rect)

    private fun fitRect(width: Int, height: Int, aspectW: Int, aspectH: Int): Rect {
        val scale = minOf(width.toFloat() / aspectW, height.toFloat() / aspectH)
        val w = (aspectW * scale).toInt()
        val h = (aspectH * scale).toInt()
        val x = (width - w) / 2
        val y = (height - h) / 2
        return Rect(x, y, x + w, y + h)
    }

    // First screen spans the full width with the other below it, centered
    private fun portraitFullWidth(width: Int, height: Int, first: Screen, second: Screen): Layout {
        val scale = width.toFloat() / aspectWidth(first)
        val firstH = (240 * scale).toInt()
        val secondW = (aspectWidth(second) * scale).toInt()
        val secondX = (width - secondW) / 2
        return finish(listOf(
            Quad(first, Rect(0, 0, width, firstH)),
            Quad(second, Rect(secondX, firstH, secondX + secondW, firstH + firstH))
        ))
    }

    // Both screens stacked at native proportions, like the console
    private fun original(width: Int, height: Int, first: Screen, second: Screen): Layout {
        val blockW = maxOf(aspectWidth(first), aspectWidth(second))
        val block = fitRect(width, height, blockW, 480)
        val scale = block.height() / 480f
        val firstW = (aspectWidth(first) * scale).toInt()
        val secondW = (aspectWidth(second) * scale).toInt()
        val midY = block.top + block.height() / 2
        val firstX = block.left + (block.width() - firstW) / 2
        val secondX = block.left + (block.width() - secondW) / 2
        return finish(listOf(
            Quad(first, Rect(firstX, block.top, firstX + firstW, midY)),
            Quad(second, Rect(secondX, midY, secondX + secondW, block.bottom))
        ))
    }

    // Both screens side by side at equal height, centered
    private fun sideBySide(width: Int, height: Int, left: Screen, right: Screen): Layout {
        val leftAW = aspectWidth(left)
        val rightAW = aspectWidth(right)
        val scale = minOf(height.toFloat() / 240, width.toFloat() / (leftAW + rightAW))
        val leftW = (leftAW * scale).toInt()
        val rightW = (rightAW * scale).toInt()
        val screenH = (240 * scale).toInt()
        val x = (width - leftW - rightW) / 2
        val y = (height - screenH) / 2
        return finish(listOf(
            Quad(left, Rect(x, y, x + leftW, y + screenH)),
            Quad(right, Rect(x + leftW, y, x + leftW + rightW, y + screenH))
        ))
    }

    // One large screen with the other at a fraction of its scale
    private fun largeScreen(
        width: Int, height: Int, big: Screen, small: Screen, position: SmallScreenPosition
    ): Layout {
        val ratio = 2.5f // Linear scale of the big screen relative to the small one
        val bigAW = aspectWidth(big)
        val smallAW = aspectWidth(small)

        if (position == SmallScreenPosition.ABOVE || position == SmallScreenPosition.BELOW) {
            // Vertical packing: big screen with the small one above or below
            val scale = minOf(
                width.toFloat() / bigAW,
                height.toFloat() / (240 + 240 / ratio)
            )
            val bigW = (bigAW * scale).toInt()
            val bigH = (240 * scale).toInt()
            val smallW = (smallAW * scale / ratio).toInt()
            val smallH = (240 * scale / ratio).toInt()
            val totalH = bigH + smallH
            val y = (height - totalH) / 2
            val bigX = (width - bigW) / 2
            val smallX = (width - smallW) / 2
            val bigOnTop = position == SmallScreenPosition.BELOW
            val bigY = if (bigOnTop) y else y + smallH
            val smallY = if (bigOnTop) y + bigH else y
            return finish(listOf(
                Quad(big, Rect(bigX, bigY, bigX + bigW, bigY + bigH)),
                Quad(small, Rect(smallX, smallY, smallX + smallW, smallY + smallH))
            ))
        }

        // Horizontal packing: big screen with a small side column
        val scale = minOf(
            height.toFloat() / 240,
            width.toFloat() / (bigAW + smallAW / ratio)
        )
        val bigW = (bigAW * scale).toInt()
        val bigH = (240 * scale).toInt()
        val smallW = (smallAW * scale / ratio).toInt()
        val smallH = (240 * scale / ratio).toInt()
        val totalW = bigW + smallW
        val x = (width - totalW) / 2
        val y = (height - bigH) / 2
        val smallOnLeft = position == SmallScreenPosition.TOP_LEFT ||
            position == SmallScreenPosition.MIDDLE_LEFT ||
            position == SmallScreenPosition.BOTTOM_LEFT
        val bigX = if (smallOnLeft) x + smallW else x
        val smallX = if (smallOnLeft) x else x + bigW
        val smallY = when (position) {
            SmallScreenPosition.TOP_RIGHT, SmallScreenPosition.TOP_LEFT -> y
            SmallScreenPosition.MIDDLE_RIGHT, SmallScreenPosition.MIDDLE_LEFT -> y + (bigH - smallH) / 2
            else -> y + bigH - smallH
        }
        return finish(listOf(
            Quad(big, Rect(bigX, y, bigX + bigW, y + bigH)),
            Quad(small, Rect(smallX, smallY, smallX + smallW, smallY + smallH))
        ))
    }

    // Large screen on one side with small copies of both screens stacked
    // beside it; the large screen is drawn twice
    private fun hybrid(width: Int, height: Int, big: Screen, small: Screen): Layout {
        // Each small screen is half the big screen's height
        val bigAW = aspectWidth(big)
        val colAW = maxOf(aspectWidth(big), aspectWidth(small)) / 2f
        val scale = minOf(height.toFloat() / 240, width.toFloat() / (bigAW + colAW))
        val bigW = (bigAW * scale).toInt()
        val bigH = (240 * scale).toInt()
        val smallTopW = (aspectWidth(big) * scale / 2).toInt()
        val smallBotW = (aspectWidth(small) * scale / 2).toInt()
        val smallH = bigH / 2
        val colW = maxOf(smallTopW, smallBotW)
        val totalW = bigW + colW
        val x = (width - totalW) / 2
        val y = (height - bigH) / 2
        val topX = x + bigW + (colW - smallTopW) / 2
        val botX = x + bigW + (colW - smallBotW) / 2
        return finish(listOf(
            Quad(big, Rect(x, y, x + bigW, y + bigH)),
            Quad(big, Rect(topX, y, topX + smallTopW, y + smallH)),
            Quad(small, Rect(botX, y + smallH, botX + smallBotW, y + smallH + smallH))
        ))
    }

    // Free-form rectangles normalized to the view size
    private fun custom(width: Int, height: Int, rects: Map<Screen, RectF>): Layout? {
        val top = rects[Screen.TOP] ?: return null
        val bottom = rects[Screen.BOTTOM] ?: return null
        val scale = { r: RectF ->
            Rect(
                (r.left * width).toInt(), (r.top * height).toInt(),
                (r.right * width).toInt(), (r.bottom * height).toInt()
            )
        }
        return finish(listOf(
            Quad(Screen.TOP, scale(top)),
            Quad(Screen.BOTTOM, scale(bottom))
        ))
    }
}
