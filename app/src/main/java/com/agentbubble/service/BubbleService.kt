package com.agentbubble.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.agentbubble.R
import com.agentbubble.data.SettingsStore
import com.agentbubble.databinding.ViewBubbleBinding
import com.agentbubble.ui.ChatPanel
import com.agentbubble.ui.PanelGeometry
import com.agentbubble.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating bubble + the right-side chat sidebar.
 *
 * Gestures:
 *  - tap the bubble        -> open / close the sidebar
 *  - drag the bubble       -> move it (position is remembered)
 *  - hold the bubble 2 sec -> stop the bubble completely
 *
 * While the agent is working the sidebar slides off-screen and the bubble pulses, so the
 * agent can see the whole screen; it slides back by itself when the work is done.
 */
class BubbleService : Service() {

    companion object {
        const val ACTION_SHOW_CHAT = "com.agentbubble.SHOW_CHAT"
        const val ACTION_HIDE_CHAT = "com.agentbubble.HIDE_CHAT"
        const val ACTION_STOP = "com.agentbubble.STOP"
        const val ACTION_REFRESH = "com.agentbubble.REFRESH"

        private const val LONG_PRESS_MS = 2000L

        @Volatile
        var running = false
            private set

        @Volatile
        var chatOpen = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BubbleService::class.java))
        }

        fun send(ctx: Context, action: String) {
            try {
                val i = Intent(ctx, BubbleService::class.java).setAction(action)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Throwable) {
            }
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var store: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var busyAnimator: ObjectAnimator? = null

    private var actionJob: kotlinx.coroutines.Job? = null
    private var actionPanel: com.agentbubble.operator.ActionPanel? = null
    private var panel: ChatPanel? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelWidthPx = 0

    /** Base geometry (keyboard closed) so the card can be restored when the keyboard hides. */
    private var panelBaseY = 0
    private var panelBaseHeight = 0
    private var panelWidthPxSaved = 0

    /** When the chat was closed by a tap outside, the bubble tap that caused it is ignored. */
    private var outsideCloseAt = 0L

    /** Keyboard detection. The window never moves, so this signal stays stable. */
    private val keyboardTracker = com.agentbubble.ui.KeyboardTracker()
    private var keyboardApplied = -1
    private val keyboardPoll = object : Runnable {
        override fun run() {
            if (!chatOpen) return
            if (keyboardTracker.sample(imeHeight())) applyKeyboard(keyboardTracker.height)
            handler.postDelayed(this, 150)
        }
    }

    /**
     * How much of the card the keyboard covers. A card floating high up on the screen is not covered
     * at all, and then nothing has to be padded at all.
     */
    private fun keyboardOverlap(ime: Int): Int {
        val lp = panelLp ?: return 0
        val (_, sh) = screen()
        return PanelGeometry.keyboardOverlap(lp.y + lp.height, sh, ime)
    }

    /** The keyboard height as the display sees it — independent of our own window. */
    private fun imeHeight(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.ime()).bottom
        } else {
            0
        }
    } catch (_: Throwable) {
        0
    }

    private fun applyKeyboard(ime: Int) {
        if (ime == keyboardApplied) return
        keyboardApplied = ime
        val p = panel ?: return
        p.setKeyboardVisible(ime > 0)
        p.applyKeyboardInset(keyboardOverlap(ime), panelLp?.height ?: 0)
    }

    /** Both keyboard sources feed the same tracker, so a wobbling value cannot shake the UI. */
    fun onKeyboard(rawHeight: Int) {
        if (!chatOpen) return
        keyboardTracker.sample(rawHeight)
        handler.removeCallbacks(keyboardPoll)
        handler.postDelayed(keyboardPoll, 150)
    }

    /** Every overlay window this service currently owns. Used to purge stale copies — a
     *  leftover sidebar window was why the ✕ button looked like it did nothing. */
    private val attached = mutableListOf<View>()

    private val channelId = "agentbubble_service"
    private val notifId = 101

    private var longPress: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        // A brand new instance owns no window yet, whatever an older instance left behind.
        chatOpen = false
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            store = SettingsStore(this)
            createChannel()
            startForegroundCompat()
            showBubble()
        } catch (t: Throwable) {
            com.agentbubble.AgentApp.saveCrash(this, t)
            toast("Local Phone Operator could not start: ${t.message ?: t.javaClass.simpleName}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_CHAT -> openChat()
            ACTION_HIDE_CHAT -> closeChat()
            ACTION_REFRESH -> {
                applyBubbleSize()
                panel?.refreshProvider()
                // "Reset layout" clears the saved position — put the card back in the middle then.
                applyPanelPlacement()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Screen rotated or the display size changed: rebuild the card so it is measured correctly. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (chatOpen) {
            closeChat()
            handler.postDelayed({ if (!chatOpen) openChat() }, 250)
        }
    }

    override fun onDestroy() {
        actionJob?.cancel()
        actionPanel?.remove()
        actionPanel = null
        running = false
        chatOpen = false
        cancelLongPress()
        busyAnimator?.cancel()
        busyAnimator = null
        try {
            panel?.destroy()
        } catch (_: Throwable) {
        }
        panel = null
        bubbleView?.let {
            try {
                wm.removeView(it)
            } catch (_: Throwable) {
            }
        }
        bubbleView = null
        attached.clear()

        scope.cancel()
        super.onDestroy()
    }

    /**
     * Screenshots must show the app behind us, never our own bubble/sidebar. The capture code
     * calls these hooks to take our windows off screen for a moment.
     */




    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                val ch = NotificationChannel(
                    channelId,
                    "Local Phone Operator",
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "Keeps the floating assistant bubble running"
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun notification(working: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(if (working) "Agent is working…" else "Local Phone Operator is running")
            .setContentText(
                when {
                    working -> "Answering… tap the bubble to read it"
                    !chatOpen -> "Tap the bubble to chat"
                    else -> "Tap the bubble to talk • hold it 2s to close"
                }
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun startForegroundCompat() {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        try {
            ServiceCompat.startForeground(this, notifId, notification(), types)
        } catch (t: Throwable) {
            try {
                startForeground(notifId, notification())
            } catch (_: Throwable) {
            }
        }
    }



    private fun updateNotification(working: Boolean) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notifId, notification(working))
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ bubble

    private fun showBubble() {
        if (bubbleView != null) return
        try {
            val binding = ViewBubbleBinding.inflate(LayoutInflater.from(this))
            val v = binding.root
            binding.badge.visibility = View.GONE

            val size = (store.bubbleSizeDp * resources.displayMetrics.density).toInt()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val lp = WindowManager.LayoutParams(
                size,
                size,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = store.bubbleX
            lp.y = store.bubbleY

            attachGestures(v, lp)
            wm.addView(v, lp)
            bubbleView = v
            bubbleParams = lp
        } catch (t: Throwable) {
            com.agentbubble.AgentApp.saveCrash(this, t)
            try {
                bubbleView?.let { wm.removeView(it) }
            } catch (_: Throwable) {
            }
            bubbleView = null
            toast("Could not show the bubble — please allow \"Display over other apps\" for Local Phone Operator.")
            stopSelf()
        }
    }

    /** Pulse the bubble while the agent works (instead of covering the screen with UI). */
    fun setBubbleBusy(busy: Boolean) {
        val v = bubbleView ?: return
        busyAnimator?.cancel()
        busyAnimator = null
        if (busy) {
            v.alpha = 1f
            busyAnimator = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.35f).apply {
                duration = 550
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
            updateNotification(true)
        } else {
            v.animate().cancel()
            v.alpha = 1f
            updateNotification(false)
        }
    }

    private fun attachGestures(v: View, lp: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    downTime = System.currentTimeMillis()
                    // hold for 2 seconds -> close the bubble
                    scheduleLongPress(v)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        if (!moved) cancelLongPress()
                        moved = true
                    }
                    if (moved) {
                        val (sw, sh) = screen()
                        lp.x = (startX + dx.toInt()).coerceIn(-10, (sw - lp.width + 10).coerceAtLeast(0))
                        lp.y = (startY + dy.toInt()).coerceIn(0, (sh - lp.height).coerceAtLeast(0))
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasLongPress = longPress == null && !moved && System.currentTimeMillis() - downTime >= LONG_PRESS_MS - 100
                    cancelLongPress()
                    if (!moved && System.currentTimeMillis() - downTime < 400) {
                        toggleChat()
                    } else if (!wasLongPress) {
                        store.bubbleX = lp.x
                        store.bubbleY = lp.y
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    true
                }
                else -> false
            }
        }
    }

    private fun scheduleLongPress(v: View) {
        cancelLongPress()
        val r = Runnable {
            longPress = null
            try {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } catch (_: Throwable) {
            }
            toast("Local Phone Operator closed")
            closeChat()
            stopSelf()
        }
        longPress = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    private fun cancelLongPress() {
        longPress?.let { handler.removeCallbacks(it) }
        longPress = null
    }

    private fun screen(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val d = wm.defaultDisplay
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun statusBarHeight(): Int = try {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
    } catch (_: Throwable) {
        0
    }

    // ------------------------------------------------------------------ chat sidebar

    fun toggleChat() {
        // A tap outside already closed the chat — the same tap on the bubble must not reopen it.
        if (!chatOpen && android.os.SystemClock.uptimeMillis() - outsideCloseAt < 450) return
        if (chatOpen) {
            closeChat()
        } else {
            openChat()
        }
    }

    private fun setActionWindowsHidden(hidden: Boolean) {
        bubbleView?.let { view ->
            view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            bubbleParams?.let { params ->
                params.alpha = if (hidden) 0f else 1f
                params.flags = if (hidden) {
                    params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                runCatching { wm.updateViewLayout(view, params) }
            }
        }
        actionPanel?.setHiddenForAction(hidden)
    }

    private fun startAction(task: String, session: com.agentbubble.data.Session) {
        if (actionJob?.isActive == true) return
        val cfg = store.activeProvider()?.copy() ?: return
        closeChat()
        val control = com.agentbubble.operator.ActionPanel(this,
            { actionJob?.cancel() }, { openChat() })
        try { control.show() } catch (e: Exception) {
            com.agentbubble.data.SessionStore.append(this, session, "assistant", "Cannot show Stop controller; task was not started.")
            openChat(); return
        }
        actionPanel = control
        actionJob = scope.launch {
            onWorkState(true)
            var result = "Stopped. Task may be incomplete."
            try {
                kotlinx.coroutines.delay(600) // let the keyboard and chat overlay leave the target app
                val priorTurns = session.messages.dropLast(1).takeLast(12).map {
                    com.agentbubble.data.ChatMessage(it.role, it.content)
                }
                result = com.agentbubble.operator.ActionRunner(
                    this@BubbleService,
                    beforeExecute = {
                        setActionWindowsHidden(true)
                        kotlinx.coroutines.delay(120)
                    },
                    afterExecute = { setActionWindowsHidden(false) }
                ).run(task, cfg, { control.status(it) }, { control.approve(it) }, priorTurns)
            } catch (e: kotlinx.coroutines.CancellationException) {
                result = "Stopped or timed out. No further actions will run. An already-dispatched gesture may finish."
            } catch (e: Exception) {
                result = "Task stopped: " + (e.message ?: e.javaClass.simpleName).take(400)
            } finally {
                setActionWindowsHidden(false)
                com.agentbubble.data.SessionStore.append(this@BubbleService, session, "assistant", result)
                control.remove()
                actionPanel = null
                actionJob = null
                onWorkState(false)
                if (running) openChat()
            }
        }
    }

    fun openChat() {
        if (actionJob?.isActive == true) return
        actionPanel?.remove()
        actionPanel = null
        // The window itself decides, not the flag: a flag left behind by an older instance of this
        // service (the companion survives, instances do not) must never block a new chat window.
        if (chatOpen && panel != null) return
        // Never stack windows: remove anything left over from an earlier attempt first.
        purgeStaleWindows()
        try {
            val (sw, sh) = screen()
            val density = resources.displayMetrics.density
            val placement = PanelGeometry.floatingPlacement(
                screenW = sw,
                screenH = sh,
                density = density,
                topInset = statusBarHeight(),
                bottomInset = navBarHeight(),
                savedWidth = store.sidebarWidthPx,
                savedHeight = store.sidebarHeightPx,
                savedX = store.panelX,
                savedY = store.panelY
            )

            val lp = WindowManager.LayoutParams(
                placement.width, placement.height,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )
            // TOP|START: x/y are absolute, which is what a freely movable card needs.
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = placement.x
            lp.y = placement.y
            // The keyboard is handled by hand (see onKeyboard), so the system must not move us.
            // STATE_VISIBLE: the system raises the keyboard itself as soon as this window takes
            // focus (that is what makes the caret ready without tapping again). ADJUST_NOTHING keeps
            // the window exactly where it is — we absorb the keyboard as inside padding instead.
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            lp.windowAnimations = android.R.style.Animation_Translucent

            panelBaseY = placement.y
            panelBaseHeight = placement.height
            panelWidthPxSaved = placement.width

            val p = ChatPanel(
                this, scope, store,
                { viaOutside -> closeChat(viaOutside) },
                { working -> onWorkState(working) },
                { newWidth -> resizeSidebar(newWidth) },
                { widthPx, reason -> saveSidebarWidth(widthPx, reason) },
                { newHeight -> resizeSidebarHeight(newHeight) },
                { heightPx, reason -> saveSidebarHeight(heightPx, reason) },
                { ime -> onKeyboard(ime) },
                { dx, dy -> movePanel(dx, dy) },
                { savePanelPosition() },
                { task, session -> startAction(task, session) }
            )
            keyboardTracker.reset()
            keyboardApplied = -1
            wm.addView(p.root, lp)
            attached.add(p.root)
            panel = p
            panelLp = lp
            panelWidthPx = placement.width
            chatOpen = true
            p.onShown()
            // Keep an eye on the keyboard for as long as the chat is open.
            handler.removeCallbacks(keyboardPoll)
            handler.postDelayed(keyboardPoll, 200)
        } catch (t: Throwable) {
            com.agentbubble.AgentApp.saveCrash(this, t)
            closeChat()
            toast("Could not open the chat: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Bottom inset of the navigation bar, so the card never sits under it. */
    private fun navBarHeight(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        } else {
            0
        }
    } catch (_: Throwable) {
        0
    }

    /**
     * Close the sidebar and go back to just the floating bubble.
     *
     * Order is deliberate and each step is isolated, because the ✕ button has to work even when
     * something in the chat tears down badly:
     *   1. hide the view immediately (so it leaves the screen no matter what happens next),
     *   2. detach it from the window manager — synchronously first, asynchronously as fallback,
     *   3. destroy the chat logic,
     *   4. if the window still refuses to leave, stop the service (its onDestroy removes everything).
     */
    fun closeChat(viaOutsideTap: Boolean = false) {
        if (viaOutsideTap) outsideCloseAt = android.os.SystemClock.uptimeMillis()
        handler.removeCallbacks(keyboardPoll)
        keyboardTracker.reset()
        keyboardApplied = -1
        val p = panel
        panel = null
        panelLp = null
        chatOpen = false

        var detachFailed = false
        var reason = ""

        if (p != null) {
            try {
                p.root.animate().cancel()
            } catch (_: Throwable) {
            }
            try {
                p.root.visibility = View.GONE
            } catch (_: Throwable) {
            }
            detachFailed = !detach(p.root)
            reason = lastDetachError
            try {
                p.destroy()
            } catch (_: Throwable) {
            }
            attached.remove(p.root)
        }

        // sweep anything else we still own (stale copies from older builds/attempts)
        purgeStaleWindows()

        if (detachFailed) {
            toast("Sidebar closed. Please reopen the bubble if you still see it.")
            com.agentbubble.AgentApp.saveCrash(
                this,
                IllegalStateException("Could not detach the sidebar window: $reason")
            )
        }
        bounceBubble()
    }

    private var lastDetachError = ""

    /** Remove one window. Tries the immediate (synchronous) call first. */
    private fun detach(v: View): Boolean {
        lastDetachError = ""
        try {
            wm.removeViewImmediate(v)
            return true
        } catch (t1: Throwable) {
            lastDetachError = t1.message ?: t1.javaClass.simpleName
        }
        return try {
            wm.removeView(v)
            true
        } catch (t2: Throwable) {
            lastDetachError = t2.message ?: t2.javaClass.simpleName
            false
        }
    }

    /** Detach any sidebar windows we still own that are not the current panel. */
    private fun purgeStaleWindows() {
        val leftovers = attached.toList()
        attached.clear()
        panel?.let { attached.add(it.root) }
        leftovers.forEach { v ->
            if (v === panel?.root) return@forEach
            try {
                v.visibility = View.GONE
            } catch (_: Throwable) {
            }
            if (!detach(v)) {
                // Last resort: the service owns the window, so restarting it clears everything.
                toast("Resetting the floating panel…")
                handler.postDelayed({ stopSelf() }, 250)
            }
        }
    }

    /** Little nudge so the user sees the bubble is still there after closing the sidebar. */
    private fun bounceBubble() {
        val v = bubbleView ?: return
        try {
            if (busyAnimator != null) return
            v.animate().cancel()
            v.animate()
                .scaleX(1.18f).scaleY(1.18f).setDuration(110)
                .withEndAction {
                    try {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    } catch (_: Throwable) {
                    }
                }
                .start()
        } catch (_: Throwable) {
        }
    }

    /** Drag the left edge of the card: the right edge stays where it is. */
    fun resizeSidebar(widthPx: Int, persist: Boolean = true) {
        val p = panel ?: return
        val lp = panelLp ?: return
        val density = resources.displayMetrics.density
        val (sw, _) = screen()
        val minW = (220 * density).toInt()
        val maxW = (sw - 48 * density).toInt()
        val w = widthPx.coerceIn(minW, maxW)
        if (w == lp.width) return
        val rightEdge = lp.x + lp.width
        lp.width = w
        lp.x = clampPanelX(rightEdge - w)
        panelWidthPx = w
        try {
            wm.updateViewLayout(p.root, lp)
        } catch (_: Throwable) {
        }
        // Save as we go: if the drag ends without an ACTION_UP (finger lifted off-screen,
        // panel closed mid-drag), the width is still remembered.
        if (persist) store.sidebarWidthPx = w
    }

    /** Drag the top edge of the sidebar to make it taller or shorter. */
    fun resizeSidebarHeight(heightPx: Int, persist: Boolean = true) {
        panelBaseHeight = heightPx
        val p = panel ?: return
        val lp = panelLp ?: return
        val density = resources.displayMetrics.density
        val (_, sh) = screen()
        val maxH = (sh - statusBarHeight()).coerceAtLeast((sh * 0.6f).toInt())
        val minH = (320 * density).toInt()
        val h = heightPx.coerceIn(minH, maxH)
        if (h == lp.height) return
        // dragging the top edge grows the card downwards? no — the bottom edge stays put
        val bottomEdge = lp.y + lp.height
        lp.height = h
        lp.y = clampPanelY(bottomEdge - h)
        try {
            wm.updateViewLayout(p.root, lp)
        } catch (_: Throwable) {
        }
        if (persist) store.sidebarHeightPx = h
    }

    private fun defaultSidebarWidth(): Int {
        val density = resources.displayMetrics.density
        val (sw, _) = screen()
        return minOf((400 * density).toInt(), (sw - 56 * density).toInt())
    }

    private fun saveSidebarWidth(widthPx: Int, reason: String) {
        when (reason) {
            "reset" -> {
                store.sidebarWidthPx = 0
                resizeSidebar(defaultSidebarWidth(), persist = false)
            }
            else -> if (widthPx > 0) store.sidebarWidthPx = widthPx
        }
    }

    private fun saveSidebarHeight(heightPx: Int, reason: String) {
        when (reason) {
            "reset" -> {
                store.sidebarHeightPx = 0
                val (_, sh) = screen()
                resizeSidebarHeight((sh - statusBarHeight()), persist = false)
            }
            else -> if (heightPx > 0) store.sidebarHeightPx = heightPx
        }
    }

    fun sidebarHeightForTest(): Int = panelLp?.height ?: 0

    /** Applied live when the user changes the bubble size in settings. */
    fun applyBubbleSize() {
        val v = bubbleView ?: return
        val lp = bubbleParams ?: return
        val size = (store.bubbleSizeDp * resources.displayMetrics.density).toInt()
        lp.width = size
        lp.height = size
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ moving the card

    /**
     * The user dragged the card: move it by this much and keep it fully on screen. The card is only
     * ever moved by the user — never by the keyboard (that is what used to make it shake).
     */
    fun movePanel(dx: Int, dy: Int) {
        val p = panel ?: return
        val lp = panelLp ?: return
        val newX = clampPanelX(lp.x + dx)
        val newY = clampPanelY(lp.y + dy)
        if (newX == lp.x && newY == lp.y) return
        lp.x = newX
        lp.y = newY
        try {
            wm.updateViewLayout(p.root, lp)
        } catch (_: Throwable) {
        }
    }

    /** Remember where the user left the card, so the next chat opens in the same place. */
    fun savePanelPosition() {
        val lp = panelLp ?: return
        store.panelX = lp.x
        store.panelY = lp.y
    }

    private fun clampPanelX(x: Int): Int {
        val lp = panelLp ?: return x
        val density = resources.displayMetrics.density
        val (sw, _) = screen()
        val margin = (PanelGeometry.EDGE_MARGIN_DP * density).toInt()
        return x.coerceIn(margin, (sw - lp.width - margin).coerceAtLeast(margin))
    }

    private fun clampPanelY(y: Int): Int {
        val lp = panelLp ?: return y
        val density = resources.displayMetrics.density
        val (_, sh) = screen()
        val margin = (PanelGeometry.EDGE_MARGIN_DP * density).toInt()
        val minY = statusBarHeight() + margin
        return y.coerceIn(minY, (sh - navBarHeight() - lp.height - margin).coerceAtLeast(minY))
    }

    /**
     * Put the card where the saved layout says it belongs. Called when Settings resets the layout;
     * with a saved position it is simply the same place again.
     */
    private fun applyPanelPlacement() {
        val p = panel ?: return
        val lp = panelLp ?: return
        val (sw, sh) = screen()
        val density = resources.displayMetrics.density
        val placement = PanelGeometry.floatingPlacement(
            screenW = sw,
            screenH = sh,
            density = density,
            topInset = statusBarHeight(),
            bottomInset = navBarHeight(),
            savedWidth = store.sidebarWidthPx,
            savedHeight = store.sidebarHeightPx,
            savedX = store.panelX,
            savedY = store.panelY
        )
        if (lp.x == placement.x && lp.y == placement.y && lp.width == placement.width &&
            lp.height == placement.height
        ) {
            return
        }
        lp.width = placement.width
        lp.height = placement.height
        lp.x = placement.x
        lp.y = placement.y
        panelWidthPx = placement.width
        panelBaseHeight = placement.height
        try {
            wm.updateViewLayout(p.root, lp)
        } catch (_: Throwable) {
        }
    }

    // ---------- test hooks ----------
    fun panelRootForTest(): View? = panel?.root

    /** Window flags of the chat card — the keyboard can only attach when NOT_FOCUSABLE is absent. */
    fun panelFlagsForTest(): Int = panelLp?.flags ?: 0

    fun keyboardHeightForTest(): Int = keyboardTracker.height

    fun panelYForTest(): Int = panelLp?.y ?: 0
    fun panelXForTest(): Int = panelLp?.x ?: 0

    /** Test seam: drag the card by (dx, dy) exactly like a finger on the header does. */
    fun movePanelForTest(dx: Int, dy: Int) {
        movePanel(dx, dy)
        savePanelPosition()
    }
    fun panelHeightForTest(): Int = panelLp?.height ?: 0
    fun panelTitleForTest(): String =
        (panel?.root?.findViewById<android.widget.TextView>(R.id.title)?.text ?: "").toString()

    /** Test seam: the window just took focus (the caret must go back into the prompt). */
    fun panelWindowFocusForTest() = panel?.windowFocusForTest()

    /** The soft-input mode the chat window was created with. */
    fun panelSoftInputModeForTest(): Int = panelLp?.softInputMode ?: 0

    /** Test seam: pretend the keyboard opened/closed and let the tracker settle. */
    fun keyboardSampleForTest(px: Int, times: Int = 4) {
        repeat(times) { keyboardTracker.sample(px) }
        applyKeyboard(keyboardTracker.height)
    }
    fun windowCountForTest(): Int = attached.size
    fun sidebarWidthForTest(): Int = panelLp?.width ?: 0
    fun bubbleSizeForTest(): Int = bubbleParams?.width ?: 0

    /**
     * Called by the chat while the model is answering: the bubble pulses so the user can see that
     * something is happening. The chat itself never moves or closes for this.
     */
    fun onWorkState(working: Boolean) {
        setBubbleBusy(working)
    }
}
