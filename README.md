# Clearit

Clearit is an Android app that lets you pick a video and produce a clearer HD version.

## What it does
- Selects a source video from storage.
- Upscales the video to at least 1080p/1920 width (based on orientation).
- Applies an FFmpeg sharpening filter to improve clarity.
- Saves the enhanced output to the app's `enhanced_videos` folder.

## Build & test
```bash
./gradlew test
./gradlew assembleDebug
```

## Build APK with GitHub Actions
- Open the **Actions** tab in GitHub.
- Run the **Build Android APK** workflow (or push/open a PR).
- Download the `clearit-debug-apk` artifact to get `app-debug.apk`.
