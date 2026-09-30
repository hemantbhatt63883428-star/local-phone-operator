package com.hemant.localoperator.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.hemant.localoperator.agent.AgentRuntime
import com.hemant.localoperator.ui.OperatorOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class OperatorAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: OperatorOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.service = this
        overlay = OperatorOverlay(this, scope).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AgentRuntime.stop()
        overlay?.hide()
        overlay = null
        if (AccessibilityBridge.service === this) AccessibilityBridge.service = null
        scope.cancel()
        super.onDestroy()
    }

    suspend fun captureScreenshotPng(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hb = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                        if (bitmap == null) {
                            if (cont.isActive) cont.resume(null)
                            return
                        }
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                        bitmap.recycle()
                        if (cont.isActive) cont.resume(out.toByteArray())
                    } catch (_: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }
}
