# QuickScan — design for a minimal Android QR scanner

Personal-use, dark-mode, lightweight QR scanner for an arm64-v8a Android phone.
Goal: point, get the result instantly, open or copy it, done.

## Stack decision

**Kotlin + CameraX + Google ML Kit Barcode Scanning (bundled model), classic Views, no Compose.**

Why this stack:

- **ML Kit (bundled, `com.google.mlkit:barcode-scanning`)** is Google's production decoder — it decodes QRs in 1–3 frames, handles small/distant codes far better than ZXing, and the bundled model runs **fully offline**. QR-only mode narrows the search space for extra speed.
- **CameraX** gives the preview, continuous autofocus, torch, and zoom control with very little code.
- **No Compose, no Material Components library** — one XML layout with plain views styled dark. This keeps the APK small and cold start near-instant. Compose/Material would add megabytes for zero benefit on a single screen.
- `abiFilters += "arm64-v8a"` — one native library instead of four.

Deliberately excluded: internet permission, analytics, libraries of any other kind, app settings screens.

## App structure (4 source files)

```
QuickScan/
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
├─ gradle wrapper
└─ app/
   ├─ build.gradle.kts                 (compileSdk 36, minSdk 26, arm64-v8a, R8)
   └─ src/main/
      ├─ AndroidManifest.xml           (CAMERA permission only)
      ├─ java/com/kayde/quickscan/
      │  ├─ MainActivity.kt            permission, preview, torch, pinch, result card
      │  └─ BarcodeAnalyzer.kt         ML Kit glue + zoom-suggestion + debounce
      └─ res/
         ├─ layout/activity_main.xml   (fullscreen preview + overlay card)
         ├─ values/…                   (dark-only theme, strings, colors)
         └─ drawable/…                 (vector icon, scan-frame, card bg)
```

## Feature specs

### 1. Instant detection
- No shutter button: `ImageAnalysis` runs continuously with `STRATEGY_KEEP_ONLY_LATEST` (always decodes the freshest frame, never queues).
- Detector configured for `FORMAT_QR_CODE` only.
- Analysis resolution 1920×1080 — best speed/precision tradeoff.
- Camera + permission flow launches straight into the live preview; no splash screen.
- On decode: immediate haptic tick (`performHapticFeedback` — no VIBRATE permission needed).
- Debounce: identical payload ignored for 2 s; decoding pauses while a result card is up.

### 2. Distance scanning
- **ML Kit Zoom Suggestions**: detector created with `ZoomSuggestionOptions` (max ratio from the camera's `CameraControl`); ML Kit calls back with a suggested zoom when a QR is too small to decode reliably → app applies `cameraControl.setZoomRatio(...)` smoothly.
- Fallback/manual: pinch-to-zoom on the preview; pinching switches that session to manual zoom.
- Analysis at 1080p (not 720p) so distant codes have enough pixels; full-screen preview so nothing is cropped away.
- Continuous autofocus on by default (CameraX default behavior).

### 3. Open URLs
- Result classified as URL if `Uri.parse` yields `http`/`https`; bare `www.…` gets `https://` prepended.
- URL results show an **Open** button → plain `ACTION_VIEW` intent to the default browser (zero extra dependencies; Custom Tabs skipped for size).
- Non-URL text shows only the content + Copy.

### 4. Copy
- **Copy** button always present on the result card → `ClipboardManager`, haptic tick + toast "Copied".
- Content text on the card is also selectable, for partial copies.

### Result flow
Decode → vibrate → card slides up from bottom:

```
┌─────────────────────────────┐
│  (live camera)          [⚡]│  torch, top-right
│     ┌ ─ ─ scan ─ ┐          │  thin rounded reticle
│ ┌─────────────────────────┐ │
│ │ https://example.com/a…  │ │  payload, selectable
│ │ [ Open ] [ Copy ] [ ✕ ] │ │  ✕ = scan again (reset zoom)
│ └─────────────────────────┘ │
└─────────────────────────────┘
```

"Scan again" (or back) hides the card, resumes decoding, resets zoom. Screen stays on while scanning (`FLAG_KEEP_SCREEN_ON`). Portrait-locked.

## Dark mode & look
- Dark-only theme (pure black `#000000` surfaces — AMOLED-friendly, saves battery; white text; single green accent for buttons).
- No `values-night` needed: the app is dark in every setting.
- One full-screen activity; nothing else to theme.

## Size & performance budget
- APK target **≤ 6 MB**: ML Kit native lib (~3 MB arm64-only) + CameraX + app code, R8 minify + resource shrinking.
- No INTERNET permission → provably offline, nothing can phone home.
- Cold start target < 1 s (no Compose, no splash, one activity).

## Build & install (machine is ready)
- JDK 21 (JAVA_HOME) + Android SDK at `C:\Users\kayde\AppData\Local\Android\Sdk` (platform 36, build-tools 36) + adb all present.
- `gradlew assembleRelease` → minified APK; sign with a locally generated keystore (or debug keystore for first test: `gradlew assembleDebug`).
- Install: `adb install app-release.apk` when the phone is plugged in, or send the APK to the phone and sideload.

## Later (out of scope for v1)
- Auto-copy toggle, scan history, 1D barcode formats, GMS "code-scanner" variant (~200 KB APK) if Play Services is guaranteed on the phone.
