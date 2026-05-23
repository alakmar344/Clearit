# Clearit

Clearit is an Android app that lets you pick a video and produce a clearer HD version.

## What it does
- Selects a source video from storage.
- Processes the selected video output.
- Saves the output video to the app's `enhanced_videos` folder.

## Build & test
```bash
./gradlew test
./gradlew assembleDebug
```

## Build APK with GitHub Actions
- Open the **Actions** tab in GitHub.
- Run the **Build Android APK** workflow (or push/open a PR).
- Download the `clearit-debug-apk` artifact to get `app-debug.apk`.

## Processing backend note
The upstream FFmpeg Kit Android artifacts used by older builds are no longer reliably resolvable from public Maven repositories. This project now includes a temporary local stub backend that copies the selected input video to the output path. This keeps CI builds stable until a full video-processing backend is reintroduced.
Video enhancement features (such as upscaling and sharpening) are currently disabled in this fallback mode.
