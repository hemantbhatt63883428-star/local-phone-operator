# Local Phone Operator — API edition

Android floating assistant based on the editable AgentBubble source. The app has exactly two modes:

- **Chat:** API conversation with saved history. Accessibility and device tools are never used.
- **Chat + Tasks:** each request sends a fresh image of the target app window and its accessibility UI tree to the selected API model. The model may answer, ask a question, or call one device tool. Each action is followed by a fresh observation.

Configure an HTTPS OpenAI-compatible provider with a model that supports both image input and function calling. Settings has independent **Check chat**, **Check image input**, and **Check tool calling** probes. API keys are encrypted with Android Keystore before storage.

Chat + Tasks requires Android 14+ and the Accessibility service. Secure screens may refuse capture. Password and authentication screens are blocked. Normal navigation proceeds within the requested task; coordinate taps and final send, delete, purchase, and payment controls request confirmation. Stop cancels the API request and prevents subsequent actions.

## Build

Run `gradle :app:testDebugUnitTest :app:assembleDebug` with Java 17 and Android SDK 35, or download the APK from the GitHub Actions build artifact. The Android project lives directly in this repository as editable source files.

## Installation compatibility

The application ID remains `com.hemant.localoperator` and the version code is 3. The previous debug APK from workflow run 36617352683 has signing certificate SHA-256 `e9add27b8b97479ed670a0910a75c9f139a1976f4a9ec3f3753f640bb1f84b97`. Its signing keystore is not in the repository. The new API APK built in workflow run 37210116593 has a different signer, SHA-256 `241e59da4be2676a4b74b837e829f6481aafcc959c4c1f95ef75d1ddbdf2c16e`. Android cannot install it over the old APK; uninstall the old APK before installing this one. Uninstalling removes app data. A stable release signing key is needed for future in-place updates.

## Device validation

The CI build does not prove real-device automation. On a Samsung A9 with Android 14+, verify Accessibility consent, target-window capture, image coordinate mapping, rotation, secure-window errors, Stop cancellation, and execution in the apps you intend to use.
