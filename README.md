# Composite Camera

A modern Android camera app with two photography pipelines that, as far as we can tell, no other
mobile app combines in one place:

1. **Pro Composite** — exposure-bracket × focus-bracket burst capture using Camera2 manual
   controls, fused on-device into a single all-in-focus HDR image. Designed for real-estate /
   interior photography (windows + room interiors + foreground objects all sharp + well-exposed
   in one shot).
2. **Motion Reveal** — live delayed-inverse overlay of the camera feed. Static scene cancels to
   mid-grey, moving things light up. Great for motion detection, scientific imaging, or just for
   the visual effect.

Both modes are fully on-device, no cloud, no telemetry.

## Build

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17, Android SDK with API 34, NDK is NOT required.

## Install on Redmi Note 14 5G

```bash
./install_with_miui_dialog.sh                   # auto-handles MIUI ADB-install confirmation
./install_with_miui_dialog.sh --build           # build + install in one shot
```

After the first install with "Remember my choice" ticked, MIUI lets future installs go silent.

## Source layout

```
camera_system/
├── app/src/main/java/com/fivelidz/compositecamera/
│   ├── MainActivity.kt              — entry + permissions + home screen + mode router
│   ├── common/
│   │   └── Gallery.kt               — saveBitmapToGallery / saveVideoFile via MediaStore
│   ├── pro/
│   │   ├── CameraCaps.kt            — probe Camera2 capabilities (manual_sensor etc)
│   │   ├── BracketPlanner.kt        — build (exposure × focus) capture step list
│   │   ├── BracketCapture.kt        — Camera2 burst capture engine
│   │   ├── Compositor.kt            — Mertens exposure fusion + Laplacian focus stack
│   │   └── ProScreen.kt             — Pro mode UI
│   └── motion/
│       ├── FrameRingBuffer.kt       — N-second bitmap ring buffer
│       ├── BitmapVideoEncoder.kt    — MediaCodec H.264 MP4 muxer fed from Bitmaps
│       └── MotionScreen.kt          — CameraX preview + blend modes + REC
└── install_with_miui_dialog.sh      — MIUI-aware install script
```

## Algorithm notes

### Pro Composite

The pipeline is `(M exposures × N focuses) -> M-fused-per-focus -> 1 all-in-focus`:

1. Open camera in Camera2, configure JPEG ImageReader at sensor's largest size.
2. Run a brief auto-mode capture to get the **metered exposure time** and **ISO**.
3. Build the bracket plan: ±EV stops around metered exposure (holding ISO fixed for cleanest
   alignment), and linearly-spaced focus diopters from near to infinity.
4. Burst-capture every (exposure, focus) pair. Between focus buckets the lens motor is given
   180 ms to settle.
5. Decode all JPEGs at a working resolution capped at 3000 px on the long edge (to keep memory
   under ~300 MB even with 9 frames).
6. **Per focus bucket**, run Mertens exposure fusion:
   - Per-frame weight = contrast (|Laplacian(luma)|) × saturation × well-exposedness
     (Gaussian peak at 0.5 per channel).
   - Normalise weights per pixel across frames.
   - Box-blur the weight maps to avoid blocky transitions.
   - Blend.
7. **Across focus buckets**, run focus stacking:
   - Per-pixel sharpness = (Laplacian of luma)², box-blurred.
   - Normalise across the focus stack, weighted blend.
8. Save final composite as JPEG q=95 via MediaStore.

### Motion Reveal

1. CameraX `ImageAnalysis` at 720×1280, `STRATEGY_KEEP_ONLY_LATEST`, on a single-thread executor.
2. Per frame: convert YUV_420_888 → ARGB (BT.601 integer math), rotate to upright.
3. Push to `FrameRingBuffer` keyed by timestamp.
4. Look up the frame from `now - delay`. If present, blend:
   - Inverse Overlay: `out = clamp(128 + sensitivity * (current - delayed))` per channel.
   - Difference: `out = clamp(sensitivity * |current - delayed|)` per channel.
   - Persistence Trail: dim current + amplified difference.
5. If REC pressed, feed each composited bitmap into `BitmapVideoEncoder` (MediaCodec H.264 →
   MediaMuxer MP4).

## Limitations + roadmap

See `~/projects/phone_projects/built_apps_testing/per_app/composite_camera.md` "Known limitations".

Tl;dr:
- Pro mode: would benefit from multi-band Laplacian-pyramid fusion (currently single-scale) + ECC
  alignment + RAW DNG side-output.
- Motion mode: would benefit from a GLSL blend (currently ~5 fps in pure Kotlin, would be 30+ fps
  on GPU).
