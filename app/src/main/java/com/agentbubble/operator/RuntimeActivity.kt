package com.agentbubble.operator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.agentbubble.data.SettingsStore
import com.agentbubble.net.LlmClient
import com.agentbubble.service.BubbleService
import com.agentbubble.ui.ProviderDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Provider capability checks are explicit; a valid key alone is not enough for Chat + Tasks. */
class RuntimeActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var store: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 30, 28, 30) }
        setContentView(ScrollView(this).apply { addView(col) })
        fun text(s: String) = TextView(this).apply {
            text = s; textSize = 16f; setPadding(0, 14, 0, 14); col.addView(this)
        }
        fun button(s: String, action: () -> Unit) {
            col.addView(Button(this).apply { text = s; setOnClickListener { action() } })
        }
        text("AI & phone actions").textSize = 24f
        status = text("")
        updateStatus()
        button("Configure API provider") {
            ProviderDialog.show(this, store, store.activeProvider()) {
                updateStatus(); BubbleService.send(this, BubbleService.ACTION_REFRESH)
            }
        }
        text("Check your selected model separately. Chat needs text responses. Chat + Tasks needs both image input and function calling.")
        button("Check chat") { check("Chat", "chat") { it.checkChat() } }
        button("Check image input") { check("Image input", "image") { it.checkImage() } }
        button("Check tool calling") { check("Tool calling", "tools") { it.checkToolCalling() } }
        text("Chat reads your conversation only. Chat + Tasks sends a fresh screenshot of the current app window and accessible UI text to your selected API provider when you ask it a question or task. Android 14 or newer is needed for screen images. Secure windows may refuse capture. Navigation runs within your request; final send, delete and payment actions require confirmation. Stop cancels the pending API request and blocks further actions.")
        button("Open Accessibility settings") {
            AlertDialog.Builder(this)
                .setTitle("Enable Chat + Tasks?")
                .setMessage("Accessibility lets the app capture the visible app window, read UI elements and perform gestures during a requested Chat + Tasks turn. The image and UI text go to your selected API provider. You can disable access in Android settings.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.show()
        }
        if (Build.VERSION.SDK_INT < 34) text("This Android version supports Chat only; Chat + Tasks screen capture requires Android 14+.")
        text("Runs stop after 40 steps or 10 minutes. Password, PIN and authentication screens require manual handling. For sideloaded APKs, Android may ask you to allow restricted settings before enabling Accessibility.")
        button("Back to settings") { finish() }
    }

    private fun check(label: String, kind: String, action: suspend (LlmClient) -> String) {
        val cfg = store.activeProvider()
        if (cfg == null || cfg.model.isBlank()) {
            status.text = "Choose an API provider and model first."
            return
        }
        scope.launch {
            status.text = "$label: checking…"
            try {
                val answer = action(LlmClient(cfg))
                store.setCapability(cfg, kind, true)
                status.text = "$label: works (${answer.take(80)})"
            } catch (e: Exception) {
                store.setCapability(cfg, kind, false)
                status.text = "$label: failed — ${e.message.orEmpty().take(240)}"
            }
        }
    }

    private fun updateStatus() {
        status.text = "Selected API model: " + (store.activeProvider()?.model?.ifBlank { "none" } ?: "none")
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
