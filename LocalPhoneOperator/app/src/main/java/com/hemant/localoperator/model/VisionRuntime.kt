package com.hemant.localoperator.model

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.hemant.localoperator.network.OpenAiCompatClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VisionRuntime {
    suspend fun describe(context: Context, png: ByteArray, question: String): String = withContext(Dispatchers.IO) {
        when (Prefs.visionProvider(context)) {
            Prefs.PROVIDER_OFF -> "VISION_DISABLED"
            Prefs.PROVIDER_OPENAI -> describeRemote(context, png, question)
            else -> describeLocal(context, png, question)
        }
    }

    private fun describeRemote(context: Context, png: ByteArray, question: String): String {
        val model = Prefs.apiVisionModel(context).ifBlank { Prefs.apiPlannerModel(context) }
        if (model.isBlank()) return "REMOTE_VISION_MODEL_NOT_CONFIGURED"
        return try {
            OpenAiCompatClient(Prefs.apiBase(context), SecureSecrets.getApiKey(context), model).vision(
                "You are the visual perception module of an Android operator. Describe only what is visible. " +
                    "Identify requested controls and approximate center coordinates if useful. Never infer hidden passwords, PINs or OTPs. Question: $question",
                png
            ).take(7000)
        } catch (t: Throwable) {
            "VISION_API_ERROR:${t.javaClass.simpleName}:${t.message.orEmpty().take(500)}"
        }
    }

    private fun describeLocal(context: Context, png: ByteArray, question: String): String {
        val path = Prefs.visionPath(context) ?: return "VISION_MODEL_NOT_CONFIGURED"
        if (!File(path).exists()) return "VISION_MODEL_FILE_MISSING"
        return try {
            val config = EngineConfig(
                modelPath = path,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumImages = 1,
                cacheDir = File(context.cacheDir, "litert_vision").apply { mkdirs() }.absolutePath
            )
            Engine(config).use { engine ->
                engine.initialize()
                val cConfig = ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are the visual perception module of an Android operator. Describe only what is visible. " +
                            "For requested controls, report labels and approximate center coordinates in pixels when possible. " +
                            "Never guess hidden content, passwords, PINs, OTPs, or security codes."
                    ),
                    maxOutputToken = 500
                )
                engine.createConversation(cConfig).use { conversation ->
                    conversation.sendMessage(Contents.of(
                        Content.Text("Current Android screenshot. Question: $question"),
                        Content.ImageBytes(png)
                    )).toString().take(7000)
                }
            }
        } catch (t: Throwable) {
            "VISION_ERROR:${t.javaClass.simpleName}:${t.message.orEmpty().take(500)}"
        }
    }
}
