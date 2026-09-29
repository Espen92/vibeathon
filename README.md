# Vibeathon

A minimal native Android "Hello World" app. It runs entirely on your phone — **no hosting, no server, no backend**. GitHub Actions builds the installable debug APK for you, so you never need a computer or Android Studio.

## What the app does

- Shows **Hello World** and a welcome message.
- Has one button — every tap updates the message with the number of taps.

## Get the APK on your phone (phone-only workflow)

1. **Commit and push to `main`** (the GitHub mobile app or your phone's browser both work). Every push to `main` and every pull request starts a build automatically.
2. **Open the Actions run for that commit:** go to the repo → **Actions** tab → the **Android CI** run for your commit. Wait until it shows a green check.
3. **Download the artifact:** scroll to the bottom of the run summary to **Artifacts** and tap **`app-debug`**. It downloads as a ZIP file. (You must be signed in to GitHub to download artifacts.)
4. **Extract and install:**
   - Open the ZIP in your phone's Files app and extract `app-debug.apk`.
   - Tap the APK to install. Android will ask you to allow installs from this app/unknown sources the first time — accept.
   - Open **Vibeathon** from your home screen.

If a step feels cramped in the GitHub mobile app, use your phone's browser for the Actions pages — still no computer required.

## No hosting required

The app is a normal local Android app. It makes no network calls and requires no server, domain, or deployment. The only thing GitHub does is compile the APK.

## Project structure

```
app/
  build.gradle                      ← app module config (compileSdk 34, minSdk 21, targetSdk 34)
  src/main/
    AndroidManifest.xml             ← app metadata
    java/com/vibeathon/
      MainActivity.java             ← the single Activity
    res/
      layout/activity_main.xml      ← UI layout
      values/strings.xml            ← strings
build.gradle                        ← root build config
settings.gradle                     ← project settings
gradle/wrapper/                     ← Gradle wrapper
.github/workflows/android.yml       ← builds the APK and uploads `app-debug`
```

## Building locally (optional)

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the standard Android debug key — fine for testing and demos, not for Play Store distribution.
