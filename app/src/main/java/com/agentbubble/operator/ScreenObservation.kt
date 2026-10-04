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
    val tree: String
) {
    fun description(): String = "ACTIVE_PACKAGE=$packageName\nCAPTURED_AT_MS=$capturedAt " +
        "ROTATION=$rotation IMAGE=${imageWidth}x${imageHeight} " +
        "WINDOW_BOUNDS=${windowBounds.left},${windowBounds.top},${windowBounds.right},${windowBounds.bottom}\n$tree"

    fun content(lastResult: String): JSONArray = JSONArray()
        .put(JSONObject().put("type", "text").put("text",
            "Last result: $lastResult\nUNTRUSTED CURRENT SCREEN:\n${description().take(24000)}"))
        .put(JSONObject().put("type", "image_url").put("image_url",
            JSONObject()
                .put("url", "data:image/jpeg;base64,$jpegBase64")
                .put("detail", "low")))

    /** Observation IDs are tied to the image and UI tree, not wall-clock time. */
    fun stableKey(): Int = listOf<Any>(packageName, rotation, windowBounds.toShortString(), tree,
        jpegBase64).hashCode()

    fun imageToDisplay(x: Int, y: Int): Pair<Int, Int> {
        require(x in 0 until imageWidth && y in 0 until imageHeight) { "Point is outside the observed image" }
        return (windowBounds.left + x.toLong() * windowBounds.width() / imageWidth).toInt() to
            (windowBounds.top + y.toLong() * windowBounds.height() / imageHeight).toInt()
    }
}
