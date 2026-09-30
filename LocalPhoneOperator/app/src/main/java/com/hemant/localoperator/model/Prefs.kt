package com.hemant.localoperator.model

import android.content.Context

object Prefs {
    private const val NAME = "operator_prefs"
    private const val PLANNER = "planner_model"
    private const val VISION = "vision_model"
    private const val RISKY = "allow_risky"
    private const val PLANNER_PROVIDER = "planner_provider"
    private const val VISION_PROVIDER = "vision_provider"
    private const val API_BASE = "api_base"
    private const val API_PLANNER_MODEL = "api_planner_model"
    private const val API_VISION_MODEL = "api_vision_model"
    private const val AUTO_FALLBACK = "auto_fallback"
    private const val MAX_STEPS = "max_steps"

    const val PROVIDER_LOCAL = "local"
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_OFF = "off"

    private fun p(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun plannerPath(context: Context): String? = p(context).getString(PLANNER, null)
    fun visionPath(context: Context): String? = p(context).getString(VISION, null)
    fun setPlannerPath(context: Context, path: String) = p(context).edit().putString(PLANNER, path).apply()
    fun setVisionPath(context: Context, path: String) = p(context).edit().putString(VISION, path).apply()

    fun plannerProvider(context: Context): String = p(context).getString(PLANNER_PROVIDER, PROVIDER_LOCAL) ?: PROVIDER_LOCAL
    fun setPlannerProvider(context: Context, value: String) = p(context).edit().putString(PLANNER_PROVIDER, value).apply()

    fun visionProvider(context: Context): String = p(context).getString(VISION_PROVIDER, PROVIDER_LOCAL) ?: PROVIDER_LOCAL
    fun setVisionProvider(context: Context, value: String) = p(context).edit().putString(VISION_PROVIDER, value).apply()

    fun apiBase(context: Context): String = p(context).getString(API_BASE, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
    fun setApiBase(context: Context, value: String) = p(context).edit().putString(API_BASE, value.trim()).apply()

    fun apiPlannerModel(context: Context): String = p(context).getString(API_PLANNER_MODEL, "") ?: ""
    fun setApiPlannerModel(context: Context, value: String) = p(context).edit().putString(API_PLANNER_MODEL, value.trim()).apply()

    fun apiVisionModel(context: Context): String = p(context).getString(API_VISION_MODEL, "") ?: ""
    fun setApiVisionModel(context: Context, value: String) = p(context).edit().putString(API_VISION_MODEL, value.trim()).apply()

    fun autoFallback(context: Context): Boolean = p(context).getBoolean(AUTO_FALLBACK, true)
    fun setAutoFallback(context: Context, value: Boolean) = p(context).edit().putBoolean(AUTO_FALLBACK, value).apply()

    fun maxSteps(context: Context): Int = p(context).getInt(MAX_STEPS, 120).coerceIn(20, 240)
    fun setMaxSteps(context: Context, value: Int) = p(context).edit().putInt(MAX_STEPS, value.coerceIn(20, 240)).apply()

    fun allowRisky(context: Context): Boolean = p(context).getBoolean(RISKY, false)
    fun setAllowRisky(context: Context, allow: Boolean) = p(context).edit().putBoolean(RISKY, allow).apply()
}
