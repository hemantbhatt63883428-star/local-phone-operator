# Android source

This is the complete Android Studio project for [Local Phone Operator](../README.md). JDK 17, Android SDK 35 and Gradle 8.14.4 are used in CI. Build with `gradle :app:assembleDebug :app:testDebugUnitTest` from this directory (or use Android Studio's Build APK(s)). The APK is under `app/build/outputs/apk/debug/`.

The app has two entry points to the same task runner: the Chat tab and an accessibility overlay. A single task runs at a time in an application-level coroutine; changing tabs or closing the Activity does not interrupt it. The chat history is stored locally in app-private preferences (last 80 messages). The UI observes runner state to show progress, the current transcript and Stop controls.

The planner can be a tool-capable imported LiteRT-LM model or an OpenAI-compatible Chat Completions endpoint. Screenshot-based vision is optional. Local planner files are user-provided; no model weights or API keys are bundled. Keys are stored using Android Keystore-backed encryption. API mode sends task and accessibility-screen context to the selected server.

Actions: inspect UI tree, open app, click, type, scroll, tap, swipe, Back, Home, wait, optional visual inspect and finish. The sensitive action gate is off by default. Password/PIN fields cannot be filled. This is a supervised prototype, not a way around protected flows or a guarantee of reliable automation across all apps.
