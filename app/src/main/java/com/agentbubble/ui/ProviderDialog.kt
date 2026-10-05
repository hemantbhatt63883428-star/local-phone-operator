package com.agentbubble.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.agentbubble.data.ProviderConfig
import com.agentbubble.data.SettingsStore
import com.agentbubble.net.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only setup screen that matters: paste an API key, press *Load models*, then **search** the list
 * and pick one. Nothing is hard-coded — the list always comes from the provider itself.
 */
object ProviderDialog {

    fun show(activity: Context, store: SettingsStore, existing: ProviderConfig?, onSaved: () -> Unit) {
        val ctx = activity
        val dlg = Dialog(ctx)
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)
        col.setBackgroundColor(Color.parseColor("#151822"))

        fun label(t: String): TextView = TextView(ctx).apply {
            text = t
            setTextColor(Color.parseColor("#8B94A3"))
            textSize = 12f
        }

        fun field(hint: String, value: String): EditText = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            setTextColor(Color.parseColor("#E9EDF3"))
            textSize = 14f
            setBackgroundColor(Color.parseColor("#1D2130"))
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }

        col.addView(TextView(ctx).apply {
            text = "Your AI"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 18f
        })
        col.addView(label("Any OpenAI-compatible endpoint. OpenRouter works out of the box."))

        col.addView(label("Base URL"))
        val baseUrl = field("https://openrouter.ai/api/v1", existing?.baseUrl ?: ProviderConfig.DEFAULT_BASE_URL)
        col.addView(baseUrl)

        col.addView(label("API key"))
        val apiKey = field("sk-…  (pasted key, stored only on this phone)", existing?.apiKey ?: "")
        apiKey.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        col.addView(apiKey)

        col.addView(label("Name (optional)"))
        val name = field("My AI", existing?.name ?: "OpenRouter")
        col.addView(name)

        val loadBtn = Button(ctx).apply {
            text = "Load models"
            setTextColor(Color.parseColor("#7C86FF"))
        }
        col.addView(loadBtn)

        col.addView(label("Search the model list"))
        val search = field("type to filter, e.g. gemini / gpt / qwen", "")
        col.addView(search)

        val status = TextView(ctx).apply {
            setTextColor(Color.parseColor("#8B94A3"))
            textSize = 12f
        }
        col.addView(status)

        val listBox = LinearLayout(ctx)
        listBox.orientation = LinearLayout.VERTICAL
        val listScroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (260 * ctx.resources.displayMetrics.density).toInt()
            )
            addView(listBox)
        }
        col.addView(listScroll)

        col.addView(label("Selected model"))
        val pickedLabel = TextView(ctx).apply {
            setTextColor(Color.parseColor("#31C48D"))
            textSize = 13f
            text = existing?.model?.ifBlank { "(none yet)" } ?: "(none yet)"
        }
        col.addView(pickedLabel)

        var picked = existing?.model.orEmpty()
        var all: List<String> = emptyList()
        var scope: CoroutineScope? = null
        var job: Job? = null

        fun render(filter: String) {
            listBox.removeAllViews()
            val f = filter.trim().lowercase()
            val shown = if (f.isEmpty()) all else all.filter { it.lowercase().contains(f) }
            if (all.isEmpty()) {
                status.text = "Press “Load models” to fetch the list from your provider."
                return
            }
            status.text = when {
                shown.isEmpty() -> "Nothing matches “$filter”."
                shown.size == all.size -> "${all.size} models available."
                else -> "${shown.size} of ${all.size} models match “$filter”."
            }
            shown.take(300).forEach { id ->
                val row = TextView(ctx).apply {
                    text = (if (id == picked) "● " else "○ ") + id
                    setTextColor(Color.parseColor(if (id == picked) "#31C48D" else "#E9EDF3"))
                    textSize = 12f
                    setPadding(8, 14, 8, 14)
                    isClickable = true
                    setOnClickListener {
                        picked = id
                        pickedLabel.text = id
                        render(search.text.toString())
                    }
                }
                listBox.addView(row)
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadBtn.setOnClickListener {
            val url = baseUrl.text.toString().trim()
            val key = apiKey.text.toString().trim()
            if (!url.startsWith("https://")) {
                toast(ctx, "Enter an HTTPS API base URL")
                return@setOnClickListener
            }
            if (key.isBlank()) {
                toast(ctx, "Paste your API key first")
                return@setOnClickListener
            }
            status.text = "Loading models…"
            val probe = ProviderConfig(
                id = existing?.id ?: "probe",
                name = name.text.toString().trim(),
                baseUrl = url,
                apiKey = key,
                model = ""
            )
            scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)
            job?.cancel()
            job = scope!!.launch {
                try {
                    val models = withContext(Dispatchers.IO) { LlmClient(probe).listModels() }
                    all = models.map { it.id }
                    render(search.text.toString())
                } catch (t: Throwable) {
                    all = emptyList()
                    render("")
                    status.text = "Could not load models: " + (t.message ?: "unknown error")
                }
            }
        }

        if (existing?.model?.isNotBlank() == true) render("")

        val save = Button(ctx).apply {
            text = "Save"
            setTextColor(Color.parseColor("#25C26E"))
        }
        col.addView(save)
        val cancel = Button(ctx).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#8B94A3"))
        }
        col.addView(cancel)

        cancel.setOnClickListener { dlg.dismiss() }
        save.setOnClickListener {
            val url = baseUrl.text.toString().trim()
            val key = apiKey.text.toString().trim()
            if (!url.startsWith("https://") || key.isBlank()) {
                toast(ctx, "HTTPS base URL and API key are required")
                return@setOnClickListener
            }
            if (picked.isBlank()) {
                toast(ctx, "Load the models and pick one from the list")
                return@setOnClickListener
            }
            val cfg = ProviderConfig(
                id = existing?.id?.takeIf { it.isNotBlank() }
                    ?: ("p" + System.currentTimeMillis()),
                name = name.text.toString().trim().ifBlank { "My AI" },
                baseUrl = url,
                apiKey = key,
                model = picked
            )
            store.upsertProvider(cfg)
            store.setActiveProvider(cfg.id)
            store.setPickedModel(cfg.id, picked)
            job?.cancel()
            onSaved()
            dlg.dismiss()
            toast(ctx, "Saved — you are chatting with $picked")
        }

        val rootScroll = ScrollView(ctx).apply {
            addView(col)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        dlg.setContentView(rootScroll)
        dlg.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.94).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dlg.show()
    }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
