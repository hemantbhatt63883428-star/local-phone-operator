package com.hemant.localoperator.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TraceStore {
    private val lock = Any()

    fun append(context: Context, taskId: String, type: String, message: String) {
        runCatching {
            val dir = File(context.filesDir, "traces").apply { mkdirs() }
            val f = File(dir, "$taskId.jsonl")
            val obj = JSONObject()
                .put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
                .put("type", type)
                .put("message", message.take(12000))
            synchronized(lock) { f.appendText(obj.toString() + "\n") }
        }
    }
}
