package com.agentbubble.operator

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume

internal object ScreenCapturePolicy {
    const val MAX_ATTEMPTS = 3
    const val MIN_INTERVAL_MS = 750L
    const val RATE_LIMIT_ERROR = 3

    fun shouldRetry(errorCode: Int, attempt: Int): Boolean =
        errorCode == RATE_LIMIT_ERROR && attempt < MAX_ATTEMPTS - 1

    fun retryDelayMs(attempt: Int): Long = MIN_INTERVAL_MS * (attempt + 1)

    /** Some Android builds fail window capture while display capture remains available. */
    fun mayFallbackToDisplay(errorCode: Int): Boolean = errorCode == 1 || errorCode == 5

    fun failureMessage(errorCode: Int): String = when (errorCode) {
        1 -> "Android screenshot service failed internally. Try again."
        2 -> "Screenshot access is unavailable. Disable and re-enable Accessibility access."
        3 -> "Android screenshot rate limit did not clear. Wait a moment and try again."
        4 -> "The current display cannot be captured."
        5 -> "The target app window changed before it could be captured. Try again."
        6 -> "This app marks the current window secure, so Android will not capture it."
        else -> "Screen capture failed ($errorCode)."
    }
}

private data class ScreenshotAttempt(
    val result: AccessibilityService.ScreenshotResult? = null,
    val errorCode: Int? = null
)

private data class CapturedScreenshot(
    val result: AccessibilityService.ScreenshotResult,
    val fullDisplay: Boolean
)

private class ScreenCaptureFailure(val errorCode: Int, message: String) : IllegalStateException(message)

/** Convert display coordinates into the bitmap's pixels, accounting for display scaling. */
internal object CaptureGeometry {
    fun cropRect(windowBounds: Rect, displayWidth: Int, displayHeight: Int, bitmapWidth: Int, bitmapHeight: Int): Rect {
        require(displayWidth > 0 && displayHeight > 0 && bitmapWidth > 0 && bitmapHeight > 0)
        val visible = Rect(windowBounds)
        check(visible.intersect(0, 0, displayWidth, displayHeight)) {
            "Target window is outside the captured display."
        }
        val left = (visible.left.toLong() * bitmapWidth / displayWidth).toInt().coerceIn(0, bitmapWidth - 1)
        val top = (visible.top.toLong() * bitmapHeight / displayHeight).toInt().coerceIn(0, bitmapHeight - 1)
        val right = ((visible.right.toLong() * bitmapWidth + displayWidth - 1) / displayWidth)
            .toInt().coerceIn(left + 1, bitmapWidth)
        val bottom = ((visible.bottom.toLong() * bitmapHeight + displayHeight - 1) / displayHeight)
            .toInt().coerceIn(top + 1, bitmapHeight)
        return Rect(left, top, right, bottom)
    }
}

/** Passive unless a user explicitly starts an Action task. No overlay or polling on connect. */
class OperatorAccessibilityService : AccessibilityService() {
    companion object {
        private const val MAX_SCREEN_IMAGE_EDGE = 1024f
        private const val SCREEN_JPEG_QUALITY = 68
        @Volatile var connected: OperatorAccessibilityService? = null; private set
    }
    private var lastScreenshotRequestAtMs = 0L
    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { if (connected === this) connected = null }
    override fun onDestroy() { if (connected === this) connected = null; super.onDestroy() }

    private fun targetWindow(): AccessibilityWindowInfo? = runCatching { windows.toList() }
        .getOrDefault(emptyList())
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            it.root != null && it.root?.packageName?.toString() != packageName }
        .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }
            .thenByDescending { it.isActive }.thenByDescending { it.layer })
        .firstOrNull()

    /** Re-read target identity and structure without another screenshot/JPEG encode. */
    fun currentScreenKey(): Int {
        check(Build.VERSION.SDK_INT >= 34) { "Chat + Tasks screen images require Android 14 or newer." }
        val target = targetWindow() ?: error("No external app window is visible.")
        val root = target.root ?: error("Target app's accessibility tree is unavailable.")
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        check(!bounds.isEmpty) { "Target window has no visible bounds." }
        val rotation = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        return ScreenObservation.structureKey(
            root.packageName?.toString().orEmpty(), rotation, bounds, NodeTree.render(root, 300)
        )
    }

    /** Capture only the external app window, so this app's bubble and Stop panel stay out of frame. */
    suspend fun observeScreen(): ScreenObservation {
        check(Build.VERSION.SDK_INT >= 34) { "Chat + Tasks screen images require Android 14 or newer." }
        val target = targetWindow() ?: error("No external app window is visible.")
        val root = target.root ?: error("Target app's accessibility tree is unavailable.")
        val pkg = root.packageName?.toString().orEmpty()
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        check(!bounds.isEmpty) { "Target window has no visible bounds." }
        val tree = NodeTree.render(root, 300)
        check(NodeTree.snapshot(root, 500).none { it.password || SafetyPolicy.protectedText(it.text + " " + it.description + " " + it.viewId) }) {
            "Authentication screen detected; its image was not sent to the provider. Continue manually."
        }
        val rotation = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val capture = try {
            captureWindowOrDisplay(target.id, target.displayId)
        } catch (e: ScreenCaptureFailure) {
            if (e.errorCode == 6) throw e // secure content must never enter the model request
            return ScreenObservation(pkg, System.currentTimeMillis(), rotation, bounds, 1, 1,
                "", tree, "Screenshot unavailable: ${e.message}. Accessibility text only.")
        }
        // The target can change while Android is capturing. Never bind a fresh image to stale UI nodes.
        val current = targetWindow()
        if (current == null) {
            capture.result.hardwareBuffer.close()
            error("Target app changed during screen capture. Try again.")
        }
        val currentBounds = Rect().also { current.getBoundsInScreen(it) }
        if (current.id != target.id || current.root?.packageName?.toString() != pkg ||
            currentBounds != bounds ||
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation != rotation) {
            capture.result.hardwareBuffer.close()
            error("Target app changed during screen capture. Try again.")
        }
        val displaySize = if (capture.fullDisplay) {
            val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(target.displayId)
            if (display == null) {
                capture.result.hardwareBuffer.close()
                error("The target display is no longer available.")
            }
            Point().also { display.getRealSize(it) }
        } else null
        return withContext(Dispatchers.Default) {
            val buffer = capture.result.hardwareBuffer
            val raw = try {
                Bitmap.wrapHardwareBuffer(buffer, capture.result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    ?: error("Could not read captured window image")
            } finally { buffer.close() }
            try {
                val crop = if (displaySize != null) {
                    val area = CaptureGeometry.cropRect(
                        bounds, displaySize.x, displaySize.y, raw.width, raw.height
                    )
                    Bitmap.createBitmap(raw, area.left, area.top, area.width(), area.height())
                } else raw
                try {
                    // The accessibility tree carries exact text and bounds. Keep the image economical.
                    val ratio = minOf(1f, MAX_SCREEN_IMAGE_EDGE / maxOf(crop.width, crop.height))
                    val width = (crop.width * ratio).toInt().coerceAtLeast(1)
                    val height = (crop.height * ratio).toInt().coerceAtLeast(1)
                    val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(crop, width, height, true) else crop
                    val bytes = ByteArrayOutputStream().use { stream ->
                        check(scaled.compress(Bitmap.CompressFormat.JPEG, SCREEN_JPEG_QUALITY, stream)) {
                            "Could not encode screen image"
                        }
                        stream.toByteArray()
                    }
                    if (scaled !== crop) scaled.recycle()
                    ScreenObservation(pkg, System.currentTimeMillis(), rotation, bounds, width, height,
                        Base64.encodeToString(bytes, Base64.NO_WRAP), tree)
                } finally { if (crop !== raw) crop.recycle() }
            } finally { raw.recycle() }
        }
    }

    private suspend fun captureWindowOrDisplay(windowId: Int, displayId: Int): CapturedScreenshot {
        var useDisplay = false
        var lastError = 1
        for (attempt in 0 until ScreenCapturePolicy.MAX_ATTEMPTS) {
            val elapsed = SystemClock.elapsedRealtime() - lastScreenshotRequestAtMs
            val waitMs = ScreenCapturePolicy.MIN_INTERVAL_MS - elapsed
            if (waitMs > 0) delay(waitMs)
            lastScreenshotRequestAtMs = SystemClock.elapsedRealtime()

            val outcome = suspendCancellableCoroutine<ScreenshotAttempt> { cont ->
                val callback = object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        if (cont.isActive) cont.resume(ScreenshotAttempt(result = result))
                        else result.hardwareBuffer.close()
                    }
                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(ScreenshotAttempt(errorCode = errorCode))
                    }
                }
                try {
                    if (useDisplay) takeScreenshot(displayId, mainExecutor, callback)
                    else takeScreenshotOfWindow(windowId, mainExecutor, callback)
                } catch (_: SecurityException) {
                    // Never treat an explicit platform security denial as a recoverable window error.
                    if (cont.isActive) cont.resume(ScreenshotAttempt(errorCode = 6))
                } catch (_: RuntimeException) {
                    if (cont.isActive) cont.resume(ScreenshotAttempt(errorCode = 1))
                }
            }
            outcome.result?.let { return CapturedScreenshot(it, useDisplay) }
            lastError = outcome.errorCode ?: 1
            if (!useDisplay && ScreenCapturePolicy.mayFallbackToDisplay(lastError)) {
                useDisplay = true
                continue
            }
            if (ScreenCapturePolicy.shouldRetry(lastError, attempt)) {
                delay(ScreenCapturePolicy.retryDelayMs(attempt))
                continue
            }
            break
        }
        throw ScreenCaptureFailure(lastError, ScreenCapturePolicy.failureMessage(lastError) +
            if (useDisplay) " Display capture also failed." else "")
    }

    /**
     * Return the focused external application, never Agent Bubble's own overlay. During an IME or
     * overlay transition rootInActiveWindow/windows can briefly be empty; ActionRunner retries before
     * declaring failure instead of asking the user to reopen an already-visible app.
     */
    fun targetRoot(): AccessibilityNodeInfo? {
        // Use the same application window that observeScreen captures. The focused IME window
        // must not replace the observed app as a tool target when the keyboard is visible.
        targetWindow()?.root?.let { return it }
        val own = packageName
        val active = runCatching { rootInActiveWindow }
            .getOrNull()
            ?.takeIf { it.packageName?.toString() != own }
        if (active != null) return active
        val apps = runCatching { windows.toList() }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                .thenByDescending { it.isActive }
                .thenByDescending { it.layer })
        return apps.asSequence()
            .mapNotNull { runCatching { it.root }.getOrNull() }
            .firstOrNull { it.packageName?.toString() != own }
    }
}
