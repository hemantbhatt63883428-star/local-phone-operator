package com.agentbubble.agent

import com.agentbubble.data.ChatMessage
import com.agentbubble.data.ProviderConfig
import com.agentbubble.data.SettingsStore
import com.agentbubble.net.LlmClient

/**
 * Plain chat with one OpenAI-compatible model. Nothing else.
 *
 * No tools, no screen reading, no automation: the model reads the conversation and writes back.
 * The floating bubble is only a nicer window for this.
 */
class AgentEngine(
    private val cfg: ProviderConfig,
    private val store: SettingsStore,
    private val listener: Listener
) {

    /** Kept small on purpose: this is a chat, not a log. */
    interface Listener {
        fun onAssistantText(text: String)
        fun onStatus(text: String)
        fun onError(message: String)
    }

    private val client = LlmClient(cfg)
    private val messages = ArrayList<ChatMessage>()

    /** How many turns (user + assistant) to keep in context. */
    private val maxMessages = 24

    val historySize: Int get() = messages.size

    fun clearHistory() {
        messages.clear()
    }

    /** Continues a conversation that was loaded from disk. */
    fun loadHistory(turns: List<Pair<String, String>>) {
        messages.clear()
        messages.add(ChatMessage(role = "system", content = systemPrompt()))
        turns.forEach { (role, content) ->
            if (content.isNotBlank()) messages.add(ChatMessage(role = role, content = content))
        }
    }

    /** Say something and get the reply. */
    suspend fun send(userText: String) {
        if (cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
            listener.onError("No model selected. Open Settings → Your AI → Choose model.")
            return
        }
        if (messages.isEmpty()) {
            messages.add(ChatMessage(role = "system", content = systemPrompt()))
        }
        messages.add(ChatMessage(role = "user", content = userText))

        listener.onStatus("Thinking…")
        try {
            val resp = client.chat(outgoing())
            val text = resp.text.trim()
            if (text.isEmpty()) {
                listener.onError("The model sent an empty reply. Try again.")
                return
            }
            messages.add(ChatMessage(role = "assistant", content = text))
            trim()
            listener.onAssistantText(text)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            messages.removeAt(messages.size - 1)
            throw cancel
        } catch (t: Exception) {
            // do not leave a half-conversation behind
            messages.removeAt(messages.size - 1)
            listener.onError(friendly(t))
        } finally {
            listener.onStatus("")
        }
    }

    private fun outgoing(): List<ChatMessage> = messages.toList()

    private fun trim() {
        while (messages.size > maxMessages) {
            // never drop the system prompt (index 0)
            messages.removeAt(1)
        }
    }

    private fun systemPrompt(): String = buildString {
        append(store.systemPrompt.trim().ifBlank { "You are a helpful assistant." })
        append("\n\nYou are answering inside a small floating chat window on the user's phone. ")
        append("Keep answers short and clear. Reply in the same language the user writes in ")
        append("(Hinglish is fine).")
    }

    private fun friendly(t: Throwable): String {
        val m = t.message ?: "unknown error"
        return when {
            m.contains("401") || m.contains("403") -> "The API key was rejected. Check it in Settings."
            m.contains("404") -> "Model \"${cfg.model}\" was not found on this provider. Pick another one."
            m.contains("429") -> "Rate limited or out of credit. Try again in a moment."
            m.contains("Network") || m.contains("timeout", true) -> "No connection to the provider."
            else -> "Request failed: $m"
        }
    }
}
