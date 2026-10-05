package com.agentbubble

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.agentbubble.data.ProviderConfig
import com.agentbubble.data.Session
import com.agentbubble.data.SessionStore
import com.agentbubble.data.SecretVault
import com.agentbubble.data.SettingsStore
import com.agentbubble.data.Turn
import com.agentbubble.net.LlmToolCall
import com.agentbubble.net.LlmToolResponse
import com.agentbubble.operator.ActionDevice
import com.agentbubble.operator.ActionRunner
import com.agentbubble.operator.Proposal
import com.agentbubble.operator.ScreenObservation
import com.agentbubble.operator.ScreenCapturePolicy
import com.agentbubble.operator.CaptureGeometry
import com.agentbubble.operator.SafetyPolicy
import com.agentbubble.operator.TaskPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OperatorTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val cfg = ProviderConfig("mock", "Mock", "https://example.test/v1", "key", "model")

    @Before fun keys() {
        ctx.getSharedPreferences("agentbubble", Context.MODE_PRIVATE).edit().clear().commit()
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SecretVault.keyProvider = { key }
    }

    private fun observation(
        tree: String = "#0 t=Open b=0,0,80,80",
        rotation: Int = 0,
        jpeg: String = "Zg=="
    ) = ScreenObservation("com.example", 1L, rotation, Rect(10, 20, 110, 220), 50, 100, jpeg, tree)

    private class FakeDevice(var screen: ScreenObservation) : ActionDevice {
        var actions = 0
        var observations = 0
        var onObserve: (() -> Unit)? = null
        var cheapChecks = 0
        var onStableCheck: (() -> Int)? = null
        var result = "CLICKED"
        var onExecute: (() -> Unit)? = null
        override suspend fun observe(): ScreenObservation { observations++; onObserve?.invoke(); return screen }
        override suspend fun currentStableKey(): Int {
            cheapChecks++
            return onStableCheck?.invoke() ?: observe().stableKey()
        }
        override suspend fun execute(proposal: Proposal, observed: ScreenObservation): String {
            actions++
            onExecute?.invoke()
            return result
        }
    }

    private fun call(name: String, args: String = "{}"): LlmToolResponse =
        LlmToolResponse("", listOf(LlmToolCall("call_1", name, JSONObject(args), args)))

    @Test fun screenQuestionSendsImageAndRunsZeroActions() = runBlocking {
        val d = FakeDevice(observation())
        var imageSent = false
        val runner = ActionRunner(ctx, d, { messages: JSONArray, _: JSONArray ->
            val content = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
            imageSent = content.getJSONObject(1).getString("type") == "image_url"
            assertEquals("low", content.getJSONObject(1).getJSONObject("image_url").getString("detail"))
            LlmToolResponse("The Open button is visible.", emptyList())
        }, 0)
        val answer = runner.run("What is on screen?", cfg, {}, { fail("No action needs approval"); false })
        assertTrue(imageSent)
        assertTrue(answer.contains("Open button"))
        assertEquals(0, d.actions)
    }

    @Test fun staleImageOrRotationRejectsAction() = runBlocking {
        val d = FakeDevice(observation())
        d.onObserve = { if (d.observations == 2) d.screen = observation(rotation = 1) }
        var calls = 0
        val runner = ActionRunner(ctx, d, { _, _ ->
            if (calls++ == 0) call("click_text", """{"text":"Open","exact":true}""")
            else LlmToolResponse("Screen changed; please retry.", emptyList())
        }, 0)
        val result = runner.run("Open it", cfg, {}, { false })
        assertEquals(0, d.actions)
        assertTrue(result.contains("retry"))
    }

    @Test fun greetingWorksWhenNoExternalWindowExists() = runBlocking {
        var actions = 0
        val d = object : ActionDevice {
            override suspend fun observe(): ScreenObservation =
                throw IllegalStateException("No external app window is visible.")
            override suspend fun execute(proposal: Proposal, observed: ScreenObservation): String {
                actions++
                return "unexpected"
            }
        }
        val runner = ActionRunner(ctx, d, { messages, _ ->
            val content = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
            assertEquals("no fake image should be sent", 1, content.length())
            assertTrue(content.toString().contains("NO_EXTERNAL_APP_WINDOW"))
            LlmToolResponse("Hi! How can I help?", emptyList())
        }, 0)
        assertEquals("Hi! How can I help?", runner.run("Hii", cfg, {}, { false }))
        assertEquals(0, actions)
    }

    @Test fun bulkWorkflowRemembersDeduplicatesAndRequiresVisibleDownloadEvidence() = runBlocking {
        val d = FakeDevice(observation("#0 t=LCM 30 Days Downloaded\n#1 t=HCF 30 Days Downloaded"))
        var calls = 0
        val runner = ActionRunner(ctx, d, { _, _ ->
            when (calls++) {
                0 -> call("remember_items", """{"items":[{"name":"LCM 30 Days"},{"name":"lcm-30 days"},{"name":"HCF 30 Days"}]}""")
                1 -> call("update_item_status", """{"name":"LCM 30 Days","status":"downloaded","evidence":"LCM 30 Days Downloaded"}""")
                2 -> call("update_item_status", """{"name":"HCF 30 Days","status":"downloaded","evidence":"HCF 30 Days Downloaded"}""")
                else -> call("finish", """{"summary":"Files saved","evidence":"HCF 30 Days Downloaded"}""")
            }
        }, 0)
        val result = runner.run("Download all LCM and HCF files", cfg, {}, { true })
        assertTrue(result.startsWith("Completed: all 2 recorded item"))
        assertEquals(0, d.actions)
    }

    @Test fun bulkWorkflowCannotFinishWithPendingRecordedFiles() = runBlocking {
        val d = FakeDevice(observation("#0 t=LCM sheet"))
        var calls = 0
        val runner = ActionRunner(ctx, d, { _, _ ->
            if (calls++ == 0) call("remember_items", """{"items":[{"name":"LCM sheet"}]}""")
            else call("finish", """{"summary":"Done","evidence":"LCM sheet"}""")
        }, 0)
        val result = runner.run("Download all files", cfg, {}, { true })
        assertTrue(result.startsWith("Incomplete:"))
    }

    @Test fun bulkTaskGetsLongerBoundedBudget() {
        assertEquals(120, TaskPolicy.stepLimit("Aman chat se saare files download karo"))
        assertEquals(40, TaskPolicy.stepLimit("Open Telegram"))
    }

    @Test fun staleCheckCanAvoidAnExtraImageCapture() = runBlocking {
        val d = FakeDevice(observation())
        d.onStableCheck = { d.screen.stableKey() }
        var calls = 0
        val runner = ActionRunner(ctx, d, { _, _ ->
            if (calls++ == 0) call("click_text", """{"text":"Open","exact":true}""")
            else LlmToolResponse("Done", emptyList())
        }, 0)
        runner.run("Open it", cfg, {}, { true })
        assertEquals(1, d.actions)
        assertEquals(1, d.cheapChecks)
        assertEquals(2, d.observations)
    }

    @Test fun finalSendRequiresConfirmationAndCancellationStopsIt() = runBlocking {
        val d = FakeDevice(observation("#0 t=Send b=0,0,80,80"))
        val waiting = CompletableDeferred<Unit>()
        val runner = ActionRunner(ctx, d, { _, _ -> call("click_text", """{"text":"Send"}""") }, 0)
        val job = launch {
            runner.run("Send it", cfg, {}, { waiting.complete(Unit); awaitCancellation() })
        }
        waiting.await()
        job.cancelAndJoin()
        assertEquals(0, d.actions)
    }

    @Test fun finishNeedsVisibleEvidenceAfterAnAction() = runBlocking {
        val d = FakeDevice(observation())
        var calls = 0
        val runner = ActionRunner(ctx, d, { _, _ ->
            if (calls++ == 0) call("click_text", """{"text":"Open"}""")
            else call("finish", """{"summary":"Opened it","evidence":"Success"}""")
        }, 0)
        val result = runner.run("Open it", cfg, {}, { true })
        assertEquals(1, d.actions)
        assertTrue(result.startsWith("Unverified:"))
    }

    @Test fun duplicateTargetStopsForClarification() = runBlocking {
        val d = FakeDevice(observation()).apply { result = "AMBIGUOUS_TEXT: two matches" }
        val runner = ActionRunner(ctx, d, { _, _ -> call("click_text", """{"text":"Amma"}""") }, 0)
        val result = runner.run("Open Amma", cfg, {}, { true })
        assertEquals(1, d.actions)
        assertTrue(result.startsWith("Clarification needed:"))
    }

    @Test fun toolResponseAndFreshImageReachNextDecision() = runBlocking {
        val d = FakeDevice(observation())
        var calls = 0
        var sawResult = false
        val runner = ActionRunner(ctx, d, { messages, _ ->
            if (calls++ == 0) call("click_text", """{"text":"Open"}""")
            else {
                sawResult = messages.toString().contains("tool_call_id") &&
                    messages.getJSONObject(messages.length() - 1).getJSONArray("content")
                        .getJSONObject(1).getString("type") == "image_url" &&
                    messages.getJSONObject(messages.length() - 1).getJSONArray("content")
                        .getJSONObject(1).getJSONObject("image_url").getString("detail") == "low"
                LlmToolResponse("Now the app is open.", emptyList())
            }
        }, 0)
        runner.run("Open it", cfg, {}, { true })
        assertTrue(sawResult)
        assertEquals(1, d.actions)
    }

    @Test fun imageCoordinatesMapToWindowAndRejectOutOfRange() {
        val shot = observation()
        assertEquals(10 to 20, shot.imageToDisplay(0, 0))
        assertEquals(60 to 120, shot.imageToDisplay(25, 50))
        assertThrows(IllegalArgumentException::class.java) { shot.imageToDisplay(50, 0) }
        assertNotEquals(shot.stableKey(), observation(rotation = 1).stableKey())
        assertEquals(shot.stableKey(), observation(jpeg = "different-frame").stableKey())
    }

    @Test fun operatorWindowsAreRestoredAfterExecution() = runBlocking {
        val device = FakeDevice(observation())
        var calls = 0
        var hidden = false
        var actionSawHiddenWindows = false
        val runner = ActionRunner(
            ctx,
            device,
            { _, _ ->
                if (calls++ == 0) call("click_text", """{"text":"Open"}""")
                else LlmToolResponse("Done", emptyList())
            },
            0,
            beforeExecute = { hidden = true },
            afterExecute = { hidden = false }
        )
        device.onExecute = { actionSawHiddenWindows = hidden }
        runner.run("Open it", cfg, {}, { true })
        assertTrue(actionSawHiddenWindows)
        assertFalse(hidden)
    }

    @Test fun completeChatExportsAsReadableText() {
        val session = Session(
            "id", "provider", "model", "My chat", 1L,
            mutableListOf(Turn("user", "Open Telegram"), Turn("assistant", "Opened it"))
        )
        val text = SessionStore.exportText(session)
        assertTrue(text.contains("Title: My chat"))
        assertTrue(text.contains("[USER]\nOpen Telegram"))
        assertTrue(text.contains("[AI]\nOpened it"))
    }

    @Test fun onlyFinalActionsNeedConfirmation() {
        val shot = observation("#0 t=Send b=0,0,80,80")
        assertFalse(SafetyPolicy.requiresConfirmation(Proposal("click_text", JSONObject("""{"text":"Open"}"""), ""), shot))
        assertTrue(SafetyPolicy.requiresConfirmation(Proposal("click_text", JSONObject("""{"text":"Send"}"""), ""), shot))
        assertTrue(SafetyPolicy.requiresConfirmation(Proposal("click_text", JSONObject("""{"text":"Payment"}"""), ""), shot))
        assertTrue(SafetyPolicy.requiresConfirmation(Proposal("click_text", JSONObject("""{"text":"Confirm"}"""), ""),
            observation("#0 t=Payment b=0,0,80,80\n#1 t=Confirm b=0,80,80,160")))
        assertTrue(SafetyPolicy.requiresConfirmation(Proposal("tap", JSONObject("""{"x":1,"y":1}"""), ""), shot))
    }

    @Test fun apiKeysAreEncrypted() {
        val store = SettingsStore(ctx)
        store.upsertProvider(cfg)
        store.setActiveProvider(cfg.id)
        val disk = ctx.getSharedPreferences("agentbubble", Context.MODE_PRIVATE).getString("providers", "")!!
        assertFalse(disk.contains("\"key\""))
        assertEquals("key", store.activeProvider()?.apiKey)
    }

    @Test fun screenshotRateLimitRetriesAndReportsTheRealFailure() {
        assertTrue(ScreenCapturePolicy.shouldRetry(3, 0))
        assertTrue(ScreenCapturePolicy.shouldRetry(3, 1))
        assertFalse(ScreenCapturePolicy.shouldRetry(3, 2))
        assertFalse(ScreenCapturePolicy.shouldRetry(6, 0))
        assertEquals(750L, ScreenCapturePolicy.retryDelayMs(0))
        assertEquals(1500L, ScreenCapturePolicy.retryDelayMs(1))
        assertTrue(ScreenCapturePolicy.failureMessage(3).contains("rate limit", ignoreCase = true))
        assertTrue(ScreenCapturePolicy.failureMessage(6).contains("secure", ignoreCase = true))
        assertTrue(ScreenCapturePolicy.mayFallbackToDisplay(1))
        assertTrue(ScreenCapturePolicy.mayFallbackToDisplay(5))
        assertFalse("secure windows must never use display fallback",
            ScreenCapturePolicy.mayFallbackToDisplay(6))
    }

    @Test fun displayFallbackCropsToTargetWindow() {
        assertEquals(Rect(50, 100, 150, 300),
            CaptureGeometry.cropRect(Rect(100, 200, 300, 600), 400, 800, 200, 400))
        assertThrows(IllegalStateException::class.java) {
            CaptureGeometry.cropRect(Rect(500, 0, 600, 100), 400, 800, 200, 400)
        }
    }

    @Test fun observationRestoresOverlaysWhenCaptureFails() = runBlocking {
        var hidden = false
        var restored = false
        val brokenDevice = object : ActionDevice {
            override suspend fun observe(): ScreenObservation =
                throw IllegalStateException("Capture backend failed")
            override suspend fun execute(proposal: Proposal, observed: ScreenObservation): String =
                error("No action should run")
        }
        val runner = ActionRunner(ctx, brokenDevice,
            { _, _ -> error("No API call expected") }, 0,
            beforeObserve = { hidden = true },
            afterObserve = { restored = hidden; hidden = false })
        try {
            runner.run("What is on screen?", cfg, {}, { false })
            fail("Expected capture failure")
        } catch (_: IllegalStateException) {
            assertTrue(restored)
            assertFalse(hidden)
        }
    }

    @Test fun treeOnlyScreenCanUseTextButCannotTapCoordinates() = runBlocking {
        val screen = observation(jpeg = "")
        val device = FakeDevice(screen)
        var calls = 0
        val runner = ActionRunner(ctx, device, { _, _ ->
            when (calls++) {
                0 -> call("tap", """{"x":1,"y":1}""")
                1 -> call("click_text", """{"text":"Open","exact":true}""")
                else -> LlmToolResponse("Done", emptyList())
            }
        }, 0)
        runner.run("Open it", cfg, {}, { false })
        assertEquals(1, device.actions)
    }

    @Test fun taskModeRequiresAllCapabilitiesForTheCurrentCredentials() {
        val store = SettingsStore(ctx)
        assertFalse(store.hasTaskCapabilities(cfg))
        store.setCapability(cfg, "chat", true)
        store.setCapability(cfg, "image", true)
        assertFalse(store.hasTaskCapabilities(cfg))
        store.setCapability(cfg, "tools", true)
        assertTrue(store.hasTaskCapabilities(cfg))
        assertFalse(store.hasTaskCapabilities(cfg.copy(model = "other")))
        assertFalse(store.hasTaskCapabilities(cfg.copy(apiKey = "rotated")))
    }
}
