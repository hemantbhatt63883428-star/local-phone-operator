package com.hemant.localoperator.network

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

class OpenAiCompatClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {
    data class ToolCall(val id: String, val name: String, val arguments: Map<String, Any?>, val rawArguments: String)
    data class Response(val text: String, val toolCalls: List<ToolCall>)

    fun chat(messages: JSONArray, tools: JSONArray? = null, maxTokens: Int = 700): Response {
        require(model.isNotBlank()) { "Remote model name is empty" }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.1)
            .put("max_tokens", maxTokens)
        if (tools != null) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }
        val json = post(body)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException("No choices in API response: ${json.toString().take(500)}")
        val msg = choice.optJSONObject("message") ?: throw IllegalStateException("Missing message in API response")
        val text = extractContent(msg.opt("content"))
        val calls = mutableListOf<ToolCall>()
        val arr = msg.optJSONArray("tool_calls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val f = o.optJSONObject("function") ?: continue
                val raw = f.optString("arguments", "{}")
                calls += ToolCall(
                    id = o.optString("id", "call_$i"),
                    name = f.optString("name"),
                    arguments = jsonObjectToMap(runCatching { JSONObject(raw) }.getOrElse { JSONObject() }),
                    rawArguments = raw
                )
            }
        }
        return Response(text, calls)
    }

    fun vision(prompt: String, png: ByteArray): String {
        require(model.isNotBlank()) { "Remote vision model name is empty" }
        val data = Base64.encodeToString(png, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", "data:image/png;base64,$data")
                )
            )
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        return chat(messages, null, 550).text
    }

    private fun post(body: JSONObject): JSONObject {
        val endpoint = endpoint(baseUrl)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(700)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun endpoint(base: String): String {
        val clean = base.trim().trimEnd('/')
        return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
    }

    private fun extractContent(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val item = value.optJSONObject(i) ?: continue
                if (item.optString("type") == "text") append(item.optString("text"))
            }
        }
        else -> value.toString()
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        obj.keys().forEach { key -> out[key] = jsonToAny(obj.opt(key)) }
        return out
    }

    private fun jsonToAny(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> (0 until v.length()).map { jsonToAny(v.opt(it)) }
        else -> v
    }

    companion object {
        fun toolSchema(): JSONArray = JSONArray().apply {
            put(tool("inspect_screen", "Inspect current Android accessibility UI tree before/after actions.", obj("max_nodes", "integer", "Maximum nodes, usually 60-120"), listOf("max_nodes")))
            put(tool("open_app", "Open an installed app by label or package.", obj("name", "string", "App label or package"), listOf("name")))
            put(tool("click_text", "Click visible text/accessibility label; prefer over coordinates.", properties(
                "text" to spec("string", "Visible text or accessibility label"),
                "exact" to spec("boolean", "Whether to require exact match")
            ), listOf("text", "exact")))
            put(tool("click_node", "Click UI node from inspect_screen.", obj("node_id", "integer", "Node id"), listOf("node_id")))
            put(tool("type_text", "Enter text into editable node. Password/PIN fields are blocked.", properties(
                "text" to spec("string", "Text to enter"),
                "node_id" to spec("integer", "Editable node id or -1"),
                "append" to spec("boolean", "Append instead of replace")
            ), listOf("text", "node_id", "append")))
            put(tool("scroll", "Scroll current page.", objEnum("direction", listOf("down","up","left","right"), "Direction"), listOf("direction")))
            put(tool("tap", "Tap coordinates only as fallback.", properties(
                "x" to spec("integer", "X px"), "y" to spec("integer", "Y px")
            ), listOf("x", "y")))
            put(tool("swipe", "Swipe between screen coordinates.", properties(
                "x1" to spec("integer","Start X"), "y1" to spec("integer","Start Y"),
                "x2" to spec("integer","End X"), "y2" to spec("integer","End Y"),
                "duration_ms" to spec("integer","Duration 100-1800 ms")
            ), listOf("x1","y1","x2","y2","duration_ms")))
            put(tool("back", "Press Android Back.", JSONObject(), emptyList()))
            put(tool("home", "Press Android Home.", JSONObject(), emptyList()))
            put(tool("wait_ms", "Wait for UI transition.", obj("milliseconds","integer","50-3000"), listOf("milliseconds")))
            put(tool("inspect_visual", "Inspect screenshot with configured vision model when accessibility is insufficient.", obj("question","string","Specific visual question"), listOf("question")))
            put(tool("finish", "Finish only after verifying requested result.", obj("summary","string","Completion summary and limitations"), listOf("summary")))
        }

        private fun tool(name: String, description: String, props: JSONObject, required: List<String>): JSONObject {
            val params = JSONObject().put("type", "object").put("properties", props).put("additionalProperties", false)
            if (required.isNotEmpty()) params.put("required", JSONArray(required))
            return JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", name).put("description", description).put("parameters", params))
        }

        private fun obj(name: String, type: String, desc: String) = properties(name to spec(type, desc))
        private fun objEnum(name: String, values: List<String>, desc: String) = properties(name to spec("string", desc).put("enum", JSONArray(values)))
        private fun properties(vararg pairs: Pair<String, JSONObject>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
        private fun spec(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
    }
}
