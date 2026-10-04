package com.agentbubble.operator

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object NodeTree {
    data class NodeRecord(
        val id: Int,
        val text: String,
        val description: String,
        val className: String,
        val viewId: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val password: Boolean,
        val bounds: Rect
    )

    /**
     * IDs are raw breadth-first traversal IDs, but only meaningful nodes are emitted.
     * This keeps prompts small while click_node(id) can still resolve the same raw ID later.
     */
    fun snapshot(root: AccessibilityNodeInfo?, maxNodes: Int = 100): List<NodeRecord> {
        if (root == null) return emptyList()
        val out = ArrayList<NodeRecord>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var rawId = 0
        var visited = 0
        while (queue.isNotEmpty() && out.size < maxNodes && visited < 1200) {
            val node = queue.removeFirst()
            val currentId = rawId++
            visited++
            val rawText = node.text?.toString().orEmpty()
            val rawDesc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val meaningful = rawText.isNotBlank() || rawDesc.isNotBlank() || viewId.isNotBlank() ||
                node.isClickable || node.isEditable || node.isScrollable
            if (meaningful) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val text = if (node.isPassword) "<redacted_password>" else rawText
                val desc = if (node.isPassword) "<redacted_password>" else rawDesc
                out += NodeRecord(
                    id = currentId,
                    text = text.take(120),
                    description = desc.take(120),
                    className = node.className?.toString().orEmpty().substringAfterLast('.').take(48),
                    viewId = viewId.substringAfterLast('/').take(60),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    password = node.isPassword,
                    bounds = bounds
                )
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    fun render(root: AccessibilityNodeInfo?, maxNodes: Int = 100): String {
        val nodes = snapshot(root, maxNodes)
        if (nodes.isEmpty()) return "NO_ACCESSIBLE_NODES"
        return buildString {
            appendLine("UI nodes=${nodes.size}")
            nodes.forEach { n ->
                append("#${n.id}")
                if (n.text.isNotBlank()) append(" t=${quote(n.text)}")
                if (n.description.isNotBlank()) append(" d=${quote(n.description)}")
                if (n.viewId.isNotBlank()) append(" v=${n.viewId}")
                val flags = buildString {
                    if (n.clickable) append('C')
                    if (n.editable) append('E')
                    if (n.scrollable) append('S')
                    if (n.password) append('P')
                }
                if (flags.isNotEmpty()) append(" f=$flags")
                append(" b=${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}")
                appendLine()
            }
        }
    }

    fun findById(root: AccessibilityNodeInfo?, wanted: Int): AccessibilityNodeInfo? {
        if (root == null || wanted < 0) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var rawId = 0
        var visited = 0
        while (queue.isNotEmpty() && visited < 1200) {
            val node = queue.removeFirst()
            if (rawId == wanted) return node
            rawId++
            visited++
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun quote(s: String) = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")}\""
}
