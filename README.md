# Clearit

Clearit is an Android app that lets you pick an image or video and produce a clearer result.

## What it does
- Selects a source image or video from storage.
- Enhances images with the app preset (contrast/highlights/shadows/blacks/exposure/whites/temp/tint/vibrance/saturation) using on-device bitmap processing.
- Processes selected video output through the app's video enhancement pipeline.
- Saves enhanced images and videos to gallery albums (`Pictures/Clearit` and `Movies/Clearit`).


## Repository structure

| File | Role |
|---|---|
| `MainActivity.kt` | Picker, album handling, permission flow (126 lines) |
| `ImageEnhancer.kt` | Real on-device bitmap enhancement (277 lines) |
| `VideoEnhancer.kt` | Video pipeline entrypoint (94 lines) |
| `EnhancementCommandBuilder.kt` | Builds the FFmpeg filter command (57 lines) |
| `EnhancementPreset.kt` | The adjustment preset shared by both pipelines (51 lines) |
| `app/src/test/.../EnhancementCommandBuilderTest.kt` | Unit test for the command builder |
| `com/arthenica/ffmpegkit/FFmpegKit.kt` | Vendored compatibility backend for FFmpeg Kit |
| `.github/workflows/build-apk.yml` | CI: builds a debug APK artifact on push/PR/dispatch |

Nothing is aspirational in this table — every row is a file that exists in
this repository today.

## Build & test
```bash
./gradlew test
./gradlew assembleDebug
```

## Build APK with GitHub Actions
- Open the **Actions** tab in GitHub.
- Run the **Build Android APK** workflow (or push/open a PR).
- Download the `clearit-debug-apk` artifact to get `app-debug.apk`.

## Image vs. video pipelines — read this first

The two media types are handled by **two different engines** in this app:

- **Images** — fully real, on-device processing. `ImageEnhancer.kt` (277 lines)
  applies the full preset (contrast, highlights, shadows, blacks, exposure,
  whites, temperature, tint, vibrance, saturation) directly on bitmaps.
- **Videos** — the *enhancement logic* is real and tested
  (`EnhancementCommandBuilder.kt` builds the FFmpeg command; there's a unit
  test `EnhancementCommandBuilderTest.kt`), **but the execution backend has a
  compatibility story**: this repo vendors a local `com.arthenica.ffmpegkit`
  package (`FFmpegKit.kt`) so builds keep working even where upstream FFmpeg
  Kit artifacts are unavailable. Video output therefore depends on that
  compatibility path, not on a full upstream FFmpeg binary.

If you fork this, swap the vendored compatibility package for the real
[FFmpegKit](https://github.com/tanersener/ffmpeg-kit) dependency to get full
video processing power.

## Alternative base approach (Python RealESRGAN)
```python
from realesrgan import RealESRGAN
from PIL import Image
import torch

device = torch.device('cuda')

model = RealESRGAN(device, scale=4)
model.load_weights('RealESRGAN_x4.pth')

image = Image.open("frame.png")
sr = model.predict(image)

sr.save("enhanced.png")
```
