# Clearit user guide

## Contents
1. [Opening a photo](#1-opening-a-photo)
2. [The screen](#2-the-screen)
3. [Auto enhance](#3-auto-enhance)
4. [Presets](#4-presets)
5. [Manual adjustments](#5-manual-adjustments)
6. [Size & Rotate (upscaling)](#6-size--rotate)
7. [Comparing, zooming, histogram](#7-comparing-zooming-histogram)
8. [Undo, redo, reset](#8-undo-redo-reset)
9. [Saving and sharing](#9-saving-and-sharing)
10. [Tips for common problems](#10-tips-for-common-problems)
11. [FAQ](#11-faq)

---

## 1. Opening a photo

- Tap **Open photo** (centre of the empty screen, or the 🖼 icon in the toolbar). The
  Android photo picker opens — no storage permission is needed.
- Or, from any gallery / file manager: **Share → Clearit**, or **Open with / Edit with → Clearit**.

Supported: anything Android can decode — JPEG, PNG, WebP, HEIC/HEIF (Android 9+), GIF/BMP
(first frame). EXIF rotation is applied automatically. Wide-gamut (Display P3)
photos are converted to sRGB.

Opening a new photo starts a fresh edit (sliders reset, history cleared).

## 2. The screen

```
┌ Toolbar: Open · Undo · Redo · Compare · Save · ⋮ (Share, Histogram, Reset all, Help, About)
├ Preview (pinch / pan / double-tap / long-press)
├ Tabs: Auto · Presets · Light · Color · Detail · Effects · Size & Rotate
└ Panel with the controls of the selected tab
```

A thin progress bar at the top of the preview shows while the preview renders or a save
is running. While a slider is being dragged the preview renders at reduced resolution for
speed; it sharpens up to full preview quality the moment you release.

## 3. Auto enhance

**Auto** tab → **Auto enhance**.

Clearit analyses the photo and *sets the sliders* — nothing is hidden. After auto runs you
can open any other tab and see/adjust exactly what it chose.

| Option | Effect |
|---|---|
| **Strength** (0–150 %) | Scales every suggestion. 100 % = recommended; 50 % = subtle; >100 % = exaggerated. |
| **Light** | Exposure, Contrast, Highlights, Shadows, Whites, Blacks |
| **Color** | Temperature, Tint, Vibrance, Saturation (white balance correction) |
| **Detail** | Noise reduction, Color noise reduction, Sharpening, Clarity, Texture, Dehaze |

Untick a chip to keep auto away from that area (e.g. untick *Color* to keep an intentional
golden-hour warmth). Auto keeps your rotation/flip/upscale settings.

**Photo analysis** shows what Clearit measured:
- *Exposure*: underexposed / balanced / overexposed + median brightness.
- *Color*: warm, cool/blue, green or magenta cast, or neutral.
- *Noise*: low / moderate / high (measured in flat areas such as sky or walls).
- *Fine detail*: how much high-frequency detail the image has (low = soft/blurry).
- *Clipping*: percentage of pure-black and pure-white pixels.

Running auto again always starts from the analysis of the original photo, so it does not
stack on top of itself.

## 4. Presets

One tap applies a complete look. Presets replace all slider values but keep rotation,
flip and upscale.

| Preset | Look |
|---|---|
| Original | All sliders neutral |
| Clearit | The classic Clearit look: recovered highlights, lifted shadows, cooler, punchy colour |
| Vivid | More contrast and colour, crisp detail |
| Portrait | Soft skin (negative texture/clarity), warm, gentle |
| Landscape | Strong highlight recovery, shadow lift, clarity, dehaze, vibrance |
| Low light | +0.8 EV, strong shadow lift, strong noise reduction |
| Crisp | Maximum detail: clarity, texture, sharpening |
| Soft matte | Faded blacks, lower contrast, muted colour, vignette |
| Warm / Cool | White balance shift with a touch of contrast |
| Black & white | Monochrome with contrast and clarity |

## 5. Manual adjustments

All sliders are 0 at neutral. **Long-press a slider's name to reset it.** Sliders support
accessibility/keyboard control too.

### Light
| Slider | Range | What it does |
|---|---|---|
| Exposure | −3…+3 EV | Brightness in photographic stops, computed in linear light. Highlights roll off smoothly instead of clipping. |
| Contrast | −100…+100 | S-curve around mid-grey. |
| Highlights | −100…+100 | Negative recovers bright areas (skies), positive brightens them. |
| Shadows | −100…+100 | Positive opens up dark areas, negative deepens them. |
| Whites | −100…+100 | Moves the white point (the very brightest tones). |
| Blacks | −100…+100 | Moves the black point. Negative = deeper blacks, positive = faded/matte. |

### Color
| Slider | What it does |
|---|---|
| Temperature | Negative = cooler/bluer, positive = warmer/yellower. Brightness-neutral. |
| Tint | Negative = greener, positive = more magenta. |
| Vibrance | Boosts muted colours more than already-saturated ones; protects skin tones. |
| Saturation | Uniform colour intensity. −100 = black & white. |

### Detail
| Slider | What it does |
|---|---|
| Clarity | Mid-tone local contrast over a large radius — adds “punch”. Negative = soft glow. |
| Texture | Medium-scale detail (foliage, fabric, hair). Negative smooths skin. |
| Dehaze | Removes atmospheric haze/fog (dark-channel prior). Negative adds haze. |
| Sharpening | Fine-edge sharpening with a noise threshold so grain isn't amplified. |
| Noise reduction | Edge-preserving luminance smoothing (removes grain, keeps edges). |
| Color noise reduction | Removes coloured speckles/blotches while keeping brightness detail. |

### Effects
| Slider | What it does |
|---|---|
| Vignette | Negative darkens the corners (classic), positive lightens them. |

## 6. Size & Rotate

- **Rotate left / right** (90°), **Flip horizontal / vertical** — lossless.
- **Upscale 1× / 2× / 3× / 4×** — enlarges the saved image using Catmull-Rom bicubic
  interpolation followed by detail-restoring sharpening that scales with the factor.
  The preview shows the look; the size is applied when saving.
- **Output** shows the exact pixel size that will be saved. If the result would exceed what
  this phone's memory can process, it says *limited by this device's memory* and the image
  is scaled to the largest safe size.

## 7. Comparing, zooming, histogram

| Gesture / control | Action |
|---|---|
| Press and hold the photo | Show the original until you lift your finger |
| Toolbar **Compare** | Split view: left = before, right = after. Drag the divider. |
| Pinch | Zoom up to 8× |
| Drag | Pan when zoomed |
| Double-tap | Zoom to 3× at that point / back to fit |
| ⋮ → **Histogram** | RGB + brightness histogram of the edited preview (setting is remembered) |

## 8. Undo, redo, reset

- **Undo / Redo** in the toolbar — up to 100 steps. One step = one slider release, preset,
  auto run, rotation, flip or upscale change.
- **⋮ → Reset all** — all sliders and geometry back to the original (undoable).
- Long-press a slider name — reset just that slider (undoable).

## 9. Saving and sharing

**Save** (⬇) opens the export dialog:

| Format | Notes |
|---|---|
| JPEG | Best for photos. Quality 50–100 (default 95). Camera EXIF (date, camera, lens, exposure) is preserved; GPS location is removed for privacy. |
| PNG | Lossless, large files. Keeps transparency. |
| WebP | Smaller than JPEG at similar quality. Keeps transparency. |

The photo is processed at full resolution and saved to **Pictures/Clearit**. A message
confirms the final size with a **Share** shortcut. **⋮ → Share** saves and immediately
opens the share sheet. Your original file is never modified.

On Android 8–9 Clearit asks once for storage permission (required by those versions to
write to the Pictures folder).

## 10. Tips for common problems

| Problem | Try |
|---|---|
| Too dark | Auto, or Exposure +0.5…+1, Shadows +30 |
| Sky blown out | Highlights −50, Whites −20 |
| Washed-out / foggy | Dehaze +30, Blacks −20, Contrast +15 |
| Orange indoor light | Temperature −20…−40 |
| Blue/cold shade | Temperature +15…+30 |
| Grainy night photo | Noise reduction 40–60, Color noise reduction 60, Sharpening ≤ 15 |
| Slightly blurry | Sharpening 40–60, Texture +20 (keep noise reduction on if noisy) |
| Small photo, want to print | Size & Rotate → 2× or 3×, Sharpening 20–30 |
| Portrait skin too harsh | Texture −20, Clarity −10 |

## 11. FAQ

**Does Clearit upload my photos?** No. The app has no internet permission at all.

**Why does the preview look slightly different while dragging?** It renders at lower
resolution for speed; release the slider for the full-quality preview.

**Is the upscaler AI?** No — it is a high-quality classical resampler plus sharpening. It
produces clean, larger images but cannot invent detail that wasn't captured.

**Why was my output smaller than expected?** The requested size exceeded this phone's
safe memory limit; the *Size & Rotate* tab shows the exact size before saving.

**Can I enhance videos?** Not in v1 — Clearit is an image enhancer.

**Where are my saved photos?** `Pictures/Clearit` — visible in any gallery app under the
“Clearit” album.
