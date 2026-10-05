package com.agentbubble.ui

import android.view.MotionEvent

/**
 * All the maths behind the floating chat window, kept pure so it can be tested without a device.
 *
 * Four jobs:
 *  - work out a sensible **medium** size for the card and float it in the middle of the screen,
 *  - keep the card where the user dragged it (and inside the screen),
 *  - keep the prompt visible when the keyboard opens by padding the card from the inside (the window
 *    itself never moves — moving it is what used to make the whole card shake),
 *  - decide when a touch outside the card should close it (but never while typing on the keyboard).
 */
object PanelGeometry {

    data class Placement(val width: Int, val height: Int, val x: Int, val y: Int)

    const val MIN_WIDTH_DP = 260
    const val MAX_WIDTH_DP = 460
    const val MIN_HEIGHT_DP = 360
    const val EDGE_MARGIN_DP = 10

    /**
     * A medium floating chat card. It sits in the middle of the screen — a card the user can move —
     * instead of being pinned to the right edge. A position the user dragged earlier wins, as does a
     * size they resized earlier.
     *
     * @return absolute x/y for a TOP|START window (0,0 = top-left of the screen).
     */
    fun floatingPlacement(
        screenW: Int,
        screenH: Int,
        density: Float,
        topInset: Int,
        bottomInset: Int = 0,
        savedWidth: Int = 0,
        savedHeight: Int = 0,
        savedX: Int = -1,
        savedY: Int = -1
    ): Placement {
        val margin = (EDGE_MARGIN_DP * density).toInt()
        val maxW = (MAX_WIDTH_DP * density).toInt().coerceAtMost(screenW - 2 * margin)
        val minW = (MIN_WIDTH_DP * density).toInt().coerceAtMost(maxW)
        val width = (if (savedWidth > 0) savedWidth else (screenW * 0.88f).toInt())
            .coerceIn(minW, maxW)

        val floor = topInset + (MIN_HEIGHT_DP * density).toInt()
        val maxH = (screenH - bottomInset - margin).coerceAtLeast(floor)
        val minH = (MIN_HEIGHT_DP * density).toInt().coerceAtMost(maxH)
        val height = (if (savedHeight > 0) savedHeight else (screenH * 0.62f).toInt())
            .coerceIn(minH, maxH)

        // default: centred, so it looks like a floating window and not a side panel
        val defaultX = (screenW - width) / 2
        val defaultY = topInset + ((screenH - topInset - bottomInset - height) / 2).coerceAtLeast(0)
        val x = if (savedX >= 0) savedX else defaultX
        val y = if (savedY >= 0) savedY else defaultY

        val clamped = clampPosition(x, y, width, height, screenW, screenH, topInset, bottomInset, density)
        return Placement(width, height, clamped.first, clamped.second)
    }

    /**
     * Keeps the card fully on screen: it may never slide under a screen edge (or under the status /
     * navigation bars).
     */
    fun clampPosition(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        screenW: Int,
        screenH: Int,
        topInset: Int,
        bottomInset: Int,
        density: Float
    ): Pair<Int, Int> {
        val margin = (EDGE_MARGIN_DP * density).toInt()
        val minX = margin
        val maxX = (screenW - width - margin).coerceAtLeast(minX)
        val minY = topInset + margin
        val maxY = (screenH - bottomInset - height - margin).coerceAtLeast(minY)
        return x.coerceIn(minX, maxX) to y.coerceIn(minY, maxY)
    }

    /**
     * How much of the card the keyboard actually covers. A card standing high up on the screen is not
     * covered at all, and then nothing has to be padded.
     *
     * @param windowBottom y + height of the card.
     */
    fun keyboardOverlap(windowBottom: Int, screenH: Int, keyboardHeight: Int): Int {
        if (keyboardHeight <= 0) return 0
        val keyboardTop = screenH - keyboardHeight
        return (windowBottom - keyboardTop).coerceIn(0, keyboardHeight)
    }

    /**
     * How much empty space must be left under the card content, given what the keyboard covers.
     *
     * The window itself never moves: the covered space is taken out of the inside of the card. Above
     * that padding [minVisiblePx] always remains — the caller passes the height of the header row plus
     * the input row plus a small gap, so the prompt is never hidden behind the keyboard no matter how
     * short the card is. Only the message list, which scrolls, gives way.
     */
    fun keyboardPadding(windowHeight: Int, keyboardHeight: Int, minVisiblePx: Int): Int {
        if (keyboardHeight <= 0) return 0
        val maxPad = (windowHeight - minVisiblePx).coerceAtLeast(0)
        return keyboardHeight.coerceIn(0, maxPad)
    }

    /**
     * A touch outside the card closes it — except while the keyboard is up, because every keyboard
     * key would count as "outside" and the chat would close as soon as you typed.
     */
    fun shouldCloseOnTouch(action: Int, keyboardVisible: Boolean): Boolean =
        action == MotionEvent.ACTION_OUTSIDE && !keyboardVisible
}
