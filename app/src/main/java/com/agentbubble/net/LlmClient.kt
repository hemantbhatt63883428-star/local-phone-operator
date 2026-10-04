package com.agentbubble.net

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.agentbubble.data.ChatMessage
import com.agentbubble.data.ProviderConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.io.ByteArrayOutputStream

class LlmException(message: String) : Exception(message)

/** One entry of GET /models — only the id matters, the list is shown and searched by it. */
data class ModelEntry(val id: String)

/** What one chat completion returned. */
data class LlmResponse(val text: String)

data class LlmToolCall(val id: String, val name: String, val arguments: JSONObject, val rawArguments: String)
data class LlmToolResponse(val text: String, val toolCalls: List<LlmToolCall>)

/** Minimal OpenAI-compatible chat-completions client. Works with any provider. */
class LlmClient(private val cfg: ProviderConfig) {

    companion object {
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun normalizeBase(url: String): String {
            var u = url.trim()
            if (u.isEmpty()) return u
            while (u.endsWith("/")) u = u.dropLast(1)
            if (u.endsWith("/chat/completions")) u = u.removeSuffix("/chat/completions")
            return u
        }
    }

    private suspend fun execute(request: Request): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use { it.code to (it.body?.string() ?: "") }
                    if (cont.isActive) cont.resume(value)
                } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
            }
        })
    }

    private fun endpoint(path: String): String {
        val base = normalizeBase(cfg.baseUrl)
        val testLoopback = base.startsWith("http://localhost:") || base.startsWith("http://127.0.0.1:")
        if (base.isNotEmpty() && !base.startsWith("https://") && !testLoopback)
            throw LlmException("An HTTPS API base URL is required.")
        return if (base.isEmpty()) "" else "$base$path"
    }

    private fun buildHeaders(b: Request.Builder) {
        b.header("Content-Type", "application/json")
        b.header("Accept", "application/json")
        if (cfg.apiKey.isNotBlank()) b.header("Authorization", "Bearer ${cfg.apiKey.trim()}")
        // Optional OpenRouter attribution headers — harmless for other providers.
        b.header("HTTP-Referer", "https://agentbubble.local")
        b.header("X-Title", "Local Phone Operator")
    }

    private fun messageJson(m: ChatMessage): JSONObject {
        val o = JSONObject()
        o.put("role", m.role)
        o.put("content", m.content)
        return o
    }

    /** One chat completion call. */
    suspend fun chat(history: List<ChatMessage>): LlmResponse = withContext(Dispatchers.IO) {
        if (endpoint("/chat/completions").isEmpty()) throw LlmException("No base URL configured.")

        val messages = JSONArray()
        history.forEach { messages.put(messageJson(it)) }

        val body = JSONObject()
        body.put("model", cfg.model)
        body.put("messages", messages)
        body.put("temperature", 0.2)
        body.put("max_tokens", 1500)

        val builder = Request.Builder()
            .url(endpoint("/chat/completions"))
            .post(body.toString().toRequestBody(JSON))
        buildHeaders(builder)
        val request = builder.build()

        val (code, text) = try {
            execute(request)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            throw LlmException("Network error: ${e.message ?: e.javaClass.simpleName}")
        }

        if (code !in 200..299) {
            val detail = try {
                val err = JSONObject(text)
                val e = err.optJSONObject("error")
                e?.optString("message") ?: err.optString("message").ifBlank { text.take(400) }
            } catch (_: Throwable) {
                text.take(400)
            }
            throw LlmException("HTTP $code from ${cfg.name}: $detail")
        }

        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw LlmException("Bad response (not JSON): ${text.take(200)}")
        }

        val choices = root.optJSONArray("choices")
            ?: throw LlmException("Response has no choices: ${text.take(200)}")
        if (choices.length() == 0) throw LlmException("Model returned an empty response.")
        val choice = choices.getJSONObject(0)
        val msg = choice.optJSONObject("message") ?: JSONObject()

        val textOut = when (val c = msg.opt("content")) {
            null, JSONObject.NULL -> msg.optString("reasoning_content", "")
            is String -> c
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until c.length()) {
                    val part = c.optJSONObject(i) ?: continue
                    sb.append(part.optString("text"))
                }
                sb.toString()
            }
            else -> c.toString()
        }

        LlmResponse(text = textOut)
    }

    /** OpenAI-compatible function/tool call used only by explicit Automation mode. */
    suspend fun chatWithTools(messages: JSONArray, tools: JSONArray): LlmToolResponse = withContext(Dispatchers.IO) {
        if (endpoint("/chat/completions").isEmpty()) throw LlmException("No base URL configured.")
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", messages)
            .put("tools", tools)
            .put("tool_choice", "auto")
            .put("temperature", 0.1)
            .put("max_tokens", 900)
        val request = Request.Builder()
            .url(endpoint("/chat/completions"))
            .post(body.toString().toRequestBody(JSON))
            .also { buildHeaders(it) }
            .build()
        val (code, text) = try { execute(request) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw LlmException("Network error: ${e.message ?: e.javaClass.simpleName}") }
        if (code !in 200..299) throw LlmException("HTTP $code from ${cfg.name}: ${text.take(500)}")
        val root = try { JSONObject(text) } catch (_: Exception) { throw LlmException("Bad tool response: ${text.take(220)}") }
        val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw LlmException("Tool response has no message")
        val content = when (val c = msg.opt("content")) {
            null, JSONObject.NULL -> ""
            is String -> c
            is JSONArray -> buildString {
                for (i in 0 until c.length()) append(c.optJSONObject(i)?.optString("text").orEmpty())
            }
            else -> c.toString()
        }
        val calls = mutableListOf<LlmToolCall>()
        val arr = msg.optJSONArray("tool_calls")
        if (arr != null) for (i in 0 until arr.length()) {
            val call = arr.optJSONObject(i) ?: continue
            val fn = call.optJSONObject("function") ?: continue
            val raw = fn.optString("arguments", "{}")
            val args = try { JSONObject(raw) } catch (_: Exception) { throw LlmException("Model returned invalid arguments for ${fn.optString("name")}") }
            val name = fn.optString("name")
            if (name !in com.agentbubble.operator.AutomationToolCatalog.names) throw LlmException("Unsupported automation tool: $name")
            calls += LlmToolCall(call.optString("id", "call_$i"), name, args, raw)
        }
        LlmToolResponse(content, calls)
    }

    /** Independent checks: a model list or working chat does not prove vision or tool support. */
    suspend fun checkChat(): String = chat(listOf(ChatMessage("user", "Reply with OK."))).text
        .takeIf { it.isNotBlank() } ?: throw LlmException("Chat returned an empty reply.")

    suspend fun checkImage(): String = withContext(Dispatchers.IO) {
        val bitmap = Bitmap.createBitmap(240, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("V42", 32f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 64f; isFakeBoldText = true
        })
        val image = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        bitmap.recycle()
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Read the three-character code in this image. Reply only with the code."))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/png;base64,$image")))
        val body = JSONObject().put("model", cfg.model).put("messages",
            JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", 40)
        val request = Request.Builder().url(endpoint("/chat/completions"))
            .post(body.toString().toRequestBody(JSON)).also { buildHeaders(it) }.build()
        val (code, raw) = execute(request)
        if (code !in 200..299) throw LlmException("Image input rejected: HTTP $code ${raw.take(300)}")
        val answer = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty()
        if (!answer.contains("V42", ignoreCase = true))
            throw LlmException("Image request succeeded but the model did not read the test image.")
        answer
    }

    suspend fun checkToolCalling(): String = withContext(Dispatchers.IO) {
        val fn = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "capability_ping").put("description", "Check function calling")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
        val body = JSONObject().put("model", cfg.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", "Call capability_ping now.")))
            .put("tools", JSONArray().put(fn))
            .put("tool_choice", JSONObject().put("type", "function")
                .put("function", JSONObject().put("name", "capability_ping")))
            .put("max_tokens", 40)
        val request = Request.Builder().url(endpoint("/chat/completions"))
            .post(body.toString().toRequestBody(JSON)).also { buildHeaders(it) }.build()
        val (code, raw) = execute(request)
        if (code !in 200..299) throw LlmException("Tool calling rejected: HTTP $code ${raw.take(300)}")
        val name = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optJSONArray("tool_calls")?.optJSONObject(0)
            ?.optJSONObject("function")?.optString("name")
        if (name != "capability_ping") throw LlmException("Model did not return a function call.")
        "capability_ping"
    }

    /** GET /models — used by the "fetch model list" button. */
    suspend fun listModels(): List<ModelEntry> = withContext(Dispatchers.IO) {
        val url = endpoint("/models")
        if (url.isEmpty()) throw LlmException("No base URL configured.")
        val builder = Request.Builder().url(url).get()
        buildHeaders(builder)
        val request = builder.build()
        val (code, text) = try {
            execute(request)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            throw LlmException("Network error: ${e.message}")
        }
        if (code !in 200..299) throw LlmException("HTTP $code while listing models")
        val out = mutableListOf<ModelEntry>()
        try {
            val arr = JSONObject(text).optJSONArray("data")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    out.add(ModelEntry(id))
                }
            }
        } catch (_: Throwable) {
        }
        if (out.isEmpty()) throw LlmException("No models returned by this endpoint")
        out.sortBy { it.id }
        out
    }
}
