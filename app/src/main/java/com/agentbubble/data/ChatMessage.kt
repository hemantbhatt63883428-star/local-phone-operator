package com.agentbubble.data

/**
 * One entry of the conversation sent to the model.
 *
 * [role] is "system", "user" or "assistant". That is everything the chat needs: this app sends text
 * and reads text back — no images, no tool calls.
 */
data class ChatMessage(
    val role: String,
    val content: String = ""
)
