# Clearit

**Clearit is an on-device photo enhancer for Android.** Open a photo, tap **Auto enhance**
or fine-tune every detail by hand, compare before/after, and save a full-resolution result
(optionally upscaled 2×–4×) to your gallery. No network, no accounts — photos never leave
the phone.

Current version: **v1.new** · Android 8.0+ (API 26) · [Changelog](CHANGELOG.md)

## Features

| Area | What you get |
|---|---|
| **Auto** | One-tap enhancement driven by real image analysis: exposure, black/white points, shadows/highlights, contrast, white balance (grey-world + white-patch), vibrance, dehaze, noise reduction and sharpening. Adjustable strength (0–150 %) and scope (Light / Color / Detail). Shows the analysis (exposure, colour cast, noise, detail, clipping). |
| **Presets** | Original, Clearit, Vivid, Portrait, Landscape, Low light, Crisp, Soft matte, Warm, Cool, Black & white — all fully editable afterwards. |
| **Light** | Exposure (±3 EV, in linear light with highlight roll-off), Contrast, Highlights, Shadows, Whites, Blacks. |
| **Color** | Temperature, Tint, Vibrance (skin-tone aware), Saturation. |
| **Detail** | Clarity, Texture, Dehaze, Sharpening, Noise reduction, Color noise reduction. |
| **Effects** | Vignette (darken or lighten corners). |
| **Size & Rotate** | Rotate 90° left/right, flip horizontal/vertical, upscale 1×/2×/3×/4×. |
| **Compare** | Split view with draggable divider, or press-and-hold the photo to see the original. Pinch to zoom up to 8×, double-tap to zoom. |
| **Histogram** | Live RGB + luma histogram overlay. |
| **History** | Undo/redo (100 steps), reset single sliders (long-press their name) or everything. |
| **Export** | JPEG / PNG / WebP with quality control, full resolution, saved to `Pictures/Clearit`, camera EXIF preserved for JPEG (GPS deliberately stripped), Share directly. |
| **Integration** | “Share to Clearit” and “Open with / Edit with Clearit” from any gallery or file app. Edits survive rotation and process death. |

## Install

Download `clearit-v1.new.apk` from the
[GitHub Releases page](https://github.com/alakmar344/Clearit/releases) and open it on your
phone (allow “install unknown apps” for your browser/file manager when asked).

## Quick start

1. Tap **Open photo**.
2. **Auto** tab → **Auto enhance**. Adjust *Strength* or untick areas you don't want auto to touch.
3. Fine-tune in **Light / Color / Detail / Effects**. Long-press a slider's name to reset it.
4. Press and hold the photo to compare with the original.
5. Tap **Save** (⬇) → pick format/quality → saved to `Pictures/Clearit`.

The full guide is in [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

## Documentation

| Document | Contents |
|---|---|
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) | Every screen, slider and option explained, tips, FAQ |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Code structure, processing pipeline, algorithms, memory model |
| [docs/BUILDING.md](docs/BUILDING.md) | Building, testing, signing and publishing releases |
| [CHANGELOG.md](CHANGELOG.md) | Release history |

## Build

```bash
./gradlew testDebugUnitTest   # 47 JVM tests: engine + Robolectric end-to-end UI test
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # minified release APK (see docs/BUILDING.md for signing)
```

Requires JDK 17 and the Android SDK (platform 34). CI builds a debug APK on every push/PR.
The **Release** workflow keeps GitHub Release **v1.new** up to date with every update. Enable
it once by following [`ci/README.md`](ci/README.md).

## Project layout

```
app/src/main/java/com/clearit/
├── MainActivity.kt          UI: toolbar, tabs, sliders, dialogs, intents
├── EditorViewModel.kt       State, preview rendering, undo/redo, export
├── engine/                  Pure-Kotlin image engine (no Android dependencies)
│   ├── Adjustments.kt       Edit model + slider metadata
│   ├── ImageProcessor.kt    The full pipeline
│   ├── ToneMapping.kt       White balance, exposure, tone curve (LUTs)
│   ├── Filters.kt           Blur, noise reduction, dehaze, clarity/sharpening, vignette
│   ├── Geometry.kt          Rotate/flip + bicubic resampler (upscaling)
│   ├── ImageAnalyzer.kt     Histograms, white balance, noise & sharpness estimation
│   ├── AutoEnhancer.kt      Analysis → slider values
│   └── Presets.kt           Built-in looks
├── io/BitmapIO.kt           Decode (EXIF, HEIC, memory limits), encode, MediaStore, EXIF copy
└── ui/                      CompareImageView (split/zoom), HistogramView
```

## Honest limitations

- Enhancement uses classic, deterministic image-processing algorithms — not a neural
  network. Upscaling is high-quality bicubic + detail sharpening; it cannot invent detail
  the way AI super-resolution models can.
- Output size is capped by available memory (shown in the *Size & Rotate* tab). On a
  typical 512 MB-heap phone that is about 25 megapixels of output.
- Video enhancement was removed in v1: the previous video path only copied the file
  unchanged, so it was not a real feature.
- Previews are rendered at up to 1600 px for speed; the saved image is processed at full
  resolution with the same settings.
