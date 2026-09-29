# Local Phone Operator

Local-first Android AI operator prototype for Android 11+ using Android Accessibility and Google's LiteRT-LM.

## Verified build

GitHub Actions build #6 completed successfully on 2026-09-30 (India time):

- Android debug APK: built successfully
- Unit tests: passed
- Artifact: `LocalPhoneOperator-debug-apk`

Build run:
https://github.com/hemantbhatt63883428-star/local-phone-operator/actions/runs/36617352683

## What it does

- Floating AI bubble
- Natural-language task prompt
- Accessibility UI-tree inspection
- Generic tap / type / scroll / swipe / Back / Home / open-app tools
- Optional screenshot + local vision-model fallback
- Local LiteRT-LM planner model
- Long multi-step task loop
- Stop control
- Password/PIN fields blocked
- Sensitive side-effect controls gated by an owner toggle

## Phone setup

1. Install the debug APK from the successful GitHub Actions artifact.
2. Open **Local Phone Operator**.
3. Tap **Open Accessibility Settings** and enable **Local Phone Operator**.
4. Import a planner `.litertlm` model with tool/function-calling capability.
5. Optionally import a multimodal vision `.litertlm` model.
6. Tap the floating **AI** bubble, enter a task, and press **Run**.

Example:

> Open Telegram, find Amma group, search files containing xxx, download every match, continue until the end, and verify completion.

## Important

The app binary builds and its unit tests pass, but universal Android automation still requires real-device testing across the apps you want to control. Secure windows, authentication prompts, CAPTCHA, and protected system flows are intentionally not bypassed.

The current repository stores the source archive as verified base64 chunks and reconstructs it during CI. The source archive SHA-256 is stored in `SOURCE_SHA256.txt`.
