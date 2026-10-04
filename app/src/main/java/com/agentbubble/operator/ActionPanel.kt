package com.agentbubble.operator

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** A non-focusable, draggable run controller. Never steals the target app's input focus. */
class ActionPanel(private val ctx: Context, private val stop: () -> Unit, private val back: () -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(18, 12, 18, 12)
        background = GradientDrawable().apply { setColor(Color.rgb(24, 30, 42)); cornerRadius = 22f }
        filterTouchesWhenObscured = true
    }
    private val title = TextView(ctx).apply { text = "ACTION · drag here"; textSize = 14f; setTextColor(Color.CYAN) }
    private val label = TextView(ctx).apply { textSize = 13f; setTextColor(Color.WHITE); setPadding(0, 10, 0, 10) }
    private val allow = Button(ctx).apply { text = "Allow once"; visibility = View.GONE; filterTouchesWhenObscured = true }
    private val close = Button(ctx).apply { text = "Stop"; filterTouchesWhenObscured = true }
    private var waiting: CompletableDeferred<Boolean>? = null
    private var attached = false
    private var done = false
    private val lp = WindowManager.LayoutParams(
        (ctx.resources.displayMetrics.widthPixels * .86f).toInt().coerceAtMost((440 * ctx.resources.displayMetrics.density).toInt()),
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 50 }
    init {
        root.addView(title)
        root.addView(ScrollView(ctx).apply { addView(label) }, LinearLayout.LayoutParams(-1, (110 * ctx.resources.displayMetrics.density).toInt()))
        val row = LinearLayout(ctx)
        row.addView(allow, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(close, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(row)
        allow.setOnClickListener { waiting?.complete(true); waiting = null; allow.visibility = View.GONE }
        close.setOnClickListener { if (done) { remove(); back() } else { cancelApproval(); stop(); finish("Stopped. No further steps will run.") } }
        var x = 0f; var y = 0f
        title.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x = e.rawX; y = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    val dm = ctx.resources.displayMetrics
                    lp.x = (lp.x + e.rawX - x).toInt().coerceIn(0, (dm.widthPixels - lp.width).coerceAtLeast(0))
                    lp.y = (lp.y + e.rawY - y).toInt().coerceIn(0, (dm.heightPixels - root.height).coerceAtLeast(0))
                    x = e.rawX; y = e.rawY
                    if (attached) wm.updateViewLayout(root, lp)
                    true
                }
                else -> true
            }
        }
    }
    fun show() { wm.addView(root, lp); attached = true }
    fun status(text: String) { if (!done) label.text = text }
    suspend fun approve(text: String): Boolean {
        if (done || !attached) return false
        val pending = CompletableDeferred<Boolean>()
        waiting = pending; label.text = text; allow.visibility = View.VISIBLE
        return try { withTimeoutOrNull(120000) { pending.await() } ?: false }
        finally { waiting = null; allow.visibility = View.GONE }
    }
    fun cancelApproval() { waiting?.complete(false); waiting = null; allow.visibility = View.GONE }

    /**
     * Accessibility fallback gestures are real screen gestures. Make this overlay fully transparent
     * and non-touchable for that short dispatch so the app underneath receives the gesture.
     */
    fun setHiddenForAction(hidden: Boolean) {
        if (!attached) return
        root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        lp.alpha = if (hidden) 0f else 1f
        lp.flags = if (hidden) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { wm.updateViewLayout(root, lp) }
    }

    fun finish(text: String) { cancelApproval(); done = true; label.text = text; close.text = "Back to chat" }
    fun remove() { cancelApproval(); if (attached) { runCatching { wm.removeView(root) }; attached = false } }
}
