package com.agentbubble.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.agentbubble.R
import com.agentbubble.data.SettingsStore
import com.agentbubble.service.BubbleService

/**
 * Settings, and nothing else: pick your AI (key → model list → search → select), and adjust the
 * bubble. Every automation feature that used to live here was removed on purpose.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var tvAi: TextView
    private lateinit var tvPermissions: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SettingsStore(this)

        tvAi = findViewById(R.id.tvAi)
        tvPermissions = findViewById(R.id.tvPermissions)

        findViewById<Button>(R.id.btnChooseModel).setOnClickListener { pickModel() }
        findViewById<Button>(R.id.btnResetLayout).setOnClickListener {
            store.bubbleSizeDp = 60
            store.sidebarWidthPx = 0
            store.sidebarHeightPx = 0
            store.panelX = -1
            store.panelY = -1
            store.bubbleX = 0
            store.bubbleY = 260
            refresh()
            BubbleService.send(this, BubbleService.ACTION_REFRESH)
            toast("Layout reset")
        }
        findViewById<Button>(R.id.btnBubblePermission).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                startBubble()
            }
        }
        findViewById<Button>(R.id.btnStartBubble).setOnClickListener { startBubble() }

        val seek = findViewById<SeekBar>(R.id.sbBubbleSize)
        val tvSize = findViewById<TextView>(R.id.tvBubbleSize)
        seek.max = 60            // 36..96 dp
        seek.progress = (store.bubbleSizeDp - 36).coerceIn(0, 60)
        tvSize.text = "Bubble size: ${store.bubbleSizeDp} dp"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dp = 36 + p
                tvSize.text = "Bubble size: $dp dp"
                store.bubbleSizeDp = dp
                BubbleService.send(this@MainActivity, BubbleService.ACTION_REFRESH)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnRuntime).setOnClickListener {
            startActivity(Intent(this, com.agentbubble.operator.RuntimeActivity::class.java))
        }
        askNotificationsIfNeeded()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val cfg = store.activeProvider()
        tvAi.text = when {
            cfg == null -> "No AI yet.\nTap “Choose model”, paste your API key, load the list and pick one."
            cfg.model.isBlank() -> "${cfg.name}\nNo model selected yet — tap “Choose model”."
            else -> "Chatting with:\n${cfg.model}\n${cfg.baseUrl}\n" +
                "(tap “Choose model” to change it)"
        }
        tvPermissions.text = buildString {
            append(if (Settings.canDrawOverlays(this@MainActivity)) "✔ Floating bubble allowed" else "✘ Floating bubble not allowed")
            append("\n")
            append(
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED ||
                    Build.VERSION.SDK_INT < 33
                ) "✔ Notifications" else "✘ Notifications"
            )
        }
    }

    private fun pickModel() {
        val current = store.activeProvider()
        ProviderDialog.show(this, store, current) {
            refresh()
            BubbleService.send(this, BubbleService.ACTION_REFRESH)
        }
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow “Display over other apps” first")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        BubbleService.start(this)
        toast("Bubble is on — tap it to chat")
    }

    private fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11
            )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
