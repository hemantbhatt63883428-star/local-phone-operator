package com.agentbubble

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.agentbubble.agent.AgentEngine
import com.agentbubble.data.ProviderConfig
import com.agentbubble.data.SettingsStore
import com.agentbubble.ui.ChatPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app is now a floating chat bubble with one job: talk to the selected model.
 * These tests lock exactly that in — plus the things a user would notice breaking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupTest {
    @org.junit.Before fun prepareVault() {
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        com.agentbubble.data.SecretVault.keyProvider = { key }
    }


    private fun cfg(server: MockWebServer, model: String = "my-model") = ProviderConfig(
        id = "p1",
        name = "Mock",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test-key",
        model = model
    )

    // ------------------------------------------------------------------ provider + model list

    /** The AI is stored as base URL + key + picked model, and nothing else is needed. */
    @Test
    fun providerRoundTripsThroughSettings() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())

        val p = ProviderConfig(
            id = "p1", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-test", model = "google/gemini-2.0-flash-001"
        )
        store.upsertProvider(p)

        val again = SettingsStore(ctx).activeProvider()
        assertNotNull(again)
        assertEquals("sk-test", again!!.apiKey)
        assertEquals("google/gemini-2.0-flash-001", again.model)
        assertEquals("https://openrouter.ai/api/v1", again.baseUrl)
    }

    @Test
    fun toolChatReadsReasoningProviderFallbackText() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"Question 53 answer"},"finish_reason":"stop"}]}"""
            ))
            val response = com.agentbubble.net.LlmClient(cfg(server)).chatWithTools(
                org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "Solve q53")),
                org.json.JSONArray()
            )
            assertEquals("Question 53 answer", response.text)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun emptyToolResponseIsRetriedOnceWithLargerBudget() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":""},"finish_reason":"length"}]}"""
            ))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":"Recovered answer"},"finish_reason":"stop"}]}"""
            ))
            val response = com.agentbubble.net.LlmClient(cfg(server)).chatWithTools(
                org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "Solve q53")),
                org.json.JSONArray()
            )
            assertEquals("Recovered answer", response.text)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    /** The model list comes from the provider itself — never a hard-coded list. */
    @Test
    fun modelListComesFromTheProvider() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"data":[{"id":"google/gemini-2.0-flash-001"},
                            {"id":"openai/gpt-4o-mini"},
                            {"id":"qwen/qwen-2.5-72b-instruct"}]}"""
                    )
            )
            val models = com.agentbubble.net.LlmClient(cfg(server)).listModels()
            assertEquals(3, models.size)
            assertEquals(
                listOf("google/gemini-2.0-flash-001", "openai/gpt-4o-mini", "qwen/qwen-2.5-72b-instruct"),
                models.map { it.id }
            )
        } finally {
            server.shutdown()
        }
    }

    /** Searching the list must be a plain, case-insensitive filter. */
    @Test
    fun modelListCanBeSearched() {
        val all = listOf(
            "google/gemini-2.0-flash-001",
            "openai/gpt-4o-mini",
            "qwen/qwen-2.5-72b-instruct",
            "meta-llama/llama-3.3-70b-instruct"
        )
        fun search(q: String) = all.filter { it.lowercase().contains(q.lowercase()) }

        assertEquals(1, search("gemini").size)
        assertEquals("openai/gpt-4o-mini", search("GPT").single())
        assertEquals(2, search("instruct").size)
        assertTrue(search("nothing-like-this").isEmpty())
    }

    /** A bad key must produce a clear message, not a crash. */
    @Test
    fun modelListFailureIsReported() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
            val failed = try {
                com.agentbubble.net.LlmClient(cfg(server)).listModels()
                false
            } catch (_: Throwable) {
                true
            }
            assertTrue("a rejected key must be reported", failed)
        } finally {
            server.shutdown()
        }
    }

    // ------------------------------------------------------------------ chat

    /** A normal message gets a reply and both sides stay in the conversation. */
    @Test
    fun chatSendsAndReceives() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"choices":[{"message":{"role":"assistant","content":"Namaste! Kaise ho?"},"finish_reason":"stop"}]}"""
                    )
            )
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val store = SettingsStore(ctx)
            val replies = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val engine = AgentEngine(
                cfg(server), store,
                object : AgentEngine.Listener {
                    override fun onAssistantText(text: String) { replies.add(text) }
                    override fun onStatus(text: String) {}
                    override fun onError(message: String) { errors.add(message) }
                }
            )
            engine.send("hello")
            assertTrue("no errors expected: $errors", errors.isEmpty())
            assertEquals(1, replies.size)
            assertTrue(replies[0].contains("Namaste"))
            assertTrue("history must hold the exchange", engine.historySize >= 3)

            // and nothing about tools or screen reading is ever sent
            val body = requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) {
                "Chat request never reached the provider"
            }.body.readUtf8()
            assertTrue(body.contains("\"model\":\"my-model\""))
            assertFalse("tools must not be sent at all", body.contains("tools"))
            assertFalse(body.contains("image_url"))
        } finally {
            server.shutdown()
        }
    }

    /** With no model selected the user is told what to do instead of getting a failed request. */
    @Test
    fun chatWithoutAModelExplainsItself() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        val errors = mutableListOf<String>()
        val engine = AgentEngine(
            ProviderConfig(id = "x", name = "x", baseUrl = "", apiKey = "", model = ""),
            store,
            object : AgentEngine.Listener {
                override fun onAssistantText(text: String) {}
                override fun onStatus(text: String) {}
                override fun onError(message: String) { errors.add(message) }
            }
        )
        engine.send("hi")
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Settings"))
    }

    /** A failed request is reported and the conversation is not corrupted. */
    @Test
    fun chatFailureIsReportedAndHistoryStaysClean() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val errors = mutableListOf<String>()
            val engine = AgentEngine(
                cfg(server), SettingsStore(ctx),
                object : AgentEngine.Listener {
                    override fun onAssistantText(text: String) {}
                    override fun onStatus(text: String) {}
                    override fun onError(message: String) { errors.add(message) }
                }
            )
            engine.send("hello")
            assertEquals(1, errors.size)
            assertFalse("a failed turn must not stay in the history", errors[0].isEmpty())
            assertTrue("only the system prompt should remain", engine.historySize <= 2)
        } finally {
            server.shutdown()
        }
    }

    /** The error text tells the user what to fix. */
    @Test
    fun errorMessagesAreHuman() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        suspend fun say(code: Int): String {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setResponseCode(code).setBody("x"))
                val errors = mutableListOf<String>()
                AgentEngine(
                    cfg(server), SettingsStore(ctx),
                    object : AgentEngine.Listener {
                        override fun onAssistantText(text: String) {}
                        override fun onStatus(text: String) {}
                        override fun onError(message: String) { errors.add(message) }
                    }
                ).send("hi")
                return errors.firstOrNull() ?: ""
            } finally {
                server.shutdown()
            }
        }
        assertTrue(say(401).contains("API key"))
        assertTrue(say(404).contains("not found"))
        assertTrue(say(429).contains("Rate limited"))
    }

    // ------------------------------------------------------------------ bubble + chat window

    /** The floating bubble starts and the chat opens/closes like it always did. */
    @Test
    fun bubbleStartsAndChatOpensAndCloses() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        assertTrue("the bubble window exists", service.windowCountForTest() >= 0)
        service.openChat()
        assertTrue("the chat is open", com.agentbubble.service.BubbleService.chatOpen)
        service.closeChat()
        assertFalse("the chat closed again", com.agentbubble.service.BubbleService.chatOpen)
    }

    /** The chat panel builds, and its header shows the missing model instead of pretending. */
    @Test
    fun chatPanelBuildsAndAsksForAModel() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())
        val panel = ChatPanel(
            ctx, CoroutineScope(Dispatchers.Main), store,
            {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}
        )
        assertNotNull(panel.root)
        panel.onShown()
        panel.refreshProvider()
        panel.destroy()
    }

    /** Changing the model must reset the conversation and show the new name. */
    @Test
    fun changingTheModelStartsAFreshChat() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())
        store.upsertProvider(
            ProviderConfig(id = "p1", name = "Mock", baseUrl = "http://x/v1", apiKey = "k", model = "a")
        )
        val panel = ChatPanel(
            ctx, CoroutineScope(Dispatchers.Main), store,
            {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}
        )
        panel.onShown()
        val second = store.activeProvider()!!.copy(model = "b")
        store.upsertProvider(second)
        panel.refreshProvider()
        panel.destroy()
        assertEquals("b", store.activeProvider()!!.model)
    }

    // --------------------------------------------- round 12: window, keyboard, saved chats

    /** The chat is a medium card that floats in the middle — not a sidebar stuck to the right. */
    @Test
    fun chatWindowOpensMediumAndFloating() {
        val p = com.agentbubble.ui.PanelGeometry.floatingPlacement(
            screenW = 1200, screenH = 2000, density = 1f, topInset = 60, bottomInset = 48
        )
        assertTrue("never the full width", p.width < 1200 * 0.9f)
        assertTrue("wide enough to be usable", p.width >= 260)
        assertTrue("never the full height", p.height < 2000 * 0.8f)
        assertTrue("tall enough to be usable", p.height >= 360)
        assertEquals("floats in the middle, not glued to an edge", (1200 - p.width) / 2, p.x)
        assertTrue("kept clear of the status bar", p.y > 60)
        assertTrue("kept clear of the navigation bar", p.y + p.height <= 2000 - 48)

        // a size the user dragged earlier wins
        val dragged = com.agentbubble.ui.PanelGeometry.floatingPlacement(1200, 2000, 1f, 60, 48, 420, 900)
        assertEquals(420, dragged.width)
        assertEquals(900, dragged.height)

        // and so does a place the user dragged it to
        val moved = com.agentbubble.ui.PanelGeometry.floatingPlacement(
            1200, 2000, 1f, 60, 48, 420, 900, 100, 300
        )
        assertEquals(100, moved.x)
        assertEquals(300, moved.y)
    }

    /** The keyboard only pads the part of the card it really covers. */
    @Test
    fun theKeyboardOnlyPadsWhatItCovers() {
        val g = com.agentbubble.ui.PanelGeometry
        assertEquals("a card well above the keyboard needs no padding at all", 0, g.keyboardOverlap(600, 2000, 800))
        assertEquals("the whole keyboard covers a card that reaches the bottom", 800, g.keyboardOverlap(2000, 2000, 800))
        assertEquals("partially covered", 200, g.keyboardOverlap(1400, 2000, 800))
        assertEquals("no keyboard, no overlap", 0, g.keyboardOverlap(2000, 2000, 0))
    }

    /** Dragging the card moves it, it cannot leave the screen, and the place is remembered. */
    @Test
    fun movingTheChatCardIsRemembered() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        store.panelX = -1
        store.panelY = -1
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val x0 = service.panelXForTest()
        val y0 = service.panelYForTest()

        service.movePanelForTest(-100, -50)
        val x1 = service.panelXForTest()
        val y1 = service.panelYForTest()
        assertTrue("the card follows the finger to the left", x1 < x0)
        assertTrue("and cannot leave the screen", x1 >= 0)

        service.movePanelForTest(8, 6)
        assertEquals("it follows by exactly what the finger moved", x1 + 8, service.panelXForTest())
        assertEquals(y1 + 6, service.panelYForTest())
        val x2 = service.panelXForTest()
        val y2 = service.panelYForTest()

        // a monstrous drag cannot push it off the screen
        service.movePanelForTest(-10000, -10000)
        assertTrue("still on screen", service.panelXForTest() >= 0)
        assertTrue("still below the status bar", service.panelYForTest() >= 0)
        service.movePanelForTest(10000, 10000)
        assertTrue("never off the right edge", service.panelXForTest() + service.sidebarWidthForTest() <= ctx.resources.displayMetrics.widthPixels)
        assertTrue("never off the bottom", service.panelYForTest() + service.panelHeightForTest() <= ctx.resources.displayMetrics.heightPixels)
        val x3 = service.panelXForTest()
        val y3 = service.panelYForTest()


        service.closeChat()
        service.openChat()
        assertEquals("the next chat opens where the user left it", x3, service.panelXForTest())
        assertEquals(y3, service.panelYForTest())
        service.closeChat()
        store.panelX = -1
        store.panelY = -1
    }

    /** "Reset layout" puts the card back in the middle of the screen. */
    @Test
    fun resetLayoutPutsTheCardBackInTheMiddle() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        store.panelX = -1
        store.panelY = -1
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        service.movePanelForTest(30, 30)
        assertTrue(service.panelXForTest() > 10)

        store.panelX = -1
        store.panelY = -1
        service.onStartCommand(
            android.content.Intent(ctx, com.agentbubble.service.BubbleService::class.java)
                .setAction(com.agentbubble.service.BubbleService.ACTION_REFRESH),
            0, 0
        )
        val sw = ctx.resources.displayMetrics.widthPixels
        assertEquals(
            "back in the middle",
            (sw - service.sidebarWidthForTest()) / 2,
            service.panelXForTest()
        )
        service.closeChat()
    }

    /** The chat list lives inside the card and shows this model's chats. */
    @Test
    fun theChatListShowsThisModelsChats() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        com.agentbubble.data.SessionStore.clearAll(ctx)
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())
        store.upsertProvider(
            ProviderConfig(id = "p1", name = "Mock", baseUrl = "http://x/v1", apiKey = "k", model = "model-one")
        )
        val a = com.agentbubble.data.SessionStore.newSession("p1", "model-one")
        com.agentbubble.data.SessionStore.append(ctx, a, "user", "first chat about frogs")
        val b = com.agentbubble.data.SessionStore.newSession("p1", "model-one")
        com.agentbubble.data.SessionStore.append(ctx, b, "user", "second chat about php")
        val other = com.agentbubble.data.SessionStore.newSession("p1", "model-two")
        com.agentbubble.data.SessionStore.append(ctx, other, "user", "a different model's chat")
        a.updatedAt = 1_000_000L
        b.updatedAt = 2_000_000L
        other.updatedAt = 3_000_000L
        com.agentbubble.data.SessionStore.saveAll(ctx, listOf(a, b, other))

        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val root = service.panelRootForTest()!!
        root.findViewById<View>(R.id.btnVision).performClick()

        assertEquals("the list is shown", View.VISIBLE, root.findViewById<View>(R.id.historyBox).visibility)
        assertEquals("the chat is hidden", View.GONE, root.findViewById<View>(R.id.conversationBox).visibility)
        assertEquals(
            "only this model's two chats",
            2,
            root.findViewById<android.view.ViewGroup>(R.id.historyList).childCount
        )
        val title = root.findViewById<android.widget.TextView>(R.id.historyTitle).text.toString()
        assertTrue("the list says which model it belongs to: $title", title.contains("model-one"))
        service.closeChat()
    }

    /** Tapping a chat in the list opens it with its messages. */
    @Test
    fun tappingAChatInTheListOpensIt() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        com.agentbubble.data.SessionStore.clearAll(ctx)
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())
        store.upsertProvider(
            ProviderConfig(id = "p1", name = "Mock", baseUrl = "http://x/v1", apiKey = "k", model = "model-one")
        )
        val older = com.agentbubble.data.SessionStore.newSession("p1", "model-one")
        com.agentbubble.data.SessionStore.append(ctx, older, "user", "older question")
        com.agentbubble.data.SessionStore.append(ctx, older, "assistant", "older answer")
        val newer = com.agentbubble.data.SessionStore.newSession("p1", "model-one")
        com.agentbubble.data.SessionStore.append(ctx, newer, "user", "newest question")
        older.updatedAt = 1_000_000L
        newer.updatedAt = 2_000_000L
        com.agentbubble.data.SessionStore.saveAll(ctx, listOf(older, newer))

        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val root = service.panelRootForTest()!!
        val list = root.findViewById<android.view.ViewGroup>(R.id.historyList)
        root.findViewById<View>(R.id.btnVision).performClick()
        assertEquals(2, list.childCount)

        list.getChildAt(1).performClick()   // the list is newest first, so this is the older chat
        assertEquals("back to the conversation", View.GONE, root.findViewById<View>(R.id.historyBox).visibility)
        val msgList = root.findViewById<android.view.ViewGroup>(R.id.msgList)
        assertEquals("the older chat comes back with both messages", 2, msgList.childCount)
        service.closeChat()
    }

    /** Deleting a chat removes it from the list and from the phone. */
    @Test
    fun deletingAChatRemovesIt() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        com.agentbubble.data.SessionStore.clearAll(ctx)
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())
        store.upsertProvider(
            ProviderConfig(id = "p1", name = "Mock", baseUrl = "http://x/v1", apiKey = "k", model = "model-one")
        )
        val keep = com.agentbubble.data.SessionStore.newSession("p1", "model-one")
        com.agentbubble.data.SessionStore.append(ctx, keep, "user", "keep me")
        val drop = com.agentbubble.data.SessionStore.newSession("p1", "model-one")
        com.agentbubble.data.SessionStore.append(ctx, drop, "user", "delete me")
        keep.updatedAt = 2_000_000L    // this one is open
        drop.updatedAt = 1_000_000L
        com.agentbubble.data.SessionStore.saveAll(ctx, listOf(keep, drop))

        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val root = service.panelRootForTest()!!
        root.findViewById<View>(R.id.btnVision).performClick()
        val list = root.findViewById<android.view.ViewGroup>(R.id.historyList)
        assertEquals(2, list.childCount)

        val row = list.getChildAt(1) as android.view.ViewGroup
        row.getChildAt(2).performClick()   // the ✕ of that row (after the download button)

        assertEquals("gone from the phone", 1, com.agentbubble.data.SessionStore.forModel(ctx, "p1", "model-one").size)
        assertEquals("gone from the list", 1, list.childCount)

        // deleting the chat that is open must not leave an empty card behind
        (list.getChildAt(0) as android.view.ViewGroup).getChildAt(2).performClick()
        assertEquals("both are gone", 0, com.agentbubble.data.SessionStore.forModel(ctx, "p1", "model-one").size)
        assertEquals("the list says so", 1, list.childCount)
        assertEquals("back to the conversation", View.GONE, root.findViewById<View>(R.id.historyBox).visibility)
        service.closeChat()
    }

    /** An empty list explains itself instead of looking broken. */
    @Test
    fun anEmptyChatListExplainsItself() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        com.agentbubble.data.SessionStore.clearAll(ctx)
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())
        store.upsertProvider(
            ProviderConfig(id = "p1", name = "Mock", baseUrl = "http://x/v1", apiKey = "k", model = "model-one")
        )
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val root = service.panelRootForTest()!!
        root.findViewById<View>(R.id.btnVision).performClick()
        val list = root.findViewById<android.view.ViewGroup>(R.id.historyList)
        assertEquals("one hint row", 1, list.childCount)
        val hint = (list.getChildAt(0) as android.widget.TextView).text.toString()
        assertTrue("the hint explains how chat history works: $hint", hint.contains("saved", true))
        service.closeChat()
    }

    /** The keyboard must never hide the input row — and must never make the window move. */
    @Test
    fun keyboardPadsTheCardInsteadOfMovingTheWindow() {
        // enough room: the whole keyboard height becomes padding
        assertEquals(
            900,
            com.agentbubble.ui.PanelGeometry.keyboardPadding(1240, 900, 260)
        )
        // a very small window: keep a minimum card instead of collapsing it
        assertEquals(
            200,
            com.agentbubble.ui.PanelGeometry.keyboardPadding(460, 900, 260)
        )
        // keyboard closed
        assertEquals(0, com.agentbubble.ui.PanelGeometry.keyboardPadding(1240, 0, 260))
    }

    /** A noisy signal must never be able to shake the UI. */
    @Test
    fun keyboardTrackerIgnoresJitter() {
        val t = com.agentbubble.ui.KeyboardTracker()

        // a wobbling value: open / closed / open / closed ... nothing may be applied
        listOf(0, 900, 0, 900, 0, 900, 0).forEach { t.sample(it) }
        assertEquals("jitter must not open the keyboard state", 0, t.height)
        assertFalse(t.isOpen)

        // a real keyboard: the same value twice in a row
        t.sample(900)
        assertFalse("one sample is not enough", t.isOpen)
        t.sample(900)
        assertTrue("two matching samples confirm it", t.isOpen)
        assertEquals(900, t.height)

        // and after that, jitter is still ignored
        t.sample(0)
        t.sample(900)
        assertTrue("still open", t.isOpen)

        // a real close needs three matching samples
        t.sample(0)
        t.sample(0)
        assertTrue("two samples are not enough to close", t.isOpen)
        t.sample(0)
        assertFalse("three samples close it", t.isOpen)

        // tiny values are noise, not a keyboard
        t.sample(40)
        t.sample(40)
        t.sample(40)
        assertFalse("40px is not a keyboard", t.isOpen)
    }

    /** Tapping outside closes the chat — but never while the keyboard is up. */
    @Test
    fun tappingOutsideClosesTheChatUnlessTyping() {
        val g = com.agentbubble.ui.PanelGeometry
        assertTrue(
            "an outside tap closes the window",
            g.shouldCloseOnTouch(android.view.MotionEvent.ACTION_OUTSIDE, keyboardVisible = false)
        )
        assertFalse(
            "keyboard keys are 'outside' too — typing must not close the chat",
            g.shouldCloseOnTouch(android.view.MotionEvent.ACTION_OUTSIDE, keyboardVisible = true)
        )
        assertFalse(
            "normal touches inside the window never close it",
            g.shouldCloseOnTouch(android.view.MotionEvent.ACTION_DOWN, keyboardVisible = false)
        )
    }

    /** The overlay window must be able to take input, or no keyboard can ever appear. */
    @Test
    fun chatWindowCanReceiveTheKeyboard() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val flags = service.panelFlagsForTest()
        assertEquals(
            "FLAG_NOT_FOCUSABLE would block the keyboard",
            0,
            flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        assertTrue(
            "touches beside the card must still reach the app behind it",
            flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0
        )
        assertTrue(
            "outside taps are needed to close the chat",
            flags and android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0
        )
    }

    /** While the keyboard is open the card is padded from the inside — the window never moves. */
    @Test
    fun keyboardPadsTheCardAndNeverMovesTheWindow() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val yBefore = service.panelYForTest()
        val hBefore = service.panelHeightForTest()

        service.keyboardSampleForTest(900)
        assertEquals("the window must not move", yBefore, service.panelYForTest())
        assertEquals("the window must not resize", hBefore, service.panelHeightForTest())
        assertEquals(900, service.keyboardHeightForTest())
        val pad = service.panelRootForTest()?.paddingBottom ?: 0
        assertTrue("the content must be pushed above the keyboard", pad > 0)

        service.keyboardSampleForTest(0, times = 3)
        assertEquals("keyboard closed", 0, service.keyboardHeightForTest())
        assertEquals("padding is released", 0, service.panelRootForTest()?.paddingBottom ?: -1)
    }

    /** Opening and closing repeatedly must never leave a second chat window behind. */
    @Test
    fun openingAndClosingChatNeverLeavesStaleWindows() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        repeat(3) {
            service.openChat()
            assertTrue(com.agentbubble.service.BubbleService.chatOpen)
            service.closeChat(viaOutsideTap = true)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(com.agentbubble.service.BubbleService.chatOpen)
        }
        assertTrue("only the bubble may remain", service.windowCountForTest() <= 1)
    }

    /** A tap beside the card closes the chat; the same guard must not fire while typing. */
    @Test
    fun outsideTapClosesTheChatExceptWhileTyping() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val root = service.panelRootForTest()!!
        val lp = android.view.WindowManager.LayoutParams::class.java
        assertEquals(0, service.panelFlagsForTest() and lp.getField("FLAG_NOT_FOCUSABLE").getInt(null))

        // keyboard open -> the chat must survive (keyboard keys also look like "outside" taps)
        service.keyboardSampleForTest(900)
        assertTrue(com.agentbubble.service.BubbleService.chatOpen)
        service.closeChat()
    }

    /** Changing the model in Settings updates the open chat immediately. */
    @Test
    fun modelChangeRefreshesTheOpenChat() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())
        store.upsertProvider(
            ProviderConfig(id = "p1", name = "Mock", baseUrl = "http://x/v1", apiKey = "k", model = "model-one")
        )
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        assertTrue(service.panelTitleForTest().contains("model-one"))

        store.upsertProvider(store.activeProvider()!!.copy(model = "model-two"))
        service.onStartCommand(
            android.content.Intent(ctx, com.agentbubble.service.BubbleService::class.java)
                .setAction(com.agentbubble.service.BubbleService.ACTION_REFRESH),
            0, 0
        )
        assertTrue(
            "the header must follow the new model: " + service.panelTitleForTest(),
            service.panelTitleForTest().contains("model-two")
        )
        service.closeChat()
    }

    /** Rotating the phone rebuilds the card instead of leaving a stale, wrongly sized window. */
    @Test
    fun rotationRebuildsTheOpenChat() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        service.onConfigurationChanged(android.content.res.Configuration())
        assertFalse("the old card is removed first", com.agentbubble.service.BubbleService.chatOpen)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(
            java.time.Duration.ofMillis(400)
        )
        assertTrue("and rebuilt", com.agentbubble.service.BubbleService.chatOpen)
        assertTrue(service.windowCountForTest() <= 2)
        service.closeChat()
    }

    /** Tapping the input must focus it — that is what makes the keyboard appear. */
    @Test
    fun tappingTheInputFocusesIt() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val store = SettingsStore(activity)
        store.saveProviders(emptyList())
        val panel = ChatPanel(
            activity, CoroutineScope(Dispatchers.Main), store,
            {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}
        )
        activity.setContentView(panel.root)
        val input = panel.root.findViewById<android.widget.EditText>(R.id.input)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        input.performClick()
        assertTrue("the input must take focus so the keyboard can show", input.isFocused)
        panel.destroy()
    }

    /** Every model keeps its own conversation, and it survives a restart. */
    @Test
    fun chatSessionsAreStoredPerModel() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        com.agentbubble.data.SessionStore.clearAll(ctx)

        val a = com.agentbubble.data.SessionStore.current(ctx, "p1", "model-a")
        com.agentbubble.data.SessionStore.append(ctx, a, "user", "hello model A")
        com.agentbubble.data.SessionStore.append(ctx, a, "assistant", "hi from A")
        assertEquals("the title comes from the first message", "hello model A", a.title)

        val b = com.agentbubble.data.SessionStore.current(ctx, "p1", "model-b")
        assertTrue("a different model starts empty", b.messages.isEmpty())
        com.agentbubble.data.SessionStore.append(ctx, b, "user", "hello model B")

        // same model, second session
        val a2 = com.agentbubble.data.SessionStore.newSession("p1", "model-a")
        com.agentbubble.data.SessionStore.append(ctx, a2, "user", "second chat")
        // pin the clocks: "newest first" must not depend on how fast this machine runs
        a.updatedAt = 1_000_000L
        a2.updatedAt = 2_000_000L
        com.agentbubble.data.SessionStore.saveAll(ctx, listOf(a, a2, b))

        val forA = com.agentbubble.data.SessionStore.forModel(ctx, "p1", "model-a")
        assertEquals("model A has two sessions", 2, forA.size)
        assertEquals("newest first", "second chat", forA[0].title)
        assertEquals("model B is separate", 1, com.agentbubble.data.SessionStore.forModel(ctx, "p1", "model-b").size)

        // continuing restarts from the newest session of that model
        val continued = com.agentbubble.data.SessionStore.current(ctx, "p1", "model-a")
        assertEquals("second chat", continued.title)
        assertEquals(1, continued.messages.size)

        // and it is really on disk
        val reloaded = com.agentbubble.data.SessionStore.forModel(ctx, "p1", "model-a")
        assertEquals(2, reloaded.size)
        assertEquals(2, reloaded[1].messages.size)

        com.agentbubble.data.SessionStore.delete(ctx, a.id)
        assertEquals(1, com.agentbubble.data.SessionStore.forModel(ctx, "p1", "model-a").size)
        com.agentbubble.data.SessionStore.clearAll(ctx)
    }

    /** A stored conversation is handed back to the model, so the chat continues. */
    @Test
    fun storedHistoryIsGivenBackToTheModel() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":"yes, 4"},"finish_reason":"stop"}]}""")
            )
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val cfg = cfg(server)
            val engine = AgentEngine(
                cfg, SettingsStore(ctx),
                object : AgentEngine.Listener {
                    override fun onAssistantText(text: String) {}
                    override fun onStatus(text: String) {}
                    override fun onError(message: String) {}
                }
            )
            engine.loadHistory(
                listOf("user" to "2+2?", "assistant" to "4", "user" to "are you sure?")
            )
            engine.send("really?")
            val body = requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) {
                "History request never reached the provider"
            }.body.readUtf8()
            assertTrue("earlier turns must be sent again", body.contains("2+2?"))
            assertTrue(body.contains("are you sure?"))
            assertTrue(body.contains("really?"))
        } finally {
            server.shutdown()
        }
    }

    /** Long messages must fit the window instead of running off its edge. */
    @Test
    fun longMessagesFitTheWindow() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())
        store.upsertProvider(
            ProviderConfig(id = "p1", name = "Mock", baseUrl = "http://x/v1", apiKey = "k", model = "m")
        )
        val panel = ChatPanel(
            ctx, CoroutineScope(Dispatchers.Main), store,
            {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}
        )
        panel.onShown()
        val root = panel.root
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 700, 900)
        // the greeting is rendered and its width is capped to the window, not a fixed 290dp
        val list = root.findViewById<android.view.ViewGroup>(R.id.msgList)
        assertNotNull(list)
        assertTrue("something was rendered", list!!.childCount >= 1)
        val bubble = (list.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as android.widget.TextView
        assertTrue("bubble must not be wider than the window", bubble.maxWidth in 1..(700 * 2))
        panel.destroy()
    }

    /** The header never cuts the model name in half. */
    @Test
    fun headerShowsTheModelClearly() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())
        store.upsertProvider(
            ProviderConfig(
                id = "p1", name = "OpenRouter", baseUrl = "http://x/v1", apiKey = "k",
                model = "inception/mercury-2.5"
            )
        )
        val panel = ChatPanel(
            ctx, CoroutineScope(Dispatchers.Main), store,
            {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}
        )
        panel.refreshProvider()
        val title = panel.root.findViewById<android.widget.TextView>(R.id.title)
        val subtitle = panel.root.findViewById<android.widget.TextView>(R.id.subtitle)
        assertEquals("the short name is shown, not cut off", "mercury-2.5", title.text.toString())
        assertTrue("the full id stays visible underneath", subtitle.text.toString().contains("inception/mercury-2.5"))
        panel.destroy()
    }

    /** The bubble size and the chat size are remembered. */
    @Test
    fun sizesAreRemembered() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        store.bubbleSizeDp = 78
        store.sidebarWidthPx = 612
        store.sidebarHeightPx = 900
        val again = SettingsStore(ctx)
        assertEquals(78, again.bubbleSizeDp)
        assertEquals(612, again.sidebarWidthPx)
        assertEquals(900, again.sidebarHeightPx)
        again.bubbleSizeDp = 5
        assertEquals("out-of-range sizes are clamped", 36, SettingsStore(ctx).bubbleSizeDp)
    }

    /** Accessibility is service-bound; screen capture does not need MediaProjection permission. */
    @Test
    fun noAutomationPermissionsAreRequested() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(
            ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val perms = info.requestedPermissions?.toList() ?: emptyList()
        assertFalse(
            "accessibility binding is a service permission, not an app request: $perms",
            perms.any { it.contains("BIND_ACCESSIBILITY") }
        )
        assertFalse(
            "media projection must not be needed: $perms",
            perms.any { it.contains("MEDIA_PROJECTION") }
        )
        assertTrue("internet is still needed for the chat", perms.any { it.contains("INTERNET") })
    }

    /** Legacy automation controls stay absent from settings. */
    @Test
    fun settingsOnlyOffersTheModelPicker() {
        val activity = Robolectric.buildActivity(com.agentbubble.ui.MainActivity::class.java)
            .setup().get()
        assertNotNull(activity.findViewById<View>(R.id.btnChooseModel))
        assertNotNull(activity.findViewById<View>(R.id.tvAi))

        // Legacy controls are gone; the new API checks live in RuntimeActivity.
        val ctx = activity as Context
        listOf("btnTeach", "swLive", "rgMode", "btnRunLog", "rgBrain", "rgMethod", "rgQuality", "swNoAi")
            .forEach { name ->
                assertEquals(
                    "the $name control must not exist any more",
                    0,
                    ctx.resources.getIdentifier(name, "id", ctx.packageName)
                )
            }
    }

    /**
     * The window has to keep the header and the prompt visible while typing — on any card size.
     * (This is the rule that decides the padding; it must never fall back to "the card, roughly".)
     */
    @Test
    fun keyboardPaddingAlwaysLeavesRoomForHeaderAndInput() {
        val g = com.agentbubble.ui.PanelGeometry
        val reserve = 120
        assertEquals("a tall card pads the full keyboard", 900, g.keyboardPadding(1240, 900, reserve))
        assertEquals("a short card gives way, keeping the prompt", 240, g.keyboardPadding(360, 900, reserve))
        for (h in listOf(260, 360, 460, 700, 1240, 2000)) {
            val pad = g.keyboardPadding(h, 900, reserve)
            assertTrue("window $h: the prompt stays visible (pad=$pad)", h - pad >= reserve)
        }
        assertEquals("no keyboard, no padding", 0, g.keyboardPadding(1240, 0, reserve))
    }

    /**
     * "Chat is open" is a process-wide flag, but the window belongs to one service instance. A second
     * instance (a service restart) must not be blocked by the flag the first one left behind.
     */
    @Test
    fun aStaleFlagCannotBlockTheChatWindow() {
        val first = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        first.openChat()
        assertTrue("the first chat is open", com.agentbubble.service.BubbleService.chatOpen)

        val second = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        second.openChat()
        assertNotNull("a fresh instance must still be able to open the chat", second.panelRootForTest())
        second.closeChat()
        first.closeChat()
    }

    /**
     * The exact complaint: the keyboard never opened. Opening the chat (and tapping the prompt) must
     * reach the system's InputMethodManager — an overlay window is not given the keyboard for free.
     */
    @Test
    fun tappingThePromptReallyAsksForTheKeyboard() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val shadow = org.robolectric.Shadows.shadowOf(imm)
        assertFalse("nothing asked for the keyboard yet", shadow.isSoftInputVisible)

        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(700))

        val input = service.panelRootForTest()!!.findViewById<android.widget.EditText>(com.agentbubble.R.id.input)
        assertTrue("opening the chat must show the keyboard", shadow.isSoftInputVisible)
        assertTrue("the prompt must keep the system keyboard on focus", input.showSoftInputOnFocus)
        input.performClick()
        assertTrue("tapping the prompt must show the keyboard", shadow.isSoftInputVisible)
        service.closeChat()
    }

    /**
     * The acceptance rule "Enter sends, but never closes the chat": sending a message must leave the
     * window (and the keyboard) exactly where they are.
     */
    @Test
    fun enterSendsAndLeavesTheChatOpen() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val root = service.panelRootForTest()!!
        val list = root.findViewById<android.widget.LinearLayout>(com.agentbubble.R.id.msgList)
        val before = list.childCount
        val input = root.findViewById<android.widget.EditText>(com.agentbubble.R.id.input)

        input.setText("what is on my calendar?")
        input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)

        assertTrue("Enter must not close the chat", com.agentbubble.service.BubbleService.chatOpen)
        assertNotNull("the window must still be there", service.panelRootForTest())
        assertTrue("the message must be added to the conversation", list.childCount > before)
        service.closeChat()
    }

    /** A tap outside closes the chat; the same tap landing on the bubble must not reopen it. */
    @Test
    fun theClosingTapDoesNotReopenTheChat() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        service.closeChat(viaOutsideTap = true)
        service.toggleChat()
        assertFalse("the closing tap must not reopen the chat", com.agentbubble.service.BubbleService.chatOpen)
        assertNull("nothing may be left on screen", service.panelRootForTest())
    }

    /** Tapping the prompt row (not only the text field) must open the keyboard as well. */
    @Test
    fun thePromptRowOpensTheKeyboardOnTap() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val shadow = org.robolectric.Shadows.shadowOf(imm)
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val root = service.panelRootForTest()!!

        imm.hideSoftInputFromWindow(root.windowToken, 0)
        assertFalse("the keyboard is closed again", shadow.isSoftInputVisible)

        root.findViewById<View>(com.agentbubble.R.id.inputRow).performClick()
        assertTrue("tapping the prompt row must open the keyboard", shadow.isSoftInputVisible)
        service.closeChat()
    }

    /**
     * The card must be type-ready the moment it opens and the moment the window takes focus — the user
     * complained about having to tap the prompt again before the keys did anything.
     */
    @Test
    fun thePromptIsReadyToTypeTheMomentTheChatOpens() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(900))

        val input = service.panelRootForTest()!!
            .findViewById<android.widget.EditText>(com.agentbubble.R.id.input)
        assertTrue("the caret must already be in the prompt", input.isFocused)
        assertTrue("and keep the system keyboard", input.showSoftInputOnFocus)

        // the window takes focus a moment after it is added: the caret goes straight back in
        input.clearFocus()
        assertFalse(input.isFocused)
        service.panelWindowFocusForTest()
        assertTrue("the caret comes back when the window gains focus", input.isFocused)

        // …and when the keyboard appears, the prompt is connected to it again
        input.setText("hello")
        service.keyboardSampleForTest(900)
        assertTrue("still focused after the keyboard shows", input.isFocused)
        assertEquals("nothing may eat the typed text", "hello", input.text.toString())
        service.closeChat()
    }

    /** Back dismisses the IME without an automatic focus loop; tapping the prompt reopens it. */
    @Test
    fun pressingBackDoesNotImmediatelyReopenKeyboard() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val root = service.panelRootForTest()!!
        val input = root.findViewById<android.widget.EditText>(com.agentbubble.R.id.input)
        service.keyboardSampleForTest(900)
        service.keyboardSampleForTest(0, times = 4)
        input.clearFocus()
        service.panelWindowFocusForTest()
        assertFalse("a user-dismissed keyboard must stay closed", input.isFocused)
        root.findViewById<View>(com.agentbubble.R.id.inputRow).performClick()
        assertTrue("an explicit prompt tap must reopen the keyboard", input.isFocused)
        service.closeChat()
    }

    /**
     * The window itself must ask the system for the keyboard (STATE_VISIBLE) while still never being
     * resized by it (ADJUST_NOTHING) — that is what keeps the caret ready *and* the card still.
     */
    @Test
    fun theWindowAsksForTheKeyboardWithoutBeingResized() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val mode = service.panelSoftInputModeForTest()
        val lp = android.view.WindowManager.LayoutParams::class.java
        val stateVisible = lp.getField("SOFT_INPUT_STATE_VISIBLE").getInt(null)
        val adjustNothing = lp.getField("SOFT_INPUT_ADJUST_NOTHING").getInt(null)
        assertEquals("the system must raise the keyboard itself", stateVisible, mode and 0x0F)
        assertEquals("but never move or resize the card", adjustNothing, mode and 0xF0)
        service.closeChat()
    }

    /** Coming back from the chat list must leave the caret in the prompt again — ready to type. */
    @Test
    fun comingBackFromTheChatListPutsTheCaretBack() {
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val root = service.panelRootForTest()!!
        val input = root.findViewById<android.widget.EditText>(com.agentbubble.R.id.input)

        root.findViewById<View>(com.agentbubble.R.id.btnVision).performClick()
        assertFalse("typing is not wanted while browsing the list", input.isFocused)

        root.findViewById<View>(com.agentbubble.R.id.btnHistoryBack).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(200))
        assertTrue("back in the chat, the caret is in the prompt again", input.isFocused)
        service.closeChat()
    }

    /** After sending, the prompt is empty, focused and ready for the next message. */
    @Test
    fun afterSendingThePromptIsReadyForTheNextMessage() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        store.saveProviders(emptyList())
        store.upsertProvider(
            ProviderConfig(id = "p1", name = "Mock", baseUrl = "http://x/v1", apiKey = "k", model = "model-one")
        )
        val service = Robolectric.buildService(com.agentbubble.service.BubbleService::class.java)
            .create().get()
        service.openChat()
        val input = service.panelRootForTest()!!
            .findViewById<android.widget.EditText>(com.agentbubble.R.id.input)

        input.setText("first message")
        input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(200))

        assertEquals("the prompt is cleared", "", input.text.toString())
        assertTrue("and ready for the next message", input.isFocused)
        store.saveProviders(emptyList())
        service.closeChat()
    }
}
