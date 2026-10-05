package com.agentbubble.operator

import org.json.JSONArray

/** Compact, model-visible memory for long collection/download workflows. */
internal class TaskLedger(private val maxItems: Int = 120) {
    private data class Item(
        val name: String,
        var details: String,
        var status: String = "found",
        var evidence: String = ""
    )

    private val items = linkedMapOf<String, Item>()

    fun remember(input: JSONArray): String {
        var added = 0
        var existing = 0
        for (i in 0 until input.length()) {
            val value = input.opt(i)
            val name = when (value) {
                is String -> value
                else -> input.optJSONObject(i)?.optString("name").orEmpty()
            }.trim().take(240)
            if (name.isBlank()) continue
            val details = input.optJSONObject(i)?.optString("details").orEmpty().trim().take(500)
            val key = key(name)
            val old = items[key]
            if (old != null) {
                existing++
                if (details.isNotBlank()) old.details = details
            } else if (items.size < maxItems) {
                items[key] = Item(name, details)
                added++
            }
        }
        return "MEMORY_UPDATED added=$added duplicates=$existing total=${items.size}"
    }

    fun update(name: String, status: String, evidence: String): String {
        require(status in STATUSES) { "Unsupported item status: $status" }
        val cleanName = name.trim().take(240)
        require(cleanName.isNotBlank()) { "Item name is required" }
        val item = items[key(cleanName)] ?: return "ITEM_NOT_FOUND:$cleanName"
        item.status = status
        item.evidence = evidence.trim().take(500)
        return "ITEM_UPDATED name=${item.name} status=${item.status}"
    }

    fun total(): Int = items.size

    fun notDownloadedNames(): List<String> = items.values
        .filter { it.status != "downloaded" }
        .map { it.name }

    fun promptBlock(): String {
        if (items.isEmpty()) return "TASK_MEMORY: no items recorded yet."
        val counts = STATUSES.associateWith { s -> items.values.count { it.status == s } }
            .filterValues { it > 0 }
            .entries.joinToString { "${it.key}=${it.value}" }
        val rows = items.values.toList().takeLast(60).joinToString("\n") {
            "- [${it.status}] ${it.name}" +
                (if (it.details.isBlank()) "" else " | ${it.details.take(180)}") +
                (if (it.evidence.isBlank()) "" else " | evidence=${it.evidence.take(120)}")
        }
        return "TASK_MEMORY total=${items.size} $counts\n$rows"
    }

    companion object {
        val STATUSES = setOf("found", "attempted", "downloaded", "failed", "skipped")

        private fun key(value: String): String = value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }
}

internal object TaskPolicy {
    private val bulkWords = Regex(
        "\\b(all|every|each|files?|documents?|download|saare|saree|sari|sabhi|sab|har)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isBulkTask(task: String): Boolean = bulkWords.containsMatchIn(task)
    fun stepLimit(task: String): Int = if (isBulkTask(task)) 120 else 40
    fun timeoutMs(task: String): Long = if (isBulkTask(task)) 30 * 60_000L else 10 * 60_000L
}
