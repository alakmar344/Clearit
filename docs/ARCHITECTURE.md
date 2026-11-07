# Clearit architecture

## Layers

```
MainActivity  ──observes──▶  EditorViewModel  ──calls──▶  engine (pure Kotlin)
   │  (views, tabs,               │  (state, history,             ImageProcessor, AutoEnhancer,
   │   dialogs, intents)          │   preview/export jobs)        ImageAnalyzer, Filters, ...
   ▼                              ▼
ui/CompareImageView         io/BitmapIO  (decode / encode / MediaStore / EXIF)
ui/HistogramView
```

- **`engine/`** has *no Android imports*. It operates on `PixelImage` (width, height and an
  `IntArray` of packed `0xAARRGGBB` pixels). This makes every algorithm unit-testable on the
  JVM (`app/src/test/java/com/clearit/engine`, 44 tests) and keeps preview and export
  bit-identical in behaviour.
- **`io/BitmapIO`** is the only place that touches `Bitmap`, `ImageDecoder`, `MediaStore` and
  `ExifInterface`.
- **`EditorViewModel`** owns all state and coroutines; the activity only renders state and
  forwards user intent.

## The edit model

`Adjustments` is an immutable data class: 17 slider values + rotation, flips and upscale
factor. The `Adjustment` enum is the slider table (key, group, min, max, step, accessors) —
the UI generates its slider tabs from it, `clamp()` snaps values onto the slider step grid,
and `encode()/decode()` provide a compact `key=value;` string used for saved state.

## Processing pipeline (`ImageProcessor.process`)

| # | Stage | Algorithm |
|---|---|---|
| 1 | Geometry | Exact index remapping for 90/180/270° rotation and flips. |
| 2 | Noise reduction | **Luminance:** blend each pixel towards a small box-blur (3 passes ≈ Gaussian) with weight `amount · exp(−Δluma² / 2σ²)` — a range kernel like a bilateral filter, so edges (large Δ) are preserved. **Colour:** replace chroma with that of a wider blur while keeping the pixel's own luma. |
| 3 | Dehaze | Dark-channel prior (He et al. 2009), simplified: dark channel = per-pixel min(R,G,B) smoothed by a wide box blur; airlight A = top 0.1 % luma; `J = (I − A) / max(t, 0.3) + A`, `t = 1 − 0.95·amount·dark/A`. Negative amounts blend towards A. |
| 4 | Tone & colour | Three 256-entry per-channel LUTs (see below) + per-pixel vibrance/saturation around Rec.709 luma. Vibrance weight `(1 − sat)`, halved for skin-like hues (R > G > B). |
| 5 | Clarity / Texture | Luma-only unsharp masks. Clarity: radius 1.2 % of the long edge, midtone mask `1 − (2y−1)²`. Texture: radius 0.35 %. Differences are soft-clipped (`d / (1 + |d|/k)`) to suppress halos. |
| 6 | Upscale (export only) | Separable Catmull-Rom bicubic (Keys, a = −0.5). Downscaling widens the kernel support by 1/scale for area-correct, alias-free results. |
| 7 | Sharpening | Luma unsharp mask, radius ≈ long-edge/3000 × upscale, with a noise threshold and soft-clip. Adds `0.12 × (factor − 1)` automatic sharpening after upscaling. |
| 8 | Vignette | Radial smoothstep falloff from 30 % of the half-diagonal; darken multiplies, lighten blends to white. |

**Resolution independence.** Every radius is a fraction of the image's long edge, so the
1600 px preview predicts the full-size export.

**Cancellation/progress.** `process` reports progress through `ProgressCallback`; the preview
renderer's callback calls `ensureActive()`, so a newer slider value cancels a stale render.

### Tone LUTs (`ToneMapping`)

For each 8-bit input code `v` and channel `c`:

1. `lin = sRGB⁻¹(v)` (IEC 61966-2-1).
2. `lin *= gain_c` where gains combine **white balance** (temperature: R × e^{0.3t}, B × e^{−0.3t};
   tint: G × e^{−0.25m}, R,B × e^{0.125m}), normalised so Rec.709 luminance is unchanged, and
   **exposure** `2^EV`.
3. **Highlight shoulder**: identity below 0.7, then `[0.7, maxGain]` is mapped into `[0.7, 1]`
   by `1 − (1 − t)^p` with `p` chosen for C¹ continuity — values never hard-clip. When no gain
   exceeds 1 this is exactly the identity.
4. `sRGB(lin)` then the **parametric tone curve**: a monotone cubic (Fritsch–Carlson) spline
   through 5 control points at x = 0, ¼, ½, ¾, 1 whose y-values are moved by Blacks, Shadows,
   Contrast, Highlights and Whites. Monotone interpolation guarantees no tonal inversions.

A neutral edit produces identity LUTs (tested bit-exact).

## Analysis & auto (`ImageAnalyzer`, `AutoEnhancer`)

`ImageAnalyzer.analyze` (run once on the preview image when a photo opens) measures:

- RGB + luma histograms; luma percentiles p1/p5/p50/p95/p99; clipped fractions.
- **White balance:** low-chroma, unclipped mid/bright pixels are candidates. The neutral
  estimate is the geometric blend of **grey-world** (mean of all candidates) and
  **white-patch** (mean of the brightest 10 % of candidates), in linear light. Using both is
  much more robust than grey-world alone, which blue skies and foliage fool.
- **Noise σ:** Immerkær's (1996) operator `[1 −2 1; −2 4 −2; 1 −2 1]` evaluated per block;
  the 20th-percentile block is used, so flat regions define the noise and texture is ignored.
- **Sharpness:** mean absolute Laplacian.

`AutoEnhancer.suggest` turns this into slider values (each step deliberately partial):

| Step | Rule |
|---|---|
| Exposure | 75 % of `log2(lin(0.46) / lin(median))`, reduced when many highlights exist, clamped ±1.5 EV |
| Blacks / Whites | stretch predicted p1 → 0.02 and p99 → 0.96; back off when clipping |
| Shadows / Highlights | lift if > 10 % of pixels are deep shadow; recover if > 8 % are bright |
| Contrast | add when the p5–p95 range is narrow |
| Temperature / Tint | 70 % / 60 % of the correction that neutralises the estimate, scaled by the share of neutral pixels found (confidence) |
| Vibrance | more for low mean saturation |
| Noise reduction | from σ; colour NR follows |
| Sharpening | 40 if soft else 25, reduced when NR is high |
| Dehaze | when blacks are lifted (p1 > 0.12) and colours are washed out |

Result = `NEUTRAL.blend(suggestion, strength)`; disabled groups stay neutral.

## Android layer

### Previews
`EditorViewModel` decodes the photo once at ≤1600 px (`PREVIEW_LONG_EDGE`) and derives an
≤800 px draft copy. While a slider is dragged the draft is rendered; on release the full
preview is rendered and the histogram updated. The "before" bitmap is cached per geometry.

### History & state
Each committed change is pushed to a 100-entry history list (undo/redo). The source URI and
encoded adjustments live in `SavedStateHandle`, so edits survive process death (the picker
URI permission is persisted where the provider allows it).

### Export & memory model
`BitmapIO.maxProcessingPixels = heap / 20` (halved on low-RAM devices, clamped 4–64 MP): the
pipeline needs roughly five ARGB buffers of the output size. The source is decoded at most at
`budget / upscale²` pixels, processed with `includeUpscale = true`, encoded and inserted into
MediaStore (`Pictures/Clearit`, `IS_PENDING` on Android 10+, explicit path on 8–9). For JPEG,
selected EXIF tags (date, camera, lens, exposure — not GPS) are copied and orientation is set
to normal. `android:largeHeap="true"` raises the budget on most devices.

### Decoding
Android 9+: `ImageDecoder` with software allocation (hardware bitmaps can't be read), sRGB
target colour space, EXIF orientation, partial-image tolerance and `setTargetSize`.
Android 8: `BitmapFactory` with power-of-two subsampling + exact scaling and manual EXIF
rotation/flip via `ExifInterface`.

## Testing

```bash
./gradlew testDebugUnitTest
```

Covers: adjustment clamping/encoding/presets; LUT identity, monotonicity and direction of
every tone control; blur, noise reduction (variance drop, edge & luma preservation), colour
NR, sharpening, dehaze, vignette; rotations/flips; resampling (flat-colour exactness,
anti-aliasing, smoothness); pipeline identity, immutability, output size, progress and
cancellation; auto exposure (dark & bright), auto white balance (blue & warm casts), noise
detection, option groups/strength, and validity of suggestions on extreme images.
