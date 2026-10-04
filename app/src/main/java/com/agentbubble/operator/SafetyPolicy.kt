package com.agentbubble.operator

import android.content.Context

/** Authentication is blocked; irreversible final actions need a specific confirmation. */
object SafetyPolicy {
    private val protected = Regex("password|passcode|\\bpin\\b|\\botp\\b|one.time.code|verification.code|recovery.code|captcha|biometric|fingerprint|authenticator", RegexOption.IGNORE_CASE)
    private val finalAction = Regex("\\b(send|submit|delete|remove|pay|purchase|buy|transfer|confirm order|place order|post|publish)\\b", RegexOption.IGNORE_CASE)
    fun protectedText(text: String): Boolean = protected.containsMatchIn(text)
    fun mayClick(context: Context, label: String?): Pair<Boolean, String> =
        (!protectedText(label.orEmpty())) to "Authentication controls require manual interaction"
    fun mayTypeInto(isPassword: Boolean): Pair<Boolean, String> = (!isPassword) to "Password fields cannot be automated"

    fun requiresConfirmation(proposal: Proposal, screen: ScreenObservation): Boolean {
        if (proposal.tool == "tap") return true // A bare image coordinate may hide a final action.
        if (proposal.tool !in setOf("click_text", "click_node")) return false
        val target = when (proposal.tool) {
            "click_text" -> proposal.args.optString("text")
            else -> screen.tree.lineSequence().firstOrNull { it.startsWith("#${proposal.args.optInt("node_id", -1)} ") }.orEmpty()
        }
        return finalAction.containsMatchIn(target)
    }
}
