package com.agentbubble.operator

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Passive unless a user explicitly starts an Action task. No overlay or polling on connect. */
class OperatorAccessibilityService : AccessibilityService() {
    companion object { @Volatile var connected: OperatorAccessibilityService? = null; private set }
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
        val capture = suspendCancellableCoroutine<android.accessibilityservice.AccessibilityService.ScreenshotResult> { cont ->
            takeScreenshotOfWindow(target.id, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) { if (cont.isActive) cont.resume(result) }
                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(
                        "Screen capture failed ($errorCode). Secure windows and system restrictions cannot be captured."))
                }
            })
        }
        return withContext(Dispatchers.Default) {
            val buffer = capture.hardwareBuffer
            val raw = try {
                Bitmap.wrapHardwareBuffer(buffer, capture.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    ?: error("Could not read captured window image")
            } finally { buffer.close() }
            try {
                val ratio = minOf(1f, 1280f / maxOf(raw.width, raw.height))
                val width = (raw.width * ratio).toInt().coerceAtLeast(1)
                val height = (raw.height * ratio).toInt().coerceAtLeast(1)
                val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(raw, width, height, true) else raw
                val bytes = ByteArrayOutputStream().use { stream ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 78, stream)) { "Could not encode screen image" }
                    stream.toByteArray()
                }
                if (scaled !== raw) scaled.recycle()
                val rotation = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                ScreenObservation(pkg, System.currentTimeMillis(), rotation, bounds, width, height,
                    Base64.encodeToString(bytes, Base64.NO_WRAP), tree)
            } finally { raw.recycle() }
        }
    }

    /**
     * Return the focused external application, never Agent Bubble's own overlay. During an IME or
     * overlay transition rootInActiveWindow/windows can briefly be empty; ActionRunner retries before
     * declaring failure instead of asking the user to reopen an already-visible app.
     */
    fun targetRoot(): AccessibilityNodeInfo? {
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
