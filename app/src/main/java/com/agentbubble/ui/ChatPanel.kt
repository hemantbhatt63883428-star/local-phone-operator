package com.agentbubble.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.agentbubble.agent.AgentEngine
import com.agentbubble.data.Session
import com.agentbubble.data.SessionStore
import com.agentbubble.data.SettingsStore
import com.agentbubble.databinding.ItemMsgAiBinding
import com.agentbubble.databinding.ItemMsgUserBinding
import com.agentbubble.databinding.ViewChatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The floating chat window.
 *
 * Principles this file follows (each one fixed a real complaint):
 *  1. the window itself never moves or resizes by itself — it is a free floating card the *user*
 *     drags around, and the keyboard is absorbed as padding on the inside,
 *  2. the keyboard is asked for explicitly and then re-connected to the input field, otherwise it
 *     appears but the keys go nowhere (…and you had to tap twice),
 *  3. chat history is part of the card (a real list, one per model), not a dialog — a dialog from a
 *     service context cannot be shown at all, which is why the old "saved chats" button looked dead.
 */
class ChatPanel(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    /** true = closed by a tap outside the card (so the same tap cannot reopen it) */
    private val onClose: (Boolean) -> Unit,
    private val onWorkState: (Boolean) -> Unit,
    private val onResize: (Int) -> Unit,
    private val onResizeEnd: (Int, String) -> Unit,
    private val onResizeHeight: (Int) -> Unit,
    private val onResizeHeightEnd: (Int, String) -> Unit,
    /** The measured keyboard height inside this window (0 when closed). */
    private val onKeyboard: (Int) -> Unit,
    /** The user dragged the card: move it by this much (the service clamps and applies it). */
    private val onMove: (Int, Int) -> Unit = { _, _ -> },
    /** The drag is over: remember where the card landed. */
    private val onMoveEnd: () -> Unit = {},
    private val onAction: (String, com.agentbubble.data.Session) -> Unit = { _, _ -> }
) : AgentEngine.Listener {

    private val binding: ViewChatBinding = ViewChatBinding.inflate(LayoutInflater.from(ctx))
    val root: View get() = binding.root

    private companion object {
        /** The window height is unknown (no layout pass yet): never clamp the padding below that. */
        const val NO_HEIGHT_KNOWN = Int.MAX_VALUE / 2

        /** How far a touch may wander before it counts as a drag instead of a tap. */
        const val DRAG_SLOP = 8f

        /** How often the input asks for the keyboard again while it has no real focus. */
        const val KEYBOARD_TRIES = 10
        const val KEYBOARD_RETRY_MS = 90L
    }

    private var engine: AgentEngine? = null
    private var engineKey: String = ""
    private var job: Job? = null
    private var session: Session? = null

    /** Set by the service once the keyboard is really open (guards the tap-outside close). */
    private var keyboardVisible = false
    private var keyboardHasShown = false
    private var keyboardDismissedByUser = false

    /** True while the chat list is on screen instead of the conversation. */
    private var historyOpen = false
    private var actionMode = store.automationMode && Build.VERSION.SDK_INT >= 34

    private var keyboardTries = 0

    /** Set when the card is closed: posted retries must not touch a dead panel. */
    private var destroyed = false

    private val input: EditText get() = binding.input
    private val msgList get() = binding.msgList
    private val scroll get() = binding.scroll

    init {
        // this window is a plain chat: no agents, no screen reading
        binding.chipRow.visibility = View.VISIBLE
        binding.btnChatMode.setOnClickListener {
            if (job?.isActive == true) return@setOnClickListener
            actionMode = false
            store.automationMode = false
            updateModeSourceUi()
        }
        binding.btnAutomationMode.setOnClickListener {
            if (job?.isActive == true) return@setOnClickListener
            actionMode = true
            store.automationMode = true
            updateModeSourceUi()
        }
        binding.btnSetupAi.setOnClickListener {
            ctx.startActivity(Intent(ctx, com.agentbubble.operator.RuntimeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onClose(false)
        }
        updateModeSourceUi()
        binding.btnMic.visibility = View.GONE

        binding.btnCollapse.setOnClickListener { onClose(false) }
        binding.btnNew.setOnClickListener { newChat() }
        binding.btnVision.setOnClickListener { toggleHistory() }
        binding.btnHistoryBack.setOnClickListener { hideHistory() }
        binding.btnHistoryNew.setOnClickListener { newChat() }
        binding.btnSend.setOnClickListener { if (job?.isActive == true) job?.cancel() else send() }

        wireInput()
        wireHeaderDrag()
        wireMoveAndResize()

        // A tap outside the card closes the chat — never while the keyboard is up, because every
        // keyboard key is reported as an "outside" touch and the chat would close on the first letter.
        binding.root.setOnTouchListener { _, ev ->
            if (PanelGeometry.shouldCloseOnTouch(ev.action, keyboardVisible)) {
                onClose(true)
                true
            } else {
                false
            }
        }

        // Report the keyboard height measured inside this window. The window never moves, so this
        // number cannot feed back into its own measurement (that is what used to shake the UI).
        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            val ime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                0
            }
            onKeyboard(ime)
            insets
        }
        binding.root.post { runCatching { binding.root.requestApplyInsets() } }

        // As soon as this overlay window really has the focus, put the caret in the prompt and connect
        // the keyboard to it. Without this the keyboard could appear while the keys still went
        // nowhere (or nowhere until the user tapped a second time).
        binding.root.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) focusPrompt()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun onShown() {
        keyboardHasShown = false
        keyboardDismissedByUser = false
        openCurrentSession()
        binding.root.post {
            applyBubbleWidths()
            scrollDown()
        }
        // A chat window is a text box: the caret belongs in the prompt from the first moment, so the
        // user can simply start typing. The system also raises the keyboard by itself (the window
        // asks for it with SOFT_INPUT_STATE_VISIBLE); these calls are the belt to that braces.
        binding.root.postDelayed({ focusPrompt() }, 120)
        binding.root.postDelayed({ focusPrompt() }, 400)
    }

    fun destroy() {
        destroyed = true
        job?.cancel()
        job = null
        hideKeyboard()
        engine = null
    }

    /** The provider or the model changed in Settings: switch to that model's own conversation. */
    fun refreshProvider() {
        updateModeSourceUi()
        val cfg = store.activeProvider()
        val key = (cfg?.id ?: "") + "|" + (cfg?.model ?: "")
        if (key != engineKey) {
            engine = null
            engineKey = key
            openCurrentSession()   // this refreshes the header as well
        } else {
            refreshHeader()
        }
    }

    private fun updateModeSourceUi() {
        binding.btnChatMode.isEnabled = true
        binding.btnAutomationMode.isEnabled = Build.VERSION.SDK_INT >= 34
        binding.btnChatMode.text = if (!actionMode) "✓ Chat" else "Chat"
        binding.btnAutomationMode.text = if (actionMode) "✓ Chat + Tasks" else "Chat + Tasks"
        binding.btnChatMode.alpha = if (!actionMode) 1f else .72f
        binding.btnAutomationMode.alpha = if (actionMode) 1f else .72f
        input.hint = if (actionMode) "What should I do on your phone?" else "Message…"
    }

    private fun refreshHeader() {
        val cfg = store.activeProvider()
        val model = cfg?.model.orEmpty()
        when {
            cfg == null -> {
                binding.title.text = "Local Phone Operator"
                binding.subtitle.text = "Tap to add your AI"
            }
            model.isBlank() -> {
                binding.title.text = cfg.name.ifBlank { "Your AI" }
                binding.subtitle.text = "No model selected — tap to choose one"
            }
            else -> {
                // a long "vendor/model-name" is split so the useful part is never cut off
                binding.title.text = model.substringAfterLast('/')
                binding.subtitle.text = cfg.name.ifBlank { "AI" } + " · " + model
            }
        }
    }

    // ------------------------------------------------------------------ keyboard

    /**
     * Called by the service with the keyboard height as padding.
     *
     * @param px how much of the card the keyboard covers (0 when it does not overlap at all),
     * @param windowHeightPx the height the window was created with — known exactly, so the padding
     *        never depends on a layout pass that may not have happened yet.
     */
    fun applyKeyboardInset(px: Int, windowHeightPx: Int = 0) {
        val windowH = when {
            windowHeightPx > 0 -> windowHeightPx
            binding.root.height > 0 -> binding.root.height
            else -> NO_HEIGHT_KNOWN
        }
        val pad = PanelGeometry.keyboardPadding(
            windowHeight = windowH,
            keyboardHeight = px,
            minVisiblePx = fixedRowsPx()
        )
        if (binding.root.paddingBottom == pad) return
        binding.root.setPadding(
            binding.root.paddingLeft,
            binding.root.paddingTop,
            binding.root.paddingRight,
            pad
        )
        scrollDown()
    }

    /**
     * The rows that must stay on screen while typing: the header (so the chat is recognisable) and
     * the input row (so the prompt can be seen and tapped). What the keyboard covers is taken out of
     * the message list, which scrolls anyway.
     */
    private fun fixedRowsPx(): Int {
        val density = ctx.resources.displayMetrics.density
        val header = binding.headerRow.height.takeIf { it > 0 } ?: (54 * density).toInt()
        val inputRow = binding.inputRow.height.takeIf { it > 0 } ?: (52 * density).toInt()
        return header + inputRow + (58 * density).toInt()
    }

    /**
     * The service tells us whether the keyboard is really open (also guards the tap-outside close).
     *
     * When it appears, the prompt is re-connected to it one last time: if the IME showed up before the
     * field had focus, the keys would go nowhere until the user tapped again — that is exactly the
     * "I have to tap twice before I can type" complaint.
     */
    fun setKeyboardVisible(visible: Boolean) {
        val appeared = visible && !keyboardVisible
        if (visible) {
            keyboardHasShown = true
            keyboardDismissedByUser = false
        } else if (keyboardHasShown) {
            // The IME Back key hides the keyboard but normally leaves the EditText focused.
            // Remember this explicit dismissal so the focus listener cannot immediately reopen it.
            keyboardDismissedByUser = true
        }
        keyboardVisible = visible
        if (appeared && !historyOpen && !destroyed && !input.hasFocus()) {
            input.requestFocus()
            input.requestFocusFromTouch()
            runCatching {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.restartInput(input)
            }
        }
    }

    /** Used by the service and the tests: is the chat list on screen? */
    fun isHistoryOpen(): Boolean = historyOpen

    /** Test seam: the whole flow a tap on the prompt triggers. */
    fun requestKeyboardForTest() = showKeyboard()

    /** Test seam: the window just took the focus (the same code the focus listener runs). */
    fun windowFocusForTest() = focusPrompt()

    /**
     * An overlay window is never given the keyboard automatically, so it has to be asked for — and
     * once the field has focus the IME must be *re-connected* to it. Without that restart the
     * keyboard can appear while the keys still go nowhere, which is why a second tap used to be
     * needed before typing worked.
     */
    private fun showKeyboard() {
        // Explicit user interaction is the one way to reopen an IME dismissed with Back.
        keyboardDismissedByUser = false
        keyboardTries = 0
        focusPrompt()
    }

    /**
     * Puts the caret in the prompt and makes sure the keyboard is connected to it. This runs when the
     * card opens, when the window takes focus, when the prompt is tapped and whenever the prompt must
     * be ready again — so typing never needs a second tap.
     */
    private fun focusPrompt() {
        if (destroyed || historyOpen || keyboardDismissedByUser) return
        if (!input.hasFocus()) {
            input.requestFocus()
            input.requestFocusFromTouch()
        }
        if (input.selectionStart < 0) input.setSelection(input.text?.length ?: 0)
        askForKeyboard()
    }

    private fun askForKeyboard() {
        if (destroyed || historyOpen || keyboardDismissedByUser) return
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return

        if (!input.hasFocus()) {
            input.requestFocus()
            input.requestFocusFromTouch()
        }
        if (input.selectionStart < 0) input.setSelection(input.text?.length ?: 0)

        val connected = input.hasFocus()
        if (connected) {
            // re-connecting the IME to this field is what makes the very first keypress land in it
            runCatching { imm.restartInput(input) }
            // the modern way to raise the keyboard (API 30+): ask this window's insets controller
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { binding.root.windowInsetsController?.show(WindowInsets.Type.ime()) }
            }
        }
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)

        // The window focus arrives a moment after the card is shown: keep asking until it is real.
        if (!connected && keyboardTries < KEYBOARD_TRIES) {
            keyboardTries++
            binding.root.postDelayed({ askForKeyboard() }, KEYBOARD_RETRY_MS)
        }
    }

    private fun hideKeyboard() {
        keyboardTries = KEYBOARD_TRIES
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ history (the chat list)

    private fun toggleHistory() {
        if (historyOpen) hideHistory() else showHistory()
    }

    /** Shows the saved chats of the current model instead of the conversation. */
    fun showHistory() {
        historyOpen = true
        renderHistory()
        binding.conversationBox.visibility = View.GONE
        binding.historyBox.visibility = View.VISIBLE
        hideKeyboard()
    }

    fun hideHistory() {
        historyOpen = false
        binding.historyBox.visibility = View.GONE
        binding.conversationBox.visibility = View.VISIBLE
        // back to writing: the caret belongs in the prompt again, with the keyboard up
        binding.root.post { focusPrompt() }
    }

    /** Test seam: the rows of the chat list, top to bottom. */
    fun historyRowCountForTest(): Int = binding.historyList.childCount

    private fun renderHistory() {
        val (providerId, model) = key()
        val list = SessionStore.forModel(ctx, providerId, model)
        binding.historyTitle.text =
            if (model.isBlank() || model == "none") "Chats" else "Chats · " + model.substringAfterLast('/')
        binding.historyList.removeAllViews()

        if (list.isEmpty()) {
            binding.historyList.addView(
                textRow(
                    "No saved chats yet.\nYour chats are saved here automatically — one list per model, " +
                        "so switching the model in Settings shows that model's own chats."
                )
            )
            return
        }
        list.forEach { s -> binding.historyList.addView(historyRow(s)) }
    }

    private fun historyRow(s: Session): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(4), dp(8))
            background = selectableBackground()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val isCurrent = s.id == session?.id
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = (if (isCurrent) "▸ " else "") + s.title
            setTextColor(color(if (isCurrent) com.agentbubble.R.color.accent else com.agentbubble.R.color.text))
            textSize = 13.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(ctx).apply {
            text = s.messages.size.toString() + " messages · " + SessionStore.stamp(s.updatedAt) +
                (if (isCurrent) " · open now" else "")
            setTextColor(color(com.agentbubble.R.color.text_dim))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(col)

        row.addView(ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(color(com.agentbubble.R.color.text_dim))
            background = selectableBackground()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Delete this chat"
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { deleteSession(s.id) }
        })

        row.setOnClickListener { openSession(s.id) }
        return row
    }

    private fun textRow(text: String): View = TextView(ctx).apply {
        this.text = text
        setTextColor(color(com.agentbubble.R.color.text_dim))
        textSize = 12.5f
        setPadding(dp(12), dp(16), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun openSession(id: String) {
        val (providerId, model) = key()
        val s = SessionStore.forModel(ctx, providerId, model).firstOrNull { it.id == id } ?: return
        job?.cancel()
        session = s
        engine = null
        hideHistory()
        renderSession()
    }

    private fun deleteSession(id: String) {
        SessionStore.delete(ctx, id)
        val wasOpen = session?.id == id
        renderHistory()
        toast("Chat deleted")
        if (wasOpen) {
            // the chat being read was deleted: go back to the conversation, which falls back to the
            // newest remaining chat of this model — or a fresh one when none is left
            session = null
            engine = null
            hideHistory()
            openCurrentSession()
        }
    }

    /** A brand new conversation for the current model. */
    private fun newChat() {
        hideHistory()
        startNewSession()
        toast("New chat")
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun color(res: Int): Int = ContextCompat.getColor(ctx, res)

    private fun selectableBackground(): Drawable? = try {
        val tv = TypedValue()
        if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            ContextCompat.getDrawable(ctx, tv.resourceId)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ sessions

    private fun key(): Pair<String, String> {
        val cfg = store.activeProvider()
        return (cfg?.id ?: "none") to (cfg?.model ?: "none")
    }

    private fun openCurrentSession() {
        // The header must always say which model this chat belongs to — also on the very first open,
        // not only after Settings changes something.
        refreshHeader()
        val (providerId, model) = key()
        session = SessionStore.current(ctx, providerId, model)
        renderSession()
        if (historyOpen) renderHistory()
    }

    private fun startNewSession() {
        job?.cancel()
        val (providerId, model) = key()
        session = SessionStore.newSession(providerId, model)
        engine = null
        msgList.removeAllViews()
        greeting()
        scrollDown()
        binding.root.post { applyBubbleWidths() }
    }

    private fun renderSession() {
        msgList.removeAllViews()
        val s = session ?: return
        if (s.messages.isEmpty()) {
            greeting()
        } else {
            s.messages.forEach { turn ->
                if (turn.role == "user") addUser(turn.content, save = false)
                else addAi(turn.content, save = false)
            }
        }
        binding.root.post {
            applyBubbleWidths()
            scrollDown()
        }
    }

    private fun openSettings() {
        try {
            val i = Intent(ctx, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ctx.startActivity(i)
            onClose(false)
        } catch (_: Throwable) {
            toast("Could not open settings")
        }
    }

    // ------------------------------------------------------------------ sending

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return

        val cfg = store.activeProvider()
        if (cfg == null) {
            addAi("⚠ Add your AI first — tap the title above (API key → model list).")
            return
        }
        if (cfg.model.isBlank()) {
            addAi("⚠ No model selected yet. Tap the title above and pick one from the list.")
            return
        }
        if (job?.isActive == true) {
            toast("Still answering — wait a moment")
            return
        }

        if (actionMode) {
            if (!store.hasTaskCapabilities(cfg)) {
                addAi("Run all three API checks in Settings → AI & phone actions before using Chat + Tasks.")
                return
            }
            if (com.agentbubble.operator.OperatorAccessibilityService.connected == null) {
                addAi("Enable accessibility in Settings → Action setup first. Chat does not need accessibility.")
                return
            }
            val s = session ?: return
            input.setText("")
            addUser(text)
            onAction(text, s)
            return
        }
        // Load existing history BEFORE appending this user turn (avoid sending it twice).
        val historyBeforeSend = currentTurns()
        input.setText("")
        addUser(text)
        // the input must stay the thing being typed into: caret back in it, keyboard up
        focusPrompt()

        val engineKeyNow = cfg.id + "|" + cfg.model
        if (engine == null || engineKey != engineKeyNow) {
            engine = AgentEngine(cfg, store, this)
            engineKey = engineKeyNow
            engine?.loadHistory(historyBeforeSend)
        }
        val e = engine ?: return
        job = scope.launch {
            onWorkState(true)
            setBusy(true)
            try {
                e.send(text)
            } finally {
                setBusy(false)
                onWorkState(false)
                // the answer is in: the prompt is ready for the next message straight away
                binding.root.post { focusPrompt() }
            }
        }
    }

    private fun currentTurns(): List<Pair<String, String>> =
        session?.messages?.takeLast(20)?.map { it.role to it.content } ?: emptyList()

    private fun setBusy(busy: Boolean) {
        binding.btnSend.setImageResource(
            if (busy) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_send
        )
        binding.subtitle.text = if (busy) {
            "thinking…"
        } else {
            val cfg = store.activeProvider()
            when {
                cfg == null -> "Tap to add your AI"
                cfg.model.isBlank() -> "No model selected — tap to choose one"
                else -> cfg.name.ifBlank { "AI" } + " · " + cfg.model
            }
        }
    }

    // ------------------------------------------------------------------ move / resize

    /**
     * The card floats where the user puts it: dragging the header (or the model name) moves the whole
     * window. A touch that does not really move counts as a tap instead, so the title still opens
     * Settings.
     */
    private fun wireHeaderDrag() {
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var dragging = false

        val targets = listOf<View>(binding.headerRow, binding.title, binding.subtitle)
        targets.forEach { v ->
            v.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        lastX = downX
                        lastY = downY
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // the finger has to travel a little before it counts as a drag, otherwise a
                        // slightly shaky tap would nudge the card
                        if (!dragging &&
                            (Math.abs(ev.rawX - downX) > DRAG_SLOP || Math.abs(ev.rawY - downY) > DRAG_SLOP)
                        ) {
                            dragging = true
                        }
                        if (dragging) {
                            val dx = ev.rawX - lastX
                            val dy = ev.rawY - lastY
                            if (dx != 0f || dy != 0f) {
                                onMove(dx.toInt(), dy.toInt())
                                lastX = ev.rawX
                                lastY = ev.rawY
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            onMoveEnd()
                        } else if (v !== binding.headerRow) {
                            openSettings()   // a plain tap on the model name still opens Settings
                        }
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (dragging) onMoveEnd()
                        dragging = false
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun wireMoveAndResize() {
        wireResizeHandle()
        wireHeightHandle()
    }

    private fun wireResizeHandle() {
        val h = binding.dragHandle
        var startX = 0f
        var startW = 0
        h.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startW = root.width
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    onResize((startW + (startX - ev.rawX).toInt()).coerceAtLeast(200))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    onResizeEnd(root.width, "drag")
                    true
                }
                else -> false
            }
        }
    }

    private fun wireHeightHandle() {
        val h = binding.dragHandleTop
        var startY = 0f
        var startH = 0
        h.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = ev.rawY
                    startH = root.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    onResizeHeight((startH + (ev.rawY - startY).toInt()).coerceAtLeast(260))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    onResizeHeightEnd(root.height, "drag")
                    true
                }
                else -> false
            }
        }
    }

    private fun wireInput() {
        input.setShowSoftInputOnFocus(true)
        input.setOnClickListener { showKeyboard() }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showKeyboard()
            } else if (!historyOpen) {
                if (keyboardHasShown) {
                    // Losing focus immediately after the IME Back key is a user dismissal, not a
                    // reason to ask the keyboard to come back.
                    keyboardDismissedByUser = true
                } else {
                    // Before the first keyboard appearance, never leave the user without a caret.
                    binding.root.post { if (!input.hasFocus()) focusPrompt() }
                }
            }
        }
        // The whole prompt row is a target, not just the text field: a tap on the empty space next to
        // it opens the keyboard as well (that is what "tapping the input" means for a user).
        binding.inputRow.setOnClickListener { showKeyboard() }

        // Enter sends (the keyboard shows ➤ because the input is not multi-line).
        input.setOnEditorActionListener { _, actionId, event ->
            val sendByAction = actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE
            val enter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN &&
                !event.isShiftPressed
            if (sendByAction || enter) {
                send()
                true
            } else {
                false
            }
        }
    }

    // ------------------------------------------------------------------ messages

    private fun addUser(text: String, save: Boolean = true) {
        val item = ItemMsgUserBinding.inflate(LayoutInflater.from(ctx), msgList, false)
        item.text.text = text
        msgList.addView(item.root)
        if (save) {
            session?.let { SessionStore.append(ctx, it, "user", text) }
            applyBubbleWidths()
            scrollDown()
        }
    }

    private fun addAi(text: String, save: Boolean = true) {
        val item = ItemMsgAiBinding.inflate(LayoutInflater.from(ctx), msgList, false)
        item.text.text = text
        msgList.addView(item.root)
        if (save) {
            session?.let { SessionStore.append(ctx, it, "assistant", text) }
            applyBubbleWidths()
            scrollDown()
        }
    }

    /**
     * The message bubbles used to have a fixed 290dp width — in a narrow window the text ran off the
     * edge and looked like it was missing. Now the width follows the window.
     */
    private fun applyBubbleWidths() {
        val density = ctx.resources.displayMetrics.density
        val w = root.width.takeIf { it > 0 } ?: (280 * density).toInt()
        val maxPx = (w * 0.86f).toInt().coerceAtLeast((120 * density).toInt())
        for (i in 0 until msgList.childCount) {
            val row = msgList.getChildAt(i) as? ViewGroup ?: continue
            val tv = row.getChildAt(0) as? TextView ?: continue
            tv.maxWidth = maxPx
        }
    }

    private fun greeting() {
        val cfg = store.activeProvider()
        addAi(
            if (cfg == null || cfg.model.isBlank()) {
                "👋 Add your AI first: tap here → paste your API key → load the model list → pick one. " +
                    "After that this window is just chat."
            } else {
                "👋 Ready. You are talking to ${cfg.model}. Ask me anything."
            },
            save = false
        )
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ engine callbacks

    override fun onAssistantText(text: String) = addAi(text)

    override fun onStatus(text: String) {
        if (text.isNotEmpty()) binding.subtitle.text = text
    }

    override fun onError(message: String) = addAi("⚠ $message")
}
