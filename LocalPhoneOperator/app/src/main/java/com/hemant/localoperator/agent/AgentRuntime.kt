package com.hemant.localoperator.agent

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.tool
import com.hemant.localoperator.accessibility.AccessibilityBridge
import com.hemant.localoperator.accessibility.DeviceController
import com.hemant.localoperator.model.Prefs
import com.hemant.localoperator.model.SecureSecrets
import com.hemant.localoperator.network.OpenAiCompatClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object AgentRuntime {
    data class RunState(val running: Boolean, val status: String, val revision: Long)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RunState(false, "Ready", 0))
    val state = _state.asStateFlow()
    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val engineLock = Any()
    private var engine: Engine? = null
    private var enginePath: String? = null

    private const val SYSTEM = """You are Local Phone Operator, an Android UI agent acting only for the device owner.
Execute the user's requested ordinary phone task using the available tools.
Rules:
1. Inspect the screen before acting and after important navigation/change. Verify results; never assume a click worked.
2. Prefer accessibility click_text/click_node. Use inspect_visual only when accessibility is insufficient. Raw tap is a fallback.
3. Never ask for, reveal, infer, capture, or auto-enter passwords, PINs, OTPs, recovery codes, biometric data, or authentication secrets.
4. If a tool says SENSITIVE_ACTION_BLOCKED, do not bypass it with coordinates. The user must explicitly enable sensitive actions.
5. Do not bypass CAPTCHA, device/app security, paywalls, admin policy, protected windows, or authentication requirements.
6. For all/every/bulk/search tasks, continue scrolling or paging until you can reasonably verify the end; avoid duplicates.
7. Disambiguate similarly named recipients, groups, apps and files before acting.
8. Call finish only after verifying completion, or clearly state the limitation preventing completion.
9. Do not merely explain a plan when tools can execute it. Act, observe, verify, then continue.
"""

    /** Only one device task may run at a time, even if both the app and bubble submit. */
    fun start(context: Context, task: String): Boolean {
        val prompt = task.trim()
        if (prompt.isEmpty() || !running.compareAndSet(false, true)) return false
        cancelRequested.set(false)
        val app = context.applicationContext
        publish(true, "Starting…", changed = true)
        appScope.launch {
            try {
                runTask(app, prompt) { publish(true, it) }
            } catch (t: Throwable) {
                val message = "Agent error: ${t.message.orEmpty().take(500)}"
                ChatStore.append(app, "assistant", message)
                publish(true, message, changed = true)
            } finally {
                running.set(false)
                publish(false, "Ready", changed = true)
            }
        }
        return true
    }

    fun stop() {
        if (running.get()) {
            cancelRequested.set(true)
            publish(true, "Stopping after the current action…")
        }
    }

    private fun publish(active: Boolean, message: String, changed: Boolean = false) {
        val current = _state.value
        _state.value = RunState(active, message.take(220), current.revision + if (changed) 1 else 0)
    }

    private suspend fun runTask(context: Context, task: String, status: (String) -> Unit) {
        val taskId = UUID.randomUUID().toString().substring(0, 8)
        ChatStore.append(context, "user", task)
        publish(true, "Inspecting device…", changed = true)
        TraceStore.append(context, taskId, "task", "task_started")
        val result = if (AccessibilityBridge.service == null) {
            "Accessibility is off. Open Controls and enable Local Phone Operator to run tasks."
        } else when (Prefs.plannerProvider(context)) {
            Prefs.PROVIDER_OPENAI -> runRemote(context, taskId, task, status)
            else -> {
                val local = runLocal(context, taskId, task, status)
                if (local.startsWith("Agent error:") && Prefs.autoFallback(context) && remoteConfigured(context) && !cancelRequested.get()) {
                    status("Local model failed; switching to API fallback…")
                    TraceStore.append(context, taskId, "fallback", "local_to_remote")
                    runRemote(context, taskId, task, status)
                } else local
            }
        }
        ChatStore.append(context, "assistant", result)
        publish(true, result, changed = true)
    }

    private suspend fun runLocal(context: Context, taskId: String, task: String, status: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val service = AccessibilityBridge.service ?: return@withContext "Accessibility service disconnected."
        val modelPath = Prefs.plannerPath(context) ?: return@withContext "Import a local planner .litertlm model or select API planner."
        if (!File(modelPath).exists()) return@withContext "Planner model file is missing. Re-import it."
        status("Loading local planner…")
        try {
            val e = ensureEngine(context, modelPath)
            val initialUi = DeviceController(service).inspect(90)
            val cfg = ConversationConfig(
                systemInstruction = Contents.of(SYSTEM),
                tools = listOf(tool(AndroidToolSchema())),
                automaticToolCalling = false,
                maxOutputToken = 520
            )
            e.createConversation(cfg).use { conversation ->
                var response = conversation.sendMessage(Message.user("USER TASK:\n$task\n\nCURRENT SCREEN:\n$initialUi\n\nExecute the task."))
                var step = 0
                var noToolTurns = 0
                val maxSteps = Prefs.maxSteps(context)
                while (step < maxSteps) {
                    if (cancelRequested.get()) return@withContext "Stopped by user."
                    val calls = response.toolCalls
                    if (calls.isEmpty()) {
                        val text = response.toString().trim()
                        if (noToolTurns++ >= 1) return@withContext text.ifBlank { "Model stopped without completing the task." }
                        response = conversation.sendMessage(Message.user("If complete call finish(summary); otherwise continue executing with tools."))
                        continue
                    }
                    noToolTurns = 0
                    val toolResponses = ArrayList<Content>()
                    for (call in calls) {
                        if (step++ >= maxSteps) break
                        if (cancelRequested.get()) return@withContext "Stopped by user."
                        status("${call.name} • step $step/$maxSteps")
                        TraceStore.append(context, taskId, "tool_call", call.name)
                        val result = ToolExecutor.execute(context, call)
                        TraceStore.append(context, taskId, "tool_result", traceSafe(call.name, result.text))
                        status(result.text.replace('\n', ' ').take(180))
                        if (result.finished) return@withContext result.text.removePrefix("FINISHED:")
                        toolResponses += Content.ToolResponse(call.name, result.text)
                    }
                    if (toolResponses.isEmpty()) break
                    response = conversation.sendMessage(Message.tool(Contents.of(toolResponses)))
                }
                "Stopped after reaching the $maxSteps-action safety limit. The task may be incomplete."
            }
        } catch (t: Throwable) {
            val msg = "Agent error: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(500)}"
            TraceStore.append(context, taskId, "error", msg)
            msg
        }
    }

    private suspend fun runRemote(context: Context, taskId: String, task: String, status: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val service = AccessibilityBridge.service ?: return@withContext "Accessibility service disconnected."
        val base = Prefs.apiBase(context)
        val model = Prefs.apiPlannerModel(context)
        if (base.isBlank() || model.isBlank()) return@withContext "Configure API Base URL and planner model in Models settings."
        val key = SecureSecrets.getApiKey(context)
        status("Connecting to $model…")
        try {
            val client = OpenAiCompatClient(base, key, model)
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", "USER TASK:\n$task\n\nCURRENT SCREEN:\n${DeviceController(service).inspect(90)}\n\nExecute the task."))
            val tools = OpenAiCompatClient.toolSchema()
            val maxSteps = Prefs.maxSteps(context)
            var step = 0
            var noToolTurns = 0
            while (step < maxSteps) {
                if (cancelRequested.get()) return@withContext "Stopped by user."
                val response = client.chat(messages, tools, 700)
                if (response.toolCalls.isEmpty()) {
                    if (noToolTurns++ >= 1) return@withContext response.text.ifBlank { "Remote model stopped without completing the task." }
                    messages.put(JSONObject().put("role", "assistant").put("content", response.text))
                    messages.put(JSONObject().put("role", "user").put("content", "If complete call finish(summary); otherwise continue executing with tools."))
                    continue
                }
                noToolTurns = 0
                val assistantToolCalls = JSONArray()
                response.toolCalls.forEach { call ->
                    assistantToolCalls.put(JSONObject().put("id", call.id).put("type", "function").put(
                        "function", JSONObject().put("name", call.name).put("arguments", call.rawArguments)
                    ))
                }
                messages.put(JSONObject().put("role", "assistant").put("content", if (response.text.isBlank()) JSONObject.NULL else response.text).put("tool_calls", assistantToolCalls))

                for (call in response.toolCalls) {
                    if (step++ >= maxSteps) break
                    if (cancelRequested.get()) return@withContext "Stopped by user."
                    status("${call.name} • step $step/$maxSteps")
                    TraceStore.append(context, taskId, "tool_call", "remote:${call.name}")
                    val result = ToolExecutor.execute(context, call.name, call.arguments)
                    TraceStore.append(context, taskId, "tool_result", traceSafe(call.name, result.text))
                    status(result.text.replace('\n', ' ').take(180))
                    if (result.finished) return@withContext result.text.removePrefix("FINISHED:")
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", result.text))
                }
            }
            "Stopped after reaching the $maxSteps-action safety limit. The task may be incomplete."
        } catch (t: Throwable) {
            val msg = "Agent error: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(700)}"
            TraceStore.append(context, taskId, "error", msg)
            msg
        }
    }

    private fun remoteConfigured(context: Context): Boolean = Prefs.apiBase(context).isNotBlank() && Prefs.apiPlannerModel(context).isNotBlank()

    private fun traceSafe(name: String, text: String): String = if (name == "inspect_screen" || name == "inspect_visual") {
        "$name:redacted chars=${text.length}"
    } else "$name:${text.take(300)}"

    private fun ensureEngine(context: Context, path: String): Engine {
        synchronized(engineLock) {
            if (engine != null && enginePath == path) return engine!!
            runCatching { engine?.close() }
            val cfg = EngineConfig(
                modelPath = path,
                backend = Backend.CPU(),
                cacheDir = File(context.cacheDir, "litert_planner").apply { mkdirs() }.absolutePath
            )
            val created = Engine(cfg)
            created.initialize()
            engine = created
            enginePath = path
            return created
        }
    }
}
