# Vibeathon

A minimal Android app for the vibeathon event. Builds to a debug APK via GitHub Actions.

## Build & Install

1. **Trigger the build:** Push a commit to `main` (or open a PR). GitHub Actions runs automatically.
2. **Download the APK:** 
   - Go to the workflow run on GitHub.
   - Scroll to "Artifacts" and download `app-debug.apk`.
3. **Install on Android:**
   - Move/download the APK to your phone.
   - Open it in a file manager or download app.
   - Tap to install (you may need to allow unknown sources in settings).
   - Open "Vibeathon" from your home screen.

## No Hosting Required

The app is entirely local—it runs on your phone and doesn't connect to any server.

## Edit & Build from Your Phone

You can edit the code directly in the GitHub app, commit, and let GitHub Actions build the new APK. The workflow handles all the Android SDK setup.

## Project Structure

```
app/
  src/main/
    AndroidManifest.xml     ← app metadata
    java/com/vibeathon/
      MainActivity.kt       ← main app logic
    res/
      layout/
        activity_main.xml   ← UI layout
      values/
        strings.xml         ← app strings
        colors.xml          ← colors
build.gradle.kts            ← Gradle build config
settings.gradle.kts         ← project settings
```

---

**Built for a phone-only workflow. No IDE, no computer, no hosting required.**
