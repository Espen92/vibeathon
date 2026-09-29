# Vibeathon

A minimal native Android app (one screen, one button). GitHub Actions builds the
installable debug APK — no hosting, no backend, no computer needed.

## Phone-only workflow

1. **Trigger a build:** Commit and push a change (or open a pull request) from the
   GitHub mobile app. The `Android CI` workflow starts automatically.
2. **Open the workflow run:** Go to the repo → **Actions** → the newest
   **Android CI** run. (If the mobile app is awkward here, open the same page in
   your phone's browser.)
3. **Download the APK:** Scroll to **Artifacts** and tap **app-debug-apk**.
   You get a ZIP; extract `app-debug.apk` with any file manager.
4. **Install it:** Tap the APK, allow "install unknown apps" for your browser or
   file manager if prompted, and install. Then open **Vibeathon** from your app
   drawer.

Tap the button on the screen and the welcome message changes — that confirms the
whole edit → build → download → install loop works.

> Artifact downloads require being signed in to GitHub with read access to this
> repo.

## No hosting required

The app is entirely local. It has no network permission, no server, no
analytics, and no sign-in. Everything runs on the phone.

## Project structure

```
app/
  build.gradle.kts                    ← app module build config
  src/main/
    AndroidManifest.xml               ← app metadata
    java/com/vibeathon/
      MainActivity.java               ← the single Activity
    res/
      layout/activity_main.xml        ← the one screen
      values/strings.xml              ← app name and messages
build.gradle.kts                      ← root build config
settings.gradle.kts                   ← project settings
gradle/wrapper/                       ← Gradle wrapper
.github/workflows/android.yml         ← builds and uploads the debug APK
```

No AndroidX or third-party libraries — just the Android framework.

## Notes

- The APK is a **debug** build. It is signed with the standard debug key, which
  is fine for testing and demos but is not a Play Store release.
- To build locally instead: `./gradlew assembleDebug`, then find the APK at
  `app/build/outputs/apk/debug/app-debug.apk`.
