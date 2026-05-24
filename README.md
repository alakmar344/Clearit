# Clearit

Clearit is an Android app that lets you pick an image or video and produce a clearer result.

## What it does
- Selects a source image or video from storage.
- Enhances images with the app preset (contrast/highlights/shadows/blacks/exposure/whites/temp/tint/vibrance/saturation) using on-device bitmap processing.
- Processes selected video output through the app's video enhancement pipeline.
- Saves enhanced images and videos to gallery albums (`Pictures/Clearit` and `Movies/Clearit`).

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
The app now performs real image enhancement in-app before saving to gallery albums.
Video enhancement still uses the FFmpeg-style pipeline entrypoint in `VideoEnhancer`, but the local compatibility backend remains in place for environments where upstream FFmpeg Kit artifacts are unavailable.

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
