# Local Phone Operator

Android 11+ accessibility-based phone operator with a native chat UI, floating AI bubble, local LiteRT-LM planner, and optional OpenAI-compatible planner/vision endpoint. **Prototype for supervised personal use**, not an unattended or certified universal automation system.

## Install

Download the **LocalPhoneOperator-debug-apk** artifact from the latest successful [Build Local Phone Operator APK](https://github.com/hemantbhatt63883428-star/local-phone-operator/actions/workflows/build-apk.yml) run for this branch. Extract the ZIP and install `app-debug.apk` on an Android 11+ device (allow installation from your browser/files app if prompted). The debug APK is signed by Android's debug key; it is not a Play Store release. If upgrading a copy signed with a different key, uninstall the old app first (this erases its data).

Alternatively, open `LocalPhoneOperator/` in Android Studio (JDK 17, SDK 35) and build the debug variant. Source is checked in directly; the `source_parts/` archive is a legacy snapshot and is **not** used for builds.

## Set up

1. Open the app and go to **Controls** → **Open Accessibility Settings**; enable Local Phone Operator.
2. In **Models**, import a tool-calling planner `.litertlm` model, or select **OpenAI-compatible API**, fill in its base URL, model ID and API key, then save and test it. An imported model is not included in the APK.
3. Optionally import a vision `.litertlm` model or configure a vision API. Vision is used only when requested by the planner.
4. Enter a task in **Chat** and tap **Run**, or use the floating **AI** bubble. Chat history and progress are shared; only one task can run at a time. Expand the bubble to stop a task after it collapses for device automation.

## Capabilities and boundaries

- Inspect accessibility tree; open app; click text/node; enter text; scroll; tap/swipe; Back/Home; wait; optionally inspect screenshot; finish.
- Password/PIN fields are blocked. Sensitive controls (such as Send, Delete and Pay) are gated by a user setting under Controls. Keep it off unless actively supervising.
- No bypass of secure windows, CAPTCHA, authentication or protected flows. Automation quality depends on the target app's accessibility data and the selected model. On-device inference needs a compatible tool-calling LiteRT-LM model and sufficient RAM; cloud API use sends task/screen context to that provider.
- `QUERY_ALL_PACKAGES` is used for sideloaded general app launching; Play Store publication requires policy review.

See `LocalPhoneOperator/README.md` for architecture and model notes.
