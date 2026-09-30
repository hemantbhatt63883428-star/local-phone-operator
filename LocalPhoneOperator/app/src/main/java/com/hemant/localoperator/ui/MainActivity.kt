package com.hemant.localoperator.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import com.hemant.localoperator.model.ModelStore
import com.hemant.localoperator.model.Prefs
import com.hemant.localoperator.model.SecureSecrets
import com.hemant.localoperator.network.OpenAiCompatClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: FrameLayout
    private lateinit var accessibilityBadge: TextView
    private var pendingSlot: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Local Phone Operator"
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(buildShell())
        showChat()
    }

    override fun onResume() {
        super.onResume()
        if (::accessibilityBadge.isInitialized) refreshBadge()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val slot = pendingSlot ?: return
        scope.launch {
            Toast.makeText(this@MainActivity, "Importing model…", Toast.LENGTH_SHORT).show()
            runCatching { ModelStore.importModel(this@MainActivity, uri, slot) }
                .onSuccess { file ->
                    if (slot == "planner") Prefs.setPlannerPath(this@MainActivity, file.absolutePath)
                    else Prefs.setVisionPath(this@MainActivity, file.absolutePath)
                    Toast.makeText(this@MainActivity, "Imported ${file.name}", Toast.LENGTH_LONG).show()
                    showModels()
                }
                .onFailure { Toast.makeText(this@MainActivity, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun buildShell(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(248,249,252)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18.dp, 14.dp, 18.dp, 8.dp) }
        header.addView(TextView(this).apply { text = "Local Phone Operator"; textSize = 24f; setTextColor(Color.rgb(25,28,35)) })
        accessibilityBadge = TextView(this).apply { textSize = 12f; setPadding(0, 5.dp, 0, 4.dp) }
        header.addView(accessibilityBadge)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun navButton(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
        nav.addView(navButton("Chat", ::showChat), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(navButton("Models", ::showModels), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(navButton("Controls", ::showControls), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(nav)
        root.addView(header)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        refreshBadge()
        return root
    }

    private fun showChat() { setPage(ChatPage(this, scope, ::showModels)) }

    private fun showModels() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(18.dp,8.dp,18.dp,22.dp) }
        root.addView(section("Planner model"))
        val plannerSpinner = spinner(listOf("Local LiteRT-LM", "OpenAI-compatible API"), if (Prefs.plannerProvider(this)==Prefs.PROVIDER_OPENAI) 1 else 0)
        root.addView(plannerSpinner)
        root.addView(info("Local planner: ${fileSummary(Prefs.plannerPath(this), "not imported")}"))
        root.addView(Button(this).apply { text="Import local planner (.litertlm)"; setOnClickListener { chooseModel("planner") } })

        root.addView(section("Vision model"))
        val visionOptions = listOf("Local LiteRT-LM", "OpenAI-compatible API", "Disabled")
        val visionIndex = when(Prefs.visionProvider(this)){ Prefs.PROVIDER_OPENAI->1; Prefs.PROVIDER_OFF->2; else->0 }
        val visionSpinner = spinner(visionOptions, visionIndex)
        root.addView(visionSpinner)
        root.addView(info("Local vision: ${fileSummary(Prefs.visionPath(this), "not imported")}"))
        root.addView(Button(this).apply { text="Import local vision (.litertlm)"; setOnClickListener { chooseModel("vision") } })

        root.addView(section("OpenAI-compatible connection"))
        root.addView(info("Works with endpoints implementing OpenAI Chat Completions/tool calling. Use your provider's base URL and model IDs."))
        val base = edit("Base URL", Prefs.apiBase(this)); root.addView(base)
        val plannerModel = edit("Planner model ID", Prefs.apiPlannerModel(this)); root.addView(plannerModel)
        val visionModel = edit("Vision model ID (can be same)", Prefs.apiVisionModel(this)); root.addView(visionModel)
        val key = edit(if (SecureSecrets.hasApiKey(this)) "API key (saved — type to replace)" else "API key", "", password=true); root.addView(key)
        val fallback = Switch(this).apply { text="Auto fallback to API if local planner fails"; isChecked=Prefs.autoFallback(this@MainActivity) }; root.addView(fallback)
        val testStatus = info(""); root.addView(testStatus)
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text="Save"
            setOnClickListener {
                Prefs.setPlannerProvider(this@MainActivity, if(plannerSpinner.selectedItemPosition==1) Prefs.PROVIDER_OPENAI else Prefs.PROVIDER_LOCAL)
                Prefs.setVisionProvider(this@MainActivity, when(visionSpinner.selectedItemPosition){1->Prefs.PROVIDER_OPENAI;2->Prefs.PROVIDER_OFF;else->Prefs.PROVIDER_LOCAL})
                Prefs.setApiBase(this@MainActivity, base.text.toString())
                Prefs.setApiPlannerModel(this@MainActivity, plannerModel.text.toString())
                Prefs.setApiVisionModel(this@MainActivity, visionModel.text.toString())
                Prefs.setAutoFallback(this@MainActivity, fallback.isChecked)
                if(key.text.toString().isNotBlank()) SecureSecrets.setApiKey(this@MainActivity, key.text.toString())
                key.setText(""); Toast.makeText(this@MainActivity,"Saved",Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        row.addView(Button(this).apply {
            text="Test API"
            setOnClickListener {
                Prefs.setApiBase(this@MainActivity, base.text.toString()); Prefs.setApiPlannerModel(this@MainActivity, plannerModel.text.toString())
                if(key.text.toString().isNotBlank()) SecureSecrets.setApiKey(this@MainActivity,key.text.toString())
                testStatus.text="Testing…"
                scope.launch(Dispatchers.IO) {
                    val result=runCatching { OpenAiCompatClient(Prefs.apiBase(this@MainActivity), SecureSecrets.getApiKey(this@MainActivity), Prefs.apiPlannerModel(this@MainActivity)).chat(JSONArray().put(JSONObject().put("role","user").put("content","Reply only OK")), null, 20).text }
                    withContext(Dispatchers.Main){ testStatus.text=result.fold({"API OK: ${it.take(80)}"},{"API failed: ${it.message}"}) }
                }
            }
        }, LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        root.addView(row)
        setPage(ScrollView(this).apply { addView(root) })
    }

    private fun showControls() {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(18.dp,8.dp,18.dp,22.dp) }
        root.addView(section("Accessibility")); root.addView(info(if(isAccessibilityEnabled()) "Enabled ✓" else "Disabled"))
        root.addView(Button(this).apply { text="Open Accessibility Settings"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        root.addView(section("Safety"))
        val risky=Switch(this).apply { text="Allow sensitive side-effect actions"; isChecked=Prefs.allowRisky(this@MainActivity) }
        risky.setOnCheckedChangeListener { button, checked ->
            if(checked && !Prefs.allowRisky(this@MainActivity)) { button.isChecked=false; AlertDialog.Builder(this@MainActivity).setTitle("Enable sensitive actions?").setMessage("Allows controls such as Send, Delete, Pay, Purchase, Install, Allow, Confirm and Share while you supervise. Password/PIN/OTP automation stays blocked.").setNegativeButton("Cancel",null).setPositiveButton("Enable"){_,_->Prefs.setAllowRisky(this@MainActivity,true);button.isChecked=true}.show() }
            else if(!checked) Prefs.setAllowRisky(this@MainActivity,false)
        }
        root.addView(risky)
        root.addView(info("Keep OFF normally. The agent cannot bypass secure windows, CAPTCHA, passwords, OTP, device locks or protected app flows."))
        root.addView(section("Agent limits"))
        val max=edit("Max actions per task (20-240)",Prefs.maxSteps(this).toString(), number=true); root.addView(max)
        root.addView(Button(this).apply { text="Save max actions"; setOnClickListener { Prefs.setMaxSteps(this@MainActivity,max.text.toString().toIntOrNull()?:120); Toast.makeText(this@MainActivity,"Saved",Toast.LENGTH_SHORT).show() } })
        root.addView(section("Device")); root.addView(info("RAM: %.1f GB\nLow-RAM devices should prefer API planner/vision or very small local models.".format(totalRamGb())))
        setPage(ScrollView(this).apply{addView(root)})
    }

    private fun setPage(v: View) { content.removeAllViews(); content.addView(v, FrameLayout.LayoutParams(-1,-1)) }
    private fun section(t:String)=TextView(this).apply{text=t;textSize=19f;setTextColor(Color.rgb(30,35,45));setPadding(0,14.dp,0,6.dp)}
    private fun info(t:String)=TextView(this).apply{text=t;textSize=13f;setTextColor(Color.DKGRAY);setPadding(0,3.dp,0,8.dp)}
    private fun edit(hint:String,value:String,password:Boolean=false,number:Boolean=false)=EditText(this).apply{this.hint=hint;setText(value);setPadding(10.dp,9.dp,10.dp,9.dp);inputType=when{password->InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;number->InputType.TYPE_CLASS_NUMBER;else->InputType.TYPE_CLASS_TEXT}}
    private fun spinner(items:List<String>,selected:Int)=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,items);setSelection(selected)}
    private fun providerName(v:String)=when(v){Prefs.PROVIDER_OPENAI->"API";Prefs.PROVIDER_OFF->"Off";else->"Local"}

    @Suppress("DEPRECATION") private fun chooseModel(slot:String){pendingSlot=slot;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"},REQ_MODEL)}
    private fun refreshBadge(){accessibilityBadge.text=if(isAccessibilityEnabled())"Accessibility: ON • floating AI bubble active" else "Accessibility: OFF • enable it before running tasks";accessibilityBadge.setTextColor(if(isAccessibilityEnabled())Color.rgb(20,120,60)else Color.rgb(180,60,30))}
    private fun isAccessibilityEnabled():Boolean{val expected="$packageName/${com.hemant.localoperator.accessibility.OperatorAccessibilityService::class.java.name}";return Settings.Secure.getString(contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any{it.equals(expected,true)}}
    private fun fileSummary(path:String?,empty:String):String{if(path.isNullOrBlank())return empty;val f=File(path);return if(f.exists())"${f.name} • ${humanSize(f.length())}" else "missing"}
    private fun totalRamGb():Double{val am=getSystemService(ACTIVITY_SERVICE) as ActivityManager;val i=ActivityManager.MemoryInfo();am.getMemoryInfo(i);return i.totalMem/(1024.0*1024*1024)}
    private fun humanSize(b:Long)=if(b>=1024L*1024*1024)"%.2f GB".format(b/(1024.0*1024*1024))else"%.1f MB".format(b/(1024.0*1024))
    private val Int.dp:Int get()=(this*resources.displayMetrics.density).toInt()
    companion object{private const val REQ_MODEL=3001}
}
