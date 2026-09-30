package com.hemant.localoperator.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.hemant.localoperator.agent.AgentRuntime
import com.hemant.localoperator.agent.ChatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

class OperatorOverlay(private val service: AccessibilityService, private val scope: CoroutineScope) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var status: TextView? = null
    private var prompt: EditText? = null
    private var transcript: LinearLayout? = null
    private var panelView: View? = null
    private var observer: Job? = null

    fun show() {
        if (root != null) return
        val container = LinearLayout(service).apply { orientation=LinearLayout.VERTICAL; setPadding(6.dp,6.dp,6.dp,6.dp) }
        val panelWidth = (service.resources.displayMetrics.widthPixels - 32.dp).coerceAtMost(360.dp)
        val bubble = TextView(service).apply { text="AI"; textSize=17f; setTextColor(Color.WHITE); gravity=Gravity.CENTER; background=rounded(Color.rgb(44,105,230),999f); layoutParams=LinearLayout.LayoutParams(56.dp,56.dp) }
        val panel = LinearLayout(service).apply { orientation=LinearLayout.VERTICAL; visibility=View.GONE; setPadding(14.dp,12.dp,14.dp,12.dp); background=rounded(Color.rgb(25,34,57),20.dp.toFloat()) }
        panelView=panel
        panel.addView(TextView(service).apply{text="Phone Operator";textSize=17f;setTextColor(Color.WHITE);setPadding(0,0,0,5.dp)})
        transcript=LinearLayout(service).apply{orientation=LinearLayout.VERTICAL}
        val scroll=ScrollView(service).apply{addView(transcript)}
        panel.addView(scroll,LinearLayout.LayoutParams(-1,200.dp))
        prompt=EditText(service).apply{hint="What should I do?";setHintTextColor(Color.LTGRAY);setTextColor(Color.WHITE);minLines=2;maxLines=4;setBackgroundColor(Color.argb(70,255,255,255));setPadding(8.dp,6.dp,8.dp,6.dp)}
        panel.addView(prompt,LinearLayout.LayoutParams(-1,LinearLayout.LayoutParams.WRAP_CONTENT))
        status=TextView(service).apply{text="Ready";setTextColor(Color.LTGRAY);textSize=11f;setPadding(0,5.dp,0,5.dp)}
        panel.addView(status)
        val buttons=LinearLayout(service).apply{orientation=LinearLayout.HORIZONTAL}
        val run=Button(service).apply{text="Run"}
        val stop=Button(service).apply{text="Stop";setOnClickListener{AgentRuntime.stop()}}
        val app=Button(service).apply{text="Full app";setOnClickListener{ val i=service.packageManager.getLaunchIntentForPackage(service.packageName); i?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); if(i!=null) service.startActivity(i) }}
        run.setOnClickListener{
            val task=prompt?.text?.toString()?.trim().orEmpty();if(task.isBlank())return@setOnClickListener
            if (AgentRuntime.start(service.applicationContext, task)) {
                prompt?.setText(""); refreshTranscript(); collapseForTask()
            }
        }
        buttons.addView(run,LinearLayout.LayoutParams(0,-2,1f));buttons.addView(stop,LinearLayout.LayoutParams(0,-2,1f));buttons.addView(app,LinearLayout.LayoutParams(0,-2,1f));panel.addView(buttons,LinearLayout.LayoutParams(-1,-2))
        container.addView(bubble);container.addView(panel, LinearLayout.LayoutParams(panelWidth, -2))
        var downX=0f;var downY=0f;var startX=0;var startY=0
        bubble.setOnTouchListener{_,e-> val lp=params?:return@setOnTouchListener false;when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;startX=lp.x;startY=lp.y;true}
            MotionEvent.ACTION_MOVE->{lp.x=(startX+(e.rawX-downX).toInt()).coerceIn(0, (service.resources.displayMetrics.widthPixels-65.dp).coerceAtLeast(0));lp.y=(startY+(e.rawY-downY).toInt()).coerceIn(0, (service.resources.displayMetrics.heightPixels-80.dp).coerceAtLeast(0));root?.let{wm.updateViewLayout(it,lp)};true}
            MotionEvent.ACTION_UP->{if(abs(e.rawX-downX)<12&&abs(e.rawY-downY)<12){expanded=!expanded;panel.visibility=if(expanded)View.VISIBLE else View.GONE;updateFocus(expanded);if(expanded)refreshTranscript()};true}
            else->false}}
        val lp=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=service.resources.displayMetrics.widthPixels-85.dp;y=170.dp}
        root=container;params=lp;wm.addView(container,lp)
        observer = scope.launch {
            var revision = -1L
            AgentRuntime.state.collect { state ->
                status?.text = if (state.running) state.status else "Ready"
                run.isEnabled = !state.running
                stop.isEnabled = state.running
                if (revision != state.revision) {
                    revision = state.revision
                    if (expanded) refreshTranscript()
                }
            }
        }
    }

    private fun refreshTranscript(){service.mainExecutor.execute{val t=transcript?:return@execute;t.removeAllViews();ChatStore.load(service).takeLast(6).forEach{m->t.addView(TextView(service).apply{text=(if(m.role=="user")"You: " else "AI: ")+m.text.take(400);textSize=12f;setTextColor(if(m.role=="user")Color.rgb(160,205,255)else Color.WHITE);setPadding(4.dp,4.dp,4.dp,4.dp)})}}}
    fun hide(){observer?.cancel();observer=null;root?.let{runCatching{wm.removeView(it)}};root=null}
    private fun collapseForTask(){expanded=false;panelView?.visibility=View.GONE;updateFocus(false)}
    private fun updateFocus(enable:Boolean){val lp=params?:return;lp.flags=if(enable)WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;root?.let{wm.updateViewLayout(it,lp)};if(enable){prompt?.requestFocus();prompt?.postDelayed({(service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(prompt,InputMethodManager.SHOW_IMPLICIT)},100)}else root?.windowToken?.let{(service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it,0)}}
    private fun rounded(color:Int,radius:Float)=GradientDrawable().apply{setColor(color);cornerRadius=radius}
    private val Int.dp:Int get()=(this*service.resources.displayMetrics.density).toInt()
}
