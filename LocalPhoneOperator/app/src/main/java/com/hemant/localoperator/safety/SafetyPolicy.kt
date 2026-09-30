package com.hemant.localoperator.safety

import android.content.Context
import com.hemant.localoperator.model.Prefs

object SafetyPolicy {
    private val riskyWords = listOf(
        "send", "delete", "remove", "pay", "purchase", "buy", "transfer", "submit",
        "post", "publish", "install", "uninstall", "allow", "approve", "confirm",
        "call", "dial", "factory reset", "erase", "format", "share"
    )

    fun mayClick(context: Context, label: String?): Pair<Boolean, String> {
        if (Prefs.allowRisky(context)) return true to "allowed by user setting"
        val text = label.orEmpty().lowercase()
        val hit = riskyWords.firstOrNull { text.contains(it) }
        return if (hit == null) true to "normal UI action"
        else false to "Blocked sensitive action containing '$hit'. Enable sensitive actions in the app first."
    }

    fun mayTypeInto(isPassword: Boolean): Pair<Boolean, String> {
        return if (isPassword) false to "Password/PIN fields are never auto-filled."
        else true to "normal text field"
    }
}
