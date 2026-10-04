package com.agentbubble.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** One AI backend the user configured. Any OpenAI-compatible endpoint works. */
data class ProviderConfig(
    var id: String = "",
    var name: String = "",
    var baseUrl: String = "",
    var apiKey: String = "",
    var model: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)

    companion object {
        fun from(o: JSONObject): ProviderConfig = ProviderConfig(
            id = o.optString("id"),
            name = o.optString("name"),
            baseUrl = o.optString("baseUrl"),
            apiKey = o.optString("apiKey"),
            model = o.optString("model")
        )

        /** Kept for "let me change my mind" — the starting point stays OpenRouter. */
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    }
}

/** Everything the app stores. Deliberately small: a base URL, a key, a model, and the layout. */
class SettingsStore(context: Context) {

    private val app = context.applicationContext
    private val sp = context.getSharedPreferences("agentbubble", Context.MODE_PRIVATE)

    init {
        if (!sp.getBoolean("apiOnlyMigration", false)) {
            app.deleteSharedPreferences("local_model")
            File(app.filesDir, "models").deleteRecursively()
            sp.edit().remove("useLocal").putBoolean("apiOnlyMigration", true).apply()
        }
    }

    fun appContext(): Context = app

    // ------------------------------------------------------------------ provider

    fun providers(): MutableList<ProviderConfig> {
        val out = mutableListOf<ProviderConfig>()
        val raw = sp.getString("providers", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o ->
                    val cfg = ProviderConfig.from(o)
                    if (o.has("apiKeyCipher")) cfg.apiKey = runCatching {
                        SecretVault.decrypt(cfg.id, o.optString("apiKeyCipher"))
                    }.getOrDefault("")
                    out.add(cfg)
                }
            }
        } catch (_: Throwable) {
        }
        // One-time migration: only rewrite after all keys have been encrypted successfully.
        if (raw.contains("\"apiKey\"")) runCatching { saveProviders(out) }
        return out
    }

    fun saveProviders(list: List<ProviderConfig>) {
        val arr = JSONArray()
        list.forEach {
            val o = it.toJson()
            o.remove("apiKey")
            o.put("apiKeyCipher", SecretVault.encrypt(it.id, it.apiKey))
            arr.put(o)
        }
        sp.edit().putString("providers", arr.toString()).apply()
    }

    fun upsertProvider(p: ProviderConfig) {
        val list = providers()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        saveProviders(list)
        if (activeProviderId().isBlank()) setActiveProvider(p.id)
    }

    fun deleteProvider(id: String) {
        saveProviders(providers().filter { it.id != id })
        if (activeProviderId() == id) {
            val rest = providers()
            setActiveProvider(rest.firstOrNull()?.id ?: "")
        }
    }

    fun activeProviderId(): String = sp.getString("activeProvider", "") ?: ""

    fun setActiveProvider(id: String) = sp.edit().putString("activeProvider", id).apply()

    /** The explicit Chat/Automation choice survives action-panel teardown. */
    var automationMode: Boolean
        get() = sp.getBoolean("automationMode", false)
        set(v) = sp.edit().putBoolean("automationMode", v).apply()

    fun activeProvider(): ProviderConfig? {
        val list = providers()
        return list.firstOrNull { it.id == activeProviderId() } ?: list.firstOrNull()
    }

    private fun capabilityKey(cfg: ProviderConfig, kind: String): String {
        val identity = "${cfg.baseUrl}\n${cfg.model}\n${cfg.apiKey}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "capability_${digest}_$kind"
    }

    fun setCapability(cfg: ProviderConfig, kind: String, works: Boolean) {
        require(kind in setOf("chat", "image", "tools"))
        sp.edit().putBoolean(capabilityKey(cfg, kind), works).apply()
    }

    fun hasTaskCapabilities(cfg: ProviderConfig): Boolean =
        listOf("chat", "image", "tools").all { sp.getBoolean(capabilityKey(cfg, it), false) }

    /** The model the user last picked, remembered per provider. */
    fun pickedModel(providerId: String): String =
        sp.getString("model_" + providerId, "") ?: ""

    fun setPickedModel(providerId: String, model: String) {
        sp.edit().putString("model_" + providerId, model).apply()
    }

    // ------------------------------------------------------------------ layout

    var bubbleX: Int
        get() = sp.getInt("bubbleX", 0)
        set(v) = sp.edit().putInt("bubbleX", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubbleY", 260)
        set(v) = sp.edit().putInt("bubbleY", v).apply()

    var bubbleSizeDp: Int
        get() = sp.getInt("bubbleSizeDp", 60)
        set(v) = sp.edit().putInt("bubbleSizeDp", v.coerceIn(36, 96)).apply()

    var sidebarWidthPx: Int
        get() = sp.getInt("sidebarWidthPx", 0)
        set(v) = sp.edit().putInt("sidebarWidthPx", v).apply()

    var sidebarHeightPx: Int
        get() = sp.getInt("sidebarHeightPx", 0)
        set(v) = sp.edit().putInt("sidebarHeightPx", v).apply()

    /** Where the chat card floats. -1 = not moved yet, put it in the middle. */
    var panelX: Int
        get() = sp.getInt("panelX", -1)
        set(v) = sp.edit().putInt("panelX", v).apply()

    var panelY: Int
        get() = sp.getInt("panelY", -1)
        set(v) = sp.edit().putInt("panelY", v).apply()

    // ------------------------------------------------------------------ chat

    var systemPrompt: String
        get() = sp.getString("systemPrompt", "You are a helpful assistant.") ?: ""
        set(v) = sp.edit().putString("systemPrompt", v).apply()
}
