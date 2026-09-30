package com.hemant.localoperator.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hemant.localoperator.agent.AgentRuntime
import com.hemant.localoperator.agent.ChatStore
import com.hemant.localoperator.model.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Chat view owns its collector only while it is on screen; tasks live in AgentRuntime, not in an Activity. */
class ChatPage(context: Context, private val scope: CoroutineScope, private val openModels: () -> Unit) : LinearLayout(context) {
    private val ink = Color.rgb(24, 35, 59)
    private val muted = Color.rgb(104, 118, 145)
    private val blue = Color.rgb(49, 91, 224)
    private val transcript = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
    private val status = TextView(context)
    private val send = TextView(context)
    private val stop = TextView(context)
    private val input = EditText(context)
    private var observer: Job? = null
    private var lastRevision = -1L
    private var lastMode = ""

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(246, 248, 253))
        setPadding(16.dp, 4.dp, 16.dp, 12.dp)

        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(TextView(context).apply {
            text = "CONVERSATION"; textSize = 11f; letterSpacing = .13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(muted)
        }, LayoutParams(0, -2, 1f))
        toolbar.addView(TextView(context).apply {
            text = "Clear chat"; textSize = 13f; setTextColor(blue); gravity = Gravity.CENTER
            setPadding(12.dp, 8.dp, 4.dp, 8.dp)
            setOnClickListener {
                if (AgentRuntime.state.value.running) return@setOnClickListener
                AlertDialog.Builder(context).setTitle("Clear conversation?")
                    .setMessage("This removes the chat history on this device.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ -> ChatStore.clear(context); render() }.show()
            }
        })
        addView(toolbar)

        val mode = TextView(context).apply {
            textSize = 12f; setTextColor(muted); setPadding(0, 0, 0, 8.dp)
            setOnClickListener { openModels() }
        }
        addView(mode)
        scroll.addView(transcript, android.widget.FrameLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(context).apply { orientation = VERTICAL; setPadding(12.dp, 8.dp, 12.dp, 10.dp) }
        footer.background = shape(Color.WHITE, 20.dp.toFloat(), Color.rgb(221, 229, 242))
        status.apply {
            textSize = 12f; setTextColor(muted); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(2.dp, 2.dp, 2.dp, 6.dp)
        }
        footer.addView(status)
        input.apply {
            hint = "Ask me to operate your phone…"
            setHintTextColor(Color.rgb(137, 148, 169)); setTextColor(ink); textSize = 15f
            minLines = 1; maxLines = 4; setPadding(3.dp, 7.dp, 3.dp, 8.dp)
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
            }
        }
        footer.addView(input, LayoutParams(-1, -2))
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(TextView(context).apply {
            text = "Runs on your device or configured API"; textSize = 11f; setTextColor(muted)
        }, LayoutParams(0, -2, 1f))
        stop.apply {
            text = "Stop"; contentDescription = "Stop current task"; textSize = 14f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(186, 57, 68)); gravity = Gravity.CENTER
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            setOnClickListener { AgentRuntime.stop() }
        }
        actions.addView(stop)
        send.apply {
            text = "Run  →"; contentDescription = "Run task"; textSize = 14f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = shape(blue, 14.dp.toFloat())
            setPadding(17.dp, 10.dp, 17.dp, 10.dp)
            setOnClickListener { submit() }
        }
        actions.addView(send)
        footer.addView(actions)
        addView(footer)

        fun update(state: AgentRuntime.RunState) {
            val currentMode = "Planner: ${if (Prefs.plannerProvider(context) == Prefs.PROVIDER_OPENAI) "API" else "Local"}  •  Tap to configure models"
            if (lastMode != currentMode) { mode.text = currentMode; lastMode = currentMode }
            if (lastRevision != state.revision) { render(); lastRevision = state.revision }
            status.text = if (state.running) "●  ${state.status}" else "●  Ready to help"
            status.setTextColor(if (state.running) blue else muted)
            send.isEnabled = !state.running
            send.alpha = if (state.running) .5f else 1f
            stop.visibility = if (state.running) VISIBLE else GONE
        }
        // Initial state also covers a task started from the floating overlay.
        update(AgentRuntime.state.value)
        observer = scope.launch { AgentRuntime.state.collect { update(it) } }
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        if (AgentRuntime.start(context, text)) {
            input.setText("")
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
    }

    private fun render() {
        transcript.removeAllViews()
        val messages = ChatStore.load(context)
        if (messages.isEmpty()) {
            val empty = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(24.dp, 32.dp, 24.dp, 32.dp)
            }
            empty.addView(TextView(context).apply {
                text = "✦"; textSize = 38f; gravity = Gravity.CENTER; setTextColor(blue)
            })
            empty.addView(TextView(context).apply {
                text = "What can I do for you?"; textSize = 21f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(ink); gravity = Gravity.CENTER; setPadding(0, 12.dp, 0, 8.dp)
            })
            empty.addView(TextView(context).apply {
                text = "Describe a task on your phone. I’ll show progress here while I work."
                textSize = 14f; gravity = Gravity.CENTER; setTextColor(muted)
            })
            transcript.addView(empty, LayoutParams(-1, 320.dp))
        } else messages.forEach { message ->
            val isUser = message.role == "user"
            val row = LinearLayout(context).apply { gravity = if (isUser) Gravity.END else Gravity.START }
            val card = LinearLayout(context).apply { orientation = VERTICAL; setPadding(14.dp, 11.dp, 14.dp, 11.dp) }
            card.background = shape(if (isUser) blue else Color.WHITE, 18.dp.toFloat(), if (isUser) null else Color.rgb(227, 233, 244))
            card.addView(TextView(context).apply {
                text = (if (isUser) "YOU" else "OPERATOR") + "  ·  " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.time))
                textSize = 10f; letterSpacing = .07f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isUser) Color.rgb(208, 223, 255) else muted)
            })
            card.addView(TextView(context).apply {
                text = message.text; maxWidth = resources.displayMetrics.widthPixels - 100.dp; textSize = 15f; setTextColor(if (isUser) Color.WHITE else ink)
                setTextIsSelectable(true); setPadding(0, 6.dp, 0, 0)
            })
            row.addView(card, LayoutParams(-2, -2))
            transcript.addView(row, LayoutParams(-1, -2).apply { setMargins(0, 5.dp, 0, 9.dp) })
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDetachedFromWindow() {
        observer?.cancel()
        super.onDetachedFromWindow()
    }

    private fun shape(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != null) setStroke(1.dp, stroke)
    }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
