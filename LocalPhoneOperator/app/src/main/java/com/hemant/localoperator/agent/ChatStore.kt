package com.hemant.localoperator.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ChatStore {
    data class ChatMessage(val role: String, val text: String, val time: Long = System.currentTimeMillis())
    private const val PREFS = "operator_chat"
    private const val KEY = "messages"
    private const val MAX = 80

    fun load(context: Context): List<ChatMessage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(ChatMessage(o.optString("role"), o.optString("text"), o.optLong("time")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun append(context: Context, role: String, text: String) {
        val messages = (load(context) + ChatMessage(role, text.take(12000))).takeLast(MAX)
        save(context, messages)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun save(context: Context, list: List<ChatMessage>) {
        val arr = JSONArray()
        list.forEach { m -> arr.put(JSONObject().put("role", m.role).put("text", m.text).put("time", m.time)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
