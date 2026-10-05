package com.agentbubble.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line of a stored conversation. */
data class Turn(val role: String, val content: String)

/** A saved conversation: tied to one provider + one model, so each model keeps its own history. */
data class Session(
    var id: String,
    var providerId: String,
    var model: String,
    var title: String,
    var updatedAt: Long,
    val messages: MutableList<Turn> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("r", it.role).put("c", it.content)) }
        return JSONObject()
            .put("id", id)
            .put("providerId", providerId)
            .put("model", model)
            .put("title", title)
            .put("updatedAt", updatedAt)
            .put("messages", arr)
    }

    companion object {
        fun from(o: JSONObject): Session {
            val list = mutableListOf<Turn>()
            val arr = o.optJSONArray("messages")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    list.add(Turn(m.optString("r", "user"), m.optString("c", "")))
                }
            }
            return Session(
                id = o.optString("id"),
                providerId = o.optString("providerId"),
                model = o.optString("model"),
                title = o.optString("title", "New chat"),
                updatedAt = o.optLong("updatedAt", 0L),
                messages = list
            )
        }
    }
}

/**
 * Chat history on disk, one file, grouped by provider + model. Nothing leaves the phone.
 * Old sessions are trimmed so the file can never grow without bound.
 */
object SessionStore {

    private const val FILE = "sessions.json"
    private const val MAX_SESSIONS = 40
    private const val MAX_TURNS = 200

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun all(ctx: Context): MutableList<Session> {
        val out = mutableListOf<Session>()
        try {
            val f = file(ctx)
            if (!f.exists()) return out
            val arr = JSONArray(android.util.AtomicFile(f).openRead().bufferedReader().use { it.readText() })
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(Session.from(it)) }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    @Synchronized
    fun saveAll(ctx: Context, list: List<Session>) {
        try {
            val arr = JSONArray()
            list.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS).forEach { arr.put(it.toJson()) }
            val f = file(ctx)
            f.parentFile?.mkdirs()
            val atomic = android.util.AtomicFile(f)
            val stream = atomic.startWrite()
            try {
                stream.write(arr.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (e: Exception) { atomic.failWrite(stream); throw e }
        } catch (_: Throwable) {
        }
    }

    /** Sessions of one model, newest first. */
    fun forModel(ctx: Context, providerId: String, model: String): List<Session> =
        all(ctx).filter { it.providerId == providerId && it.model == model }
            .sortedByDescending { it.updatedAt }

    /** The conversation to continue for this model — created when there is none yet. */
    fun current(ctx: Context, providerId: String, model: String): Session =
        forModel(ctx, providerId, model).firstOrNull() ?: newSession(providerId, model)

    fun newSession(providerId: String, model: String): Session = Session(
        // a random suffix: two chats created in the same millisecond must not merge into one
        id = "s" + System.currentTimeMillis() + "-" +
            Integer.toHexString(java.util.Random().nextInt(0x10000)),
        providerId = providerId,
        model = model,
        title = "New chat",
        updatedAt = System.currentTimeMillis()
    )

    /** Adds a line and keeps the file small; the title comes from the first thing the user said. */
    fun append(ctx: Context, session: Session, role: String, content: String) {
        if (content.isBlank()) return
        session.messages.add(Turn(role, content))
        while (session.messages.size > MAX_TURNS) session.messages.removeAt(0)
        if (session.title == "New chat" && role == "user") {
            session.title = content.trim().replace(Regex("\\s+"), " ").take(38)
        }
        session.updatedAt = System.currentTimeMillis()
        saveAll(ctx, merge(ctx, session))
    }

    private fun merge(ctx: Context, session: Session): List<Session> {
        val list = all(ctx)
        val idx = list.indexOfFirst { it.id == session.id }
        if (idx >= 0) list[idx] = session else list.add(session)
        return list
    }

    fun delete(ctx: Context, id: String) {
        saveAll(ctx, all(ctx).filter { it.id != id })
    }

    fun exportText(session: Session): String = buildString {
        appendLine("Local Phone Operator chat")
        appendLine("Title: ${session.title}")
        appendLine("Provider: ${session.providerId}")
        appendLine("Model: ${session.model}")
        appendLine("Last updated: ${stamp(session.updatedAt)}")
        appendLine()
        session.messages.forEachIndexed { index, turn ->
            val speaker = when (turn.role.lowercase()) {
                "user" -> "USER"
                "assistant" -> "AI"
                else -> turn.role.uppercase()
            }
            appendLine("[$speaker]")
            appendLine(turn.content)
            if (index != session.messages.lastIndex) appendLine()
        }
    }

    /**
     * Writes one complete conversation as a readable text file. Android 13+ MediaStore needs no
     * broad storage permission and leaves the file in Downloads for attachment to another chat.
     */
    fun exportToDownloads(ctx: Context, session: Session): String {
        val safeTitle = session.title.trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "chat" }
        val whenSaved = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "LocalPhoneOperator-$safeTitle-$whenSaved.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LocalPhoneOperator")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android could not create the download file")
        try {
            ctx.contentResolver.openOutputStream(uri, "w")?.use {
                it.write(exportText(session).toByteArray(Charsets.UTF_8))
            } ?: error("Android could not open the download file")
            ctx.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return fileName
        } catch (t: Throwable) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            throw t
        }
    }

    fun clearAll(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (_: Throwable) {
        }
    }

    /** "12:40 today · 8 messages" — follows the phone's own locale. */
    fun stamp(updatedAt: Long): String = try {
        val sdf = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        sdf.format(Date(updatedAt))
    } catch (_: Throwable) {
        ""
    }
}
