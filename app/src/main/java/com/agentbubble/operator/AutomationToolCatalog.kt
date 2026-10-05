package com.agentbubble.operator

import org.json.JSONArray
import org.json.JSONObject

/**
 * The allow-listed API function surface.
 *
 * There is no shell, file deletion, arbitrary intent, or password entry.
 */
object AutomationToolCatalog {
    val names = setOf(
        "inspect_screen", "open_app", "click_text", "click_node", "type_text", "scroll",
        "tap", "swipe", "back", "home", "wait_ms", "remember_items",
        "update_item_status", "finish"
    )

    fun isMutation(name: String): Boolean = name !in setOf(
        "inspect_screen", "wait_ms", "remember_items", "update_item_status", "finish"
    )

    fun apiSchema(): JSONArray = JSONArray().apply {
        put(tool("inspect_screen", "Inspect the current Android accessibility UI tree before acting and after navigation.",
            obj("max_nodes", "integer", "Maximum nodes, normally 60 to 120"), listOf("max_nodes")))
        put(tool("open_app", "Open an installed Android app by exact visible label or package name.",
            obj("name", "string", "App label or package"), listOf("name")))
        put(tool("click_text", "Click visible text or accessibility label. Prefer this over coordinates.", properties(
            "text" to spec("string", "Visible text or accessibility label"),
            "exact" to spec("boolean", "Require an exact match")
        ), listOf("text", "exact")))
        put(tool("click_node", "Click a UI node from the latest inspect_screen result.",
            obj("node_id", "integer", "Node id"), listOf("node_id")))
        put(tool("type_text", "Enter text into an editable node. Password and PIN fields are blocked.", properties(
            "text" to spec("string", "Text to enter"),
            "node_id" to spec("integer", "Editable node id, or -1 for focused editable field"),
            "append" to spec("boolean", "Append instead of replacing")
        ), listOf("text", "node_id", "append")))
        put(tool("scroll", "Scroll the current page.", enumObj("direction", listOf("down", "up", "left", "right"), "Direction"), listOf("direction")))
        put(tool("tap", "Tap an image location only when text/node controls cannot represent it. Coordinate taps require owner confirmation.", properties(
            "x" to spec("integer", "X pixel in the supplied image"), "y" to spec("integer", "Y pixel in the supplied image")
        ), listOf("x", "y")))
        put(tool("swipe", "Swipe the screen to reveal or move through content. Avoid sensitive controls.", properties(
            "x1" to spec("integer", "Start X image pixel"), "y1" to spec("integer", "Start Y image pixel"),
            "x2" to spec("integer", "End X image pixel"), "y2" to spec("integer", "End Y image pixel"),
            "duration_ms" to spec("integer", "100 to 1800 milliseconds")
        ), listOf("x1", "y1", "x2", "y2", "duration_ms")))
        put(tool("back", "Press Android Back.", JSONObject(), emptyList()))
        put(tool("home", "Press Android Home. This may leave the task app.", JSONObject(), emptyList()))
        put(tool("wait_ms", "Wait briefly for a screen transition.", obj("milliseconds", "integer", "50 to 3000"), listOf("milliseconds")))
        val item = JSONObject().put("type", "object").put("properties", properties(
            "name" to spec("string", "Exact visible file/item name; include enough text to identify it"),
            "details" to spec("string", "Visible date, type, size or matching reason")
        )).put("required", JSONArray(listOf("name"))).put("additionalProperties", false)
        put(tool("remember_items", "Store all matching items visible on this page before scrolling. Memory deduplicates normalized names.",
            properties("items" to JSONObject().put("type", "array").put("items", item).put("maxItems", 30)),
            listOf("items")))
        put(tool("update_item_status", "Update a remembered item after an attempted action or fresh visible verification. Never mark downloaded from a click result alone.", properties(
            "name" to spec("string", "Remembered item name"),
            "status" to spec("string", "found, attempted, downloaded, failed or skipped")
                .put("enum", JSONArray(TaskLedger.STATUSES.toList())),
            "evidence" to spec("string", "Exact visible UI text proving downloaded status; otherwise empty")
        ), listOf("name", "status", "evidence")))
        put(tool("finish", "Finish after observing the result. Give exact visible evidence text, or leave evidence empty if unverified.", properties(
            "summary" to spec("string", "What happened and what remains"),
            "evidence" to spec("string", "Exact text from the latest UI tree proving completion, or empty")
        ), listOf("summary", "evidence")))
    }

    private fun tool(name: String, description: String, props: JSONObject, required: List<String>): JSONObject {
        val params = JSONObject().put("type", "object").put("properties", props).put("additionalProperties", false)
        if (required.isNotEmpty()) params.put("required", JSONArray(required))
        return JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", name).put("description", description).put("parameters", params))
    }
    private fun obj(name: String, type: String, description: String) = properties(name to spec(type, description))
    private fun enumObj(name: String, values: List<String>, description: String) =
        properties(name to spec("string", description).put("enum", JSONArray(values)))
    private fun properties(vararg pairs: Pair<String, JSONObject>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
    private fun spec(type: String, description: String) = JSONObject().put("type", type).put("description", description)
}
