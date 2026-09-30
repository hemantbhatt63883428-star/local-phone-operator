package com.hemant.localoperator.agent

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/** Tool signatures exposed to the local planner. automaticToolCalling is disabled; ToolExecutor performs them. */
class AndroidToolSchema : ToolSet {
    @Tool(description = "Inspect the current Android accessibility UI tree. Always use this before acting and after navigation to verify the screen.")
    fun inspect_screen(@ToolParam(description = "Maximum UI nodes to return, normally 60 to 100.") max_nodes: Int): String = "schema"

    @Tool(description = "Open an installed Android app by visible app name or package name.")
    fun open_app(@ToolParam(description = "App label such as Telegram, Chrome, Settings, or a package name.") name: String): String = "schema"

    @Tool(description = "Click a visible UI element by its text or accessibility description. Prefer this over coordinate tapping.")
    fun click_text(
        @ToolParam(description = "Visible text or accessibility label to click.") text: String,
        @ToolParam(description = "True for exact text match, false for partial match.") exact: Boolean
    ): String = "schema"

    @Tool(description = "Click an element using the numeric node id from the most recent inspect_screen UI tree.")
    fun click_node(@ToolParam(description = "Node id from inspect_screen, such as 42.") node_id: Int): String = "schema"

    @Tool(description = "Set text in an editable UI node. Password/PIN fields are never auto-filled.")
    fun type_text(
        @ToolParam(description = "Text to enter.") text: String,
        @ToolParam(description = "Editable node id from inspect_screen, or -1 to use focused/first editable field.") node_id: Int,
        @ToolParam(description = "Append to existing text instead of replacing it.") append: Boolean
    ): String = "schema"

    @Tool(description = "Scroll the current page. Use down to reveal content below and up to return toward earlier content.")
    fun scroll(@ToolParam(description = "One of down, up, left, right.") direction: String): String = "schema"

    @Tool(description = "Tap screen coordinates only when accessibility nodes cannot represent the target. Sensitive actions may be blocked.")
    fun tap(
        @ToolParam(description = "X coordinate in screen pixels.") x: Int,
        @ToolParam(description = "Y coordinate in screen pixels.") y: Int
    ): String = "schema"

    @Tool(description = "Swipe between two screen coordinates.")
    fun swipe(
        @ToolParam(description = "Start X in pixels.") x1: Int,
        @ToolParam(description = "Start Y in pixels.") y1: Int,
        @ToolParam(description = "End X in pixels.") x2: Int,
        @ToolParam(description = "End Y in pixels.") y2: Int,
        @ToolParam(description = "Gesture duration in milliseconds, normally 200 to 700.") duration_ms: Int
    ): String = "schema"

    @Tool(description = "Press Android Back.")
    fun back(): String = "schema"

    @Tool(description = "Press Android Home.")
    fun home(): String = "schema"

    @Tool(description = "Wait briefly for an app or screen transition to finish.")
    fun wait_ms(@ToolParam(description = "Milliseconds to wait, maximum 3000.") milliseconds: Int): String = "schema"

    @Tool(description = "Use the optional local vision model to inspect the current screenshot when the accessibility tree is insufficient. Returns a textual visual description.")
    fun inspect_visual(@ToolParam(description = "Specific visual question, e.g. locate the unlabeled download icon and report its coordinates.") question: String): String = "schema"

    @Tool(description = "Finish the task after verifying the requested result.")
    fun finish(@ToolParam(description = "Short summary of what was completed and any limitations.") summary: String): String = "schema"
}
