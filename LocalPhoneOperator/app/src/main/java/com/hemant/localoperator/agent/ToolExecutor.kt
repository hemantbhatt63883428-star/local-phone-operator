package com.hemant.localoperator.agent

import android.content.Context
import com.google.ai.edge.litertlm.ToolCall
import com.hemant.localoperator.accessibility.AccessibilityBridge
import com.hemant.localoperator.accessibility.DeviceController
import com.hemant.localoperator.model.VisionRuntime
import kotlinx.coroutines.delay

object ToolExecutor {
    data class Result(val text: String, val finished: Boolean = false)

    suspend fun execute(context: Context, call: ToolCall): Result = execute(context, call.name, call.arguments)

    suspend fun execute(context: Context, name: String, args: Map<String, Any?>): Result {
        val service = AccessibilityBridge.service ?: return Result("ACCESSIBILITY_SERVICE_NOT_CONNECTED")
        val d = DeviceController(service)
        fun s(n: String, default: String = "") = args[n]?.toString() ?: default
        fun i(n: String, default: Int = 0) = (args[n] as? Number)?.toInt() ?: args[n]?.toString()?.toIntOrNull() ?: default
        fun b(n: String, default: Boolean = false) = (args[n] as? Boolean) ?: args[n]?.toString()?.toBooleanStrictOrNull() ?: default

        return when (name) {
            "inspect_screen" -> Result(d.inspect(i("max_nodes", 220)))
            "open_app" -> Result(d.openApp(s("name")))
            "click_text" -> Result(d.clickText(s("text"), b("exact", false)))
            "click_node" -> Result(d.clickNode(i("node_id", -1)))
            "type_text" -> Result(d.setText(i("node_id", -1), s("text"), b("append", false)))
            "scroll" -> Result(d.scroll(s("direction", "down")))
            "tap" -> Result(d.tap(i("x"), i("y")))
            "swipe" -> Result(d.swipe(i("x1"), i("y1"), i("x2"), i("y2"), i("duration_ms", 350).toLong()))
            "back" -> Result(d.back())
            "home" -> Result(d.home())
            "wait_ms" -> {
                val ms = i("milliseconds", 500).coerceIn(50, 3000)
                delay(ms.toLong())
                Result("WAITED:${ms}ms")
            }
            "inspect_visual" -> {
                val png = service.captureScreenshotPng() ?: return Result("SCREENSHOT_UNAVAILABLE_OR_SECURE_WINDOW")
                Result(VisionRuntime.describe(context, png, s("question", "Describe the current screen and interactive controls.")))
            }
            "finish" -> Result("FINISHED:${s("summary", "Task completed")}", finished = true)
            else -> Result("UNKNOWN_TOOL:$name")
        }
    }
}
