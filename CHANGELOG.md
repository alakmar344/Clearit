# Changelog

## v1.new — first full release

Clearit is now a complete, real on-device image enhancer.

### Added
- **New enhancement engine** (pure Kotlin, unit-tested): linear-light exposure with highlight
  roll-off, white balance, monotone tone curve (contrast/highlights/shadows/whites/blacks),
  vibrance & saturation, edge-preserving luminance and colour noise reduction, dark-channel
  dehaze, clarity, texture, thresholded sharpening, vignette, rotation/flip and 2×–4×
  Catmull-Rom upscaling.
- **Auto enhance** based on real analysis (histogram, grey-world + white-patch white balance,
  Immerkær noise estimation, sharpness) with strength (0–150 %) and Light/Color/Detail scope.
- **Manual mode**: 17 sliders in Light, Color, Detail and Effects tabs; long-press to reset.
- **11 presets**: Original, Clearit, Vivid, Portrait, Landscape, Low light, Crisp, Soft matte,
  Warm, Cool, Black & white.
- **Live preview** with fast draft rendering while dragging.
- **Compare**: split view with draggable divider and press-and-hold original; pinch zoom (8×),
  pan and double-tap zoom.
- **Histogram** overlay, **undo/redo** (100 steps), **reset all**.
- **Export** to JPEG, PNG or WebP with quality setting at full resolution into
  `Pictures/Clearit`; EXIF (without GPS) preserved for JPEG; memory-aware output limit;
  **Share** after saving.
- Open photos via the system photo picker, **Share to Clearit** and **Open/Edit with Clearit**.
- Edits survive screen rotation and process death.
- Adaptive app icon, dark theme, landscape layout.
- Documentation: user guide, architecture, building & releasing.
- Release workflow publishing signed APKs to GitHub Releases; committed Gradle wrapper.

### Removed
- Video enhancement. The previous "video pipeline" used a stub FFmpeg replacement that only
  copied the input file, so it never enhanced anything.
