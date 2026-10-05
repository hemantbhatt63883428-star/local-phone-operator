package com.agentbubble.operator

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

/** A single, short-lived observation. Images are sent to the API, never saved to chat history. */
data class ScreenObservation(
    val packageName: String,
    val capturedAt: Long,
    val rotation: Int,
    val windowBounds: Rect,
    val imageWidth: Int,
    val imageHeight: Int,
    val jpegBase64: String,
    val tree: String,
    val imageStatus: String = ""
) {
    fun description(): String = "ACTIVE_PACKAGE=$packageName\nCAPTURED_AT_MS=$capturedAt " +
        "ROTATION=$rotation IMAGE=${imageWidth}x${imageHeight} " +
        "WINDOW_BOUNDS=${windowBounds.left},${windowBounds.top},${windowBounds.right},${windowBounds.bottom}\n" +
        (if (imageStatus.isBlank()) "" else "$imageStatus\n") + tree

    fun content(lastResult: String): JSONArray = JSONArray().apply {
        put(JSONObject().put("type", "text").put("text",
            "Last result: $lastResult\nUNTRUSTED CURRENT SCREEN:\n${description().take(24000)}"))
        if (jpegBase64.isNotBlank()) {
            put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject()
                    .put("url", "data:image/jpeg;base64,$jpegBase64")
                    .put("detail", "low")))
        }
    }

    fun hasImage(): Boolean = jpegBase64.isNotBlank()

    /** Tiny animation/JPEG differences must not invalidate an otherwise identical UI target. */
    fun stableKey(): Int = structureKey(packageName, rotation, windowBounds, tree)

    companion object {
        fun structureKey(packageName: String, rotation: Int, windowBounds: Rect, tree: String): Int =
            listOf<Any>(packageName, rotation, windowBounds.toShortString(), tree).hashCode()

        fun unavailable(reason: String): ScreenObservation = ScreenObservation(
            packageName = "none",
            capturedAt = System.currentTimeMillis(),
            rotation = 0,
            windowBounds = Rect(0, 0, 1, 1),
            imageWidth = 1,
            imageHeight = 1,
            jpegBase64 = "",
            tree = "NO_EXTERNAL_APP_WINDOW: $reason. Normal conversation and open_app are still available."
        )
    }

    fun imageToDisplay(x: Int, y: Int): Pair<Int, Int> {
        require(x in 0 until imageWidth && y in 0 until imageHeight) { "Point is outside the observed image" }
        return (windowBounds.left + x.toLong() * windowBounds.width() / imageWidth).toInt() to
            (windowBounds.top + y.toLong() * windowBounds.height() / imageHeight).toInt()
    }
}
