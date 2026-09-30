package com.hemant.localoperator.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.hemant.localoperator.safety.SafetyPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DeviceController(private val service: OperatorAccessibilityService) {
    fun inspect(maxNodes: Int): String {
        val pkg = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        return "ACTIVE_PACKAGE=$pkg\n" + NodeTree.render(service.rootInActiveWindow, maxNodes.coerceIn(40, 500))
    }

    fun openApp(name: String): String {
        val pm = service.packageManager
        val normalized = name.trim().lowercase()
        val candidates = pm.getInstalledApplications(0).mapNotNull { app ->
            val label = pm.getApplicationLabel(app).toString()
            if (label.lowercase() == normalized || app.packageName.lowercase() == normalized || label.lowercase().contains(normalized)) {
                Triple(app.packageName, label, pm.getLaunchIntentForPackage(app.packageName))
            } else null
        }
        val chosen = candidates.sortedBy { if (it.second.equals(name, true)) 0 else 1 }.firstOrNull()
            ?: return "APP_NOT_FOUND:$name"
        val intent = chosen.third ?: return "APP_HAS_NO_LAUNCH_INTENT:${chosen.first}"
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return "OPENED:${chosen.second} package=${chosen.first}"
    }

    fun clickText(text: String, exact: Boolean): String {
        val root = service.rootInActiveWindow ?: return "NO_ACTIVE_WINDOW"
        val matches = root.findAccessibilityNodeInfosByText(text).orEmpty().filter { node ->
            if (!exact) true else {
                node.text?.toString()?.equals(text, true) == true || node.contentDescription?.toString()?.equals(text, true) == true
            }
        }
        val node = matches.firstOrNull() ?: return "TEXT_NOT_FOUND:$text"
        val visibleLabel = node.text?.toString() ?: node.contentDescription?.toString()
        val safety = SafetyPolicy.mayClick(service, visibleLabel)
        if (!safety.first) return "SENSITIVE_ACTION_BLOCKED:${safety.second}"
        return if (clickNodeOrParent(node)) "CLICKED_TEXT:$text matches=${matches.size}" else "CLICK_FAILED:$text"
    }

    fun clickNode(id: Int): String {
        val node = NodeTree.findById(service.rootInActiveWindow, id) ?: return "NODE_NOT_FOUND:$id"
        val label = node.text?.toString() ?: node.contentDescription?.toString()
        val safety = SafetyPolicy.mayClick(service, label)
        if (!safety.first) return "SENSITIVE_ACTION_BLOCKED:${safety.second}"
        return if (clickNodeOrParent(node)) "CLICKED_NODE:$id" else "CLICK_FAILED_NODE:$id"
    }

    fun setText(id: Int, text: String, append: Boolean): String {
        val root = service.rootInActiveWindow ?: return "NO_ACTIVE_WINDOW"
        val node = (if (id >= 0) NodeTree.findById(root, id) else findFocusedOrEditable(root))
            ?: return "EDITABLE_NODE_NOT_FOUND"
        val safety = SafetyPolicy.mayTypeInto(node.isPassword)
        if (!safety.first) return "SENSITIVE_INPUT_BLOCKED:${safety.second}"
        val current = node.text?.toString().orEmpty()
        val finalText = if (append) current + text else text
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) "TEXT_SET node=$id chars=${text.length}" else "TEXT_SET_FAILED node=$id"
    }

    fun scroll(direction: String): String {
        val root = service.rootInActiveWindow ?: return "NO_ACTIVE_WINDOW"
        val scrollable = findFirst(root) { it.isScrollable }
        if (scrollable != null) {
            val action = when (direction.lowercase()) {
                "up", "left", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (scrollable.performAction(action)) return "SCROLLED_ACCESSIBILITY:$direction"
        }
        val dm = service.resources.displayMetrics
        val x = dm.widthPixels / 2f
        return when (direction.lowercase()) {
            "up" -> if (swipeSync(x, dm.heightPixels * .35f, x, dm.heightPixels * .75f, 350)) "SWIPED_UP" else "SCROLL_FAILED"
            "left" -> if (swipeSync(dm.widthPixels * .3f, dm.heightPixels / 2f, dm.widthPixels * .8f, dm.heightPixels / 2f, 350)) "SWIPED_LEFT" else "SCROLL_FAILED"
            "right" -> if (swipeSync(dm.widthPixels * .8f, dm.heightPixels / 2f, dm.widthPixels * .3f, dm.heightPixels / 2f, 350)) "SWIPED_RIGHT" else "SCROLL_FAILED"
            else -> if (swipeSync(x, dm.heightPixels * .75f, x, dm.heightPixels * .35f, 350)) "SWIPED_DOWN" else "SCROLL_FAILED"
        }
    }

    suspend fun tap(x: Int, y: Int): String {
        val hit = NodeTree.snapshot(service.rootInActiveWindow, 500)
            .filter { it.bounds.contains(x, y) }
            .minByOrNull { (it.bounds.width().coerceAtLeast(1) * it.bounds.height().coerceAtLeast(1)) }
        if (hit == null && !com.hemant.localoperator.model.Prefs.allowRisky(service)) {
            return "COORDINATE_TAP_BLOCKED:No accessible element at coordinate while sensitive actions are disabled"
        }
        val label = hit?.text?.takeIf { it.isNotBlank() } ?: hit?.description
        val safety = SafetyPolicy.mayClick(service, label)
        if (!safety.first) return "SENSITIVE_ACTION_BLOCKED:${safety.second}"
        return if (gesture(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 80)) "TAPPED:$x,$y" else "TAP_FAILED:$x,$y"
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): String {
        return if (gesture(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs.coerceIn(100, 1800))) {
            "SWIPED:$x1,$y1->$x2,$y2"
        } else "SWIPE_FAILED"
    }

    fun back(): String = if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) "BACK_OK" else "BACK_FAILED"
    fun home(): String = if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) "HOME_OK" else "HOME_FAILED"

    private fun clickNodeOrParent(start: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = start
        repeat(6) {
            if (n?.isClickable == true && n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            n = n?.parent
        }
        val r = android.graphics.Rect().also { start.getBoundsInScreen(it) }
        if (!r.isEmpty) return swipeSync(r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat(), 80)
        return false
    }

    private fun findFocusedOrEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        return findFirst(root) { it.isEditable }
    }

    private fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (predicate(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun swipeSync(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
            val accepted = service.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }
}
