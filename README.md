# Vibeathon – hands-free voice mode for ChatGPT

A native Android app that recreates a "hands-free voice mode" for the **official ChatGPT app** (`com.openai.chatgpt`) using regular models, without live listening. A floating bubble stays on top of ChatGPT:

1. **Tap once** → starts recording a voice memo.
2. **Tap again** → stops recording and sends the memo to the current ChatGPT chat as a file attachment, with the prompt `respond to the prompt in the voice file`.
3. The app waits until ChatGPT has finished its reply and then taps **Read aloud** on it automatically.

Everything runs on your phone — no server or backend. GitHub Actions builds the APK.

## Install from CI (phone-only workflow)

1. Push to `main` or open a pull request. The **Android CI** workflow runs the unit tests and builds the APK (`./gradlew test assembleDebug`).
2. Open the repo → **Actions** → the **Android CI** run for your commit, and wait for the green check.
3. Under **Artifacts** tap **`app-debug`** (you must be signed in). It downloads as a ZIP.
4. Extract `app-debug.apk` in your Files app, tap it and allow installs from unknown sources when asked.
5. Open **Vibeathon**.

## One-time setup

The start screen is a checklist. Each item shows ✅/❌ live and has a button that opens the right system screen:

| Item | What to do |
| --- | --- |
| **Display over other apps** | Tap **Open** and allow Vibeathon to draw over other apps (needed for the bubble). |
| **Accessibility service enabled** | Tap **Open** → *Installed apps / Downloaded apps* → **Vibeathon** → turn it on. It is restricted to the ChatGPT app and is used to press Send and Read aloud. |
| **Microphone permission** | Tap **Grant** and allow. |
| **Notification permission** | Tap **Grant** (Android 13+). Used for the foreground-service notification with **Stop** / **Cancel** actions. |
| **ChatGPT installed** | Tap **Install** if missing (opens the Play Store). |

### Android 13+: "Restricted setting" for sideloaded apps

Android 13 and newer block accessibility services for apps installed from an APK file. If the toggle is greyed out or you see *"Restricted setting"*:

1. Try to enable the service once (so Android shows the restriction dialog).
2. Open **Settings → Apps → Vibeathon** (the **App info** button on the setup screen goes there).
3. Tap **⋮** (top-right) → **Allow restricted settings**, and confirm.
4. Go back to Accessibility and enable **Vibeathon**.

## Using it

1. Tap **Start overlay** on the setup screen. A bubble appears and a notification is shown.
2. Open ChatGPT on the chat you want to talk to.
3. **Tap the bubble** → it turns red and shows the elapsed time (**Recording**).
4. **Tap again** → **Sending** (the memo is attached and Send is pressed) → **Waiting for reply** → **Reading aloud**.
5. When the read-aloud playback ends, the bubble returns to **Idle**.

Details:

- **Drag** the bubble to move it.
- **Barge-in:** tapping while *Waiting for reply* or *Reading aloud* stops the current reply/playback and immediately starts a new recording.
- Recordings shorter than 0.5 s are discarded with a hint. Recordings stop automatically at the max length (default 5 min) and are sent.
- The notification's **Cancel** aborts the current step (back to Idle); **Stop** closes the overlay.
- If something fails, the bubble shows **Error** with a short message, and the details are in the **Debug log**. Tap the bubble to start again.

### Settings

- **Delivery strategy**
  - **Share intent (default):** sends the `.m4a` file to ChatGPT with `ACTION_SEND` (`audio/mp4`, via the app's own read-only content provider) and the prompt as text. The accessibility service then waits for the attachment to finish loading and presses **Send**. If the prompt did not arrive, it is typed into the composer.
  - **Accessibility:** brings ChatGPT to the front and uses its own attachment menu (**Attach → Files**), selects the recording in the system file picker (it is temporarily copied to `Download/Vibeathon`), types the prompt and presses **Send**. Requires Android 10+. This keeps you in the current chat, but it is the most sensitive to UI changes.
  - **Transcribe fallback:** instead of recording a file, uses Android's `SpeechRecognizer` (on-device when available) and sends the **transcript as text**. Use this if ChatGPT rejects or ignores audio attachments for your model or account.
- **Prompt** (default `respond to the prompt in the voice file`).
- **Auto "Read aloud"** on/off.
- **Reply timeout** (default 180 s).
- **Max recording length** (default 300 s).
- **Debug log:** every automation step is logged. When a step times out, the labels of the visible buttons are logged too. Use **Copy** to share them.

## Known limitations

- **ChatGPT UI changes can break the automation.** Buttons are found only by their accessibility label or text, so a renamed button, such as "Send", "Read aloud" or "Stop generating", stops the flow until `Selectors` is updated. See below.
- **Audio file support varies by model and account.** Some models may ignore the attachment or reply that they can't listen to audio. Switch to the **Transcribe fallback** in that case.
- **The share intent may open a new chat.** Depending on the ChatGPT version, sharing a file may start a new conversation instead of adding to the current one. Use the **Accessibility** strategy to stay in the current chat.
- **Reply completion is heuristic.** A reply counts as finished when the "Stop generating" control has disappeared and the newest text has not changed for about 1.5 s, or when a new "Read aloud"/feedback button appears.
- **Read-aloud end detection:** if no "Stop reading"-type control can be detected, the bubble returns to Idle about 6 s after pressing Read aloud.
- Android 13+ requires **Allow restricted settings** for sideloaded APKs (see above). Some OEMs also kill background services aggressively. Exclude Vibeathon from battery optimisation if the bubble disappears.
- The app needs `minSdk 26` (Android 8.0).

## Updating selectors

All UI selectors live in one file: [`app/src/main/java/com/vibeathon/core/Selectors.java`](app/src/main/java/com/vibeathon/core/Selectors.java).

Each selector is a list of candidate labels (`SEND`, `STOP_GENERATING`, `READ_ALOUD`, `STOP_READING`, `RESPONSE_ACTIONS`, `MORE_ACTIONS`, `ATTACH`, `ATTACH_FILE`, `SCROLL_TO_BOTTOM`). A node matches if its **content description or text** equals one of the candidates, ignoring case and extra whitespace.

To fix a broken step:

1. Reproduce the failure and open the **Debug log** on the setup screen. Look for `Timed out: …` followed by `Visible controls: "…" "…"`.
2. Find the new label of the button, such as `"Send prompt"`.
3. Add it to the matching list in `Selectors.java`. Don't use screen coordinates.
4. Commit and push. CI runs the tests and builds a new APK.

For localized ChatGPT UIs, add the translated labels to the same lists.

## Project structure

```
app/src/main/java/com/vibeathon/
  MainActivity.java                 ← setup checklist, settings, debug log
  OverlayService.java               ← floating bubble + foreground service; runs the flow
  ChatGptAccessibilityService.java  ← presses Send / Read aloud, detects reply completion
  AudioRecorder.java                ← MediaRecorder → AAC .m4a (44.1 kHz, mono, 64 kbps)
  Transcriber.java                  ← SpeechRecognizer fallback
  AudioFileProvider.java            ← minimal read-only content provider (no AndroidX)
  AppSettings.java                  ← SharedPreferences
  DebugLog.java                     ← in-app debug log
  core/                             ← plain Java, unit tested
    VoiceStateMachine.java          ← IDLE → RECORDING → SENDING → AWAITING_REPLY → READING → IDLE (+ ERROR, barge-in)
    ReplyCompletionDetector.java    ← "reply finished" logic with injectable Clock
    Selectors.java                  ← all ChatGPT UI labels
app/src/test/java/com/vibeathon/core/ ← JUnit tests
.github/workflows/android.yml       ← runs tests, builds and uploads `app-debug`
```

## Building locally (optional)

```bash
./gradlew test assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the standard Android debug key. That is fine for personal use but not for Play Store distribution.
