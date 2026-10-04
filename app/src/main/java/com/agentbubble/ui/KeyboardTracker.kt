package com.agentbubble.ui

/**
 * Decides when the keyboard is really open, from noisy samples.
 *
 * Why this exists: reading insets once and reacting immediately made the window move, which changed
 * the insets, which moved the window again — the chat visibly shook. This tracker only accepts a
 * value after it has been seen [openAfter]/[closeAfter] times in a row and ignores tiny flickers,
 * so a wobbling signal can never make the UI jitter.
 */
class KeyboardTracker(
    /** ~80px: below this we treat it as noise, not a keyboard. */
    private val minimumHeight: Int = 80,
    private val openAfter: Int = 2,
    private val closeAfter: Int = 3
) {

    var height: Int = 0
        private set

    private var candidate = 0
    private var seen = 0

    /** Feed one sample (px). Returns true when the accepted value changed. */
    fun sample(raw: Int): Boolean {
        val value = if (raw < minimumHeight) 0 else raw

        if (value == candidate) {
            if (seen < 99) seen++
        } else {
            candidate = value
            seen = 1
        }

        if (candidate == height) return false
        val needed = if (candidate > 0) openAfter else closeAfter
        if (seen >= needed) {
            height = candidate
            return true
        }
        return false
    }

    val isOpen: Boolean get() = height > 0

    fun reset() {
        height = 0
        candidate = 0
        seen = 0
    }
}
