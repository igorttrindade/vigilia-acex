# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Vigília** is an Android fatigue-monitoring app for drivers. It uses on-device face landmark detection (MediaPipe Face Landmarker) to estimate eye closure (PERCLOS), blink rate, yawns, and head orientation, and produces a fatigue score 0–100 with a hysteresis state machine (NORMAL → WARNING → FATIGUED). Sessions are persisted locally (CSV + JSON) and optionally synced to Supabase (Auth + Postgrest) via WorkManager. Alerts are played through the Ringtone API when the driver appears fatigued.

**compileSdk**: 37 | **targetSdk**: 34 | **minSdk**: 26 | **Kotlin**: 2.3.21 | **AGP**: 9.2.1 | **Compose BOM**: 2026.05.00 | **Java**: 17

## Build Commands

```bash
# Debug build
./gradlew :app:assembleDebug

# Release build (R8 minification + resource shrinking enabled; debug-signed for side-load distribution)
./gradlew :app:assembleRelease

# Install debug APK
./gradlew installDebug

# Full unit test suite (JVM)
./gradlew :app:testDebugUnitTest --rerun-tasks

# Single test class
./gradlew :app:testDebugUnitTest --tests com.vigilia.app.domain.scoring.FatigueScorerTest

# Instrumented tests (needs device/emulator)
./gradlew connectedAndroidTest

# Lint
./gradlew :app:lint
```

**JDK note (Windows)**: gradlew is Windows-friendly but needs JAVA_HOME pointing at a JDK 17+ (the JBR bundled with Android Studio at `C:\Program Files\Android\Android Studio\jbr` works).

## Architecture Overview

Domain-Driven Design with strict layer separation:

```
UI (Jetpack Compose, Navigation)
├── AuthScreen / ForgotPassword / ResetPassword → AuthViewModel → AuthRepository
├── SetupScreen → SetupViewModel → ServiceController
├── MonitoringScreen → MonitoringViewModel → MonitoringService (bound)
└── HistoryScreen → HistoryViewModel → SessionRepository

Service Layer
├── MonitoringService (Foreground Service, camera|location)
│   ├── CameraManager + CameraX (Camera2Interop tuning per lighting mode)
│   ├── FaceAnalyzer (MediaPipe Face Landmarker + OpenCV CLAHE)
│   ├── FatigueScorer
│   ├── LightingMonitor (Y-luminance + optional TYPE_LIGHT lux → mode FSM)
│   ├── TelemetryWriter
│   ├── FusedLocationProviderClient (GPS)
│   └── SensorManager (accel + gyro + light)
├── ServiceController (start/stop + calibration & low-light-adaptation preferences)
└── SyncWorker (CoroutineWorker via WorkManager)

Domain
├── FatigueScorer (PERCLOS + blinks + yawns + hysteresis FSM + calibration + dark-mode grace)
└── FatigueModels (FatigueMetrics, FatigueAssessment, SessionSummary, TelemetryRecord, FatigueState)

Lighting (Fase 1)
└── LightingMonitor + LightingMode enum (NORMAL / LOW_LIGHT / DARK)

Data
├── TelemetryWriter (local CSV/JSON, writeMutex)
├── SessionRepository (list past sessions)
├── AuthRepository (Supabase Auth)
├── SyncRepository (upload sessions/telemetry to Supabase)
└── remote/dto/ (ProfileDto, SessionSummaryDto, TelemetryRecordDto)

Remote
└── SupabaseClient (Auth + Postgrest, deep link vigilia://reset-password)
```

## Real-time monitoring pipeline

1. **Camera capture**: CameraX (front) → ImageAnalysis 640×480 (via `ResolutionSelector` with `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`) → single-threaded `analysisExecutor` with `STRATEGY_KEEP_ONLY_LATEST` backpressure. EV compensation, target FPS range, and `CONTROL_SCENE_MODE_NIGHT` are hot-applied per `LightingMode` via `Camera2CameraControl.setCaptureRequestOptions` — see "Lighting adaptation".
2. **Lighting classification**: for each frame, `FaceAnalyzer.computeYPlaneMean` samples an 8×8 grid over the Y plane (~0.3 ms) → `LightingMonitor.update(lux, frameLuminance, tsMs)` runs the hysteretic FSM and emits the current mode. Modes drive both preprocessing (CLAHE + gamma) and scorer behavior (wider `NO_FACE_GRACE_MS_DARK` in DARK).
3. **Face detection**: `FaceAnalyzer` runs MediaPipe FaceLandmarker (asset `face_landmarker.task`) with `setOutputFaceBlendshapes(true)` and `setOutputFacialTransformationMatrixes(true)`. In LOW_LIGHT/DARK, the frame is preprocessed with OpenCV CLAHE + gamma LUT (Y channel) before landmarking. Model init is scheduled on the Main Looper (MediaPipe requires it) — early frames return NO_FACE until the model is ready.
4. **Per-frame extraction**:
   - `eyeBlinkLeft` / `eyeBlinkRight` blendshapes (0=open, 1=closed) → inverted to give openness probability.
   - `jawOpen` blendshape → mouth-open probability (not "smiling proxy" — real mouth openness).
   - EAR (Eye Aspect Ratio) computed geometrically from landmarks 33/133/159/145 (left) and 263/362/386/374 (right) — invariant to lens reflections from glasses; `min(blendshape, EAR)` is used so reflections cannot hide a blink.
   - Head yaw and pitch decomposed from the 4×4 facial transformation matrix (column-major, Y-X-Z Euler: `pitch=asin(-m[9])`, `yaw=atan2(m[8], m[10])`).
5. **`FatigueMetrics`** is emitted for every frame (leftEyeOpen, rightEyeOpen, mouthOpen, isFaceDetected, timestamp, headYawDegrees, headPitchDegrees, frameLuminance).
6. **Scoring** (`FatigueScorer.processFrame`) — see next section. The scorer reads the current `LightingMode` via a provider lambda and echoes `ambientLightLux` + `lightingMode` into `FatigueAssessment` for telemetry.
7. **Alerts** (Ringtone, USAGE_ALARM, 3s clip, 8s cooldown for sustained FATIGUED).
8. **Telemetry** — every 2 s writes a 25-column record to `session.csv`; on Stop, `session_summary.json` is generated.

## FatigueScorer — algorithm details

Constants live in the `companion object` of `FatigueScorer`. **These are the values in the code, which are the authoritative source; they were tuned iteratively and diverge intentionally from earlier drafts of this file.**

### Signal windows
| Signal | Window | Purpose |
|---|---|---|
| PERCLOS | **30 000 ms** | % of eye-closed frames — primary fatigue signal |
| Blink   | **60 000 ms** | Rolling blink count for rate deviation |
| Yawn    | ≥ 1 500 ms sustained open + 4 000 ms cooldown | Secondary fatigue signal |

### Score weights (sum = 90, clamped to 0–100)
- `SCORE_WEIGHT_PERCLOS = 65f`
- `SCORE_WEIGHT_BLINK = 10f`
- `SCORE_WEIGHT_YAWN = 15f`

**Design decision**: total weight is intentionally under 100 to preserve the invariant that FATIGUED (>70) requires a *combination* of signals, not any single one. PERCLOS alone maxes at 65 (below 70). Blink weight was lowered from 20 → 15 → 10 across successive tunings — focus-blink at the front camera runs 25-30/min naturally, keeping deviation near 1.0, so any higher weight pushes the baseline near WARNING for wide-awake users. Yawn weight was lowered from 25 → 15 because a single yawn (tier-2 signal in the automotive literature) was tipping stationary users into FATIGUED when combined with mild PERCLOS.

Exponential smoothing: `smoothedScore = 0.2 * rawScore + 0.8 * prev` (`SMOOTHING_ALPHA = 0.2`). Convergence takes ~10-15 frames (~400-500 ms) after any raw drop — gentle enough that the score doesn't visibly "collapse" when the yawn contribution releases, while still following genuine multi-second trends.

### State machine — hysteresis transitions
| From | To | Score threshold | Sustained duration |
|---|---|---|---|
| NORMAL   | WARNING  | > 50 | 3 000 ms |
| WARNING  | FATIGUED | > 70 | 4 000 ms |
| WARNING  | NORMAL   | < 30 | 5 000 ms |
| FATIGUED | WARNING  | < 50 | 4 000 ms |

`TRANSITION_MAX_FRAME_DELTA_MS = 200` caps per-frame delta added to the transition accumulator — brief excursions into the neutral band don't reset progress.

### Eye-closed threshold
- Default `EYE_CLOSED_THRESHOLD_DEFAULT = 0.30f`.
- After calibration, replaced by `baseline * 0.30f` clamped to `[0.18, 0.45]` where `baseline` is the p90 of collected openness samples. This is the **PERCLOS-70 automotive standard** — a frame counts as "closed" only when the eye is at less than 30 % of its calibrated open baseline. With a typical p90 of 0.85 the resulting threshold sits around 0.255. History: this ratio was `0.60` (way too permissive — pushed threshold to ~0.51, inflating PERCLOS to 30-40 % steady state), then `0.40` (still 33 % above the documented standard; field test showed wide-awake users landing at baseline ~40 because frames with openness 0.30-0.34 counted as closed against a threshold of 0.34). `0.30` now matches the documented standard. The lower bound of 0.18 protects narrow-eyed users (p90 ≈ 0.45) — without a floor, threshold could drop below 0.14 with noisy calibration and blink-like fluctuations would never register.

### Calibration (up to 10 s total: ≤ 3 s gate + 7 s collection)
- `calibrationEnabled` is a `Boolean` passed to the scorer constructor (default `true`).
- **Stabilization gate (≤ 3 s)**: waits for `CALIBRATION_STABILIZATION_MS = 800L` of frames with `eyeOpenness ≥ 0.35`. Tolerates up to `CALIBRATION_STABILIZATION_TOLERANCE_FRAMES = 3` consecutive frames below the threshold (≈ 1 natural blink at 30 fps) without resetting the timer. If the gate hasn't converged after `CALIBRATION_STABILIZATION_MAX_MS = 3 000L`, sample collection **force-starts** with a warning log — the p90 during collection is robust to residual noise.
- **Sample collection (7 s)**: `CALIBRATION_DURATION_MS = 7 000L`, min `CALIBRATION_MIN_SAMPLES = 20` — otherwise defaults are kept.
- `eyeClosedThreshold = p90(samples) * 0.40` clamped to `[0.18, 0.45]`.
- `eyeOpenThreshold = eyeClosedThreshold + 0.10` (clamped so it stays above closed).
- **Post-calibration seeding**: when collection finishes, `perclosWindow` is pre-populated with the ~210 calibration samples re-evaluated against the just-computed `eyeClosedThreshold`, and `monitoringStartMs` is anchored at the start of collection (so the 30 s blink warmup ticks in parallel with calibration). Without the seed, the buffer starts empty right when state flips to NORMAL — a single natural blink then made PERCLOS jump to ~80 % (5 closed frames out of 6 total) and the score spiked. This eliminates the "score já está alto quando termina a calibração" transient.
- **Rationale for the gate**: without it, sampling started on the very first frame after Start — while the user's finger was still leaving the toggle button — dragging the baseline down and producing an inflated `eyeClosedThreshold` (commit `0faa6a5`). **Rationale for the tolerance + hard cap**: an earlier version required 1.5 s of unbroken good frames with `openness ≥ 0.5` and reset the timer on any low frame. Natural blinks reset it every 3-4 s, so calibration could take 15-60 s or never start with glasses/harsh lighting. The tolerance absorbs one blink, the hard cap guarantees calibration always finishes within 10 s.

### Look-away detection (head orientation)
When the driver checks mirrors / dashboard / side windows, MediaPipe's eye blendshapes inflate because eyelids look shorter in oblique perspective — those frames used to be counted as "closed" and drove false alerts. The scorer now pauses accumulation:

- `LOOK_AWAY_YAW_DEGREES = 25f` — |yaw| beyond this → look-away.
- `LOOK_AWAY_PITCH_DEGREES_UP = 20f` and `LOOK_AWAY_PITCH_DEGREES_DOWN = 25f` (asymmetric — driver glances at dashboard).

While `isLookingAway` is true: `perclosWindow` doesn't receive `addLast()`; blink state machine is skipped; yawn timers reset. Buffers **still drain by age** so a long look-away naturally empties stale data. FSM state (NORMAL/WARNING/FATIGUED) is preserved — a brief head turn doesn't demote a legitimate alert.

### NO_FACE handling
`NO_FACE_GRACE_MS = 500L` absorbs single-frame detection glitches. After 500 ms of `isFaceDetected=false`, transition to NO_FACE and clear `perclosWindow`, `blinkTimestamps`, `smoothedScore`, and `calibrationEligibleSinceMs` so stale detection doesn't inflate the score when the face returns.

**In DARK lighting mode the grace is widened to `NO_FACE_GRACE_MS_DARK = 1_500L`** — MediaPipe drops detection more frequently under low light, and a headlight glare followed by 2-3 failed frames must not clear the buffers. The active grace is chosen per-frame via `currentNoFaceGraceMs()` which reads the `lightingModeProvider`.

### Blink detection specifics
- `BLINK_MIN_CLOSED_FRAMES = 3` — debounce for confirming a closure.
- `BLINK_MAX_DURATION_MS = 500L` — closure beyond this counts as PERCLOS, not a blink.
- `BLINK_MIN_OBSERVATION_MS = 30 000L` — first 30 s don't penalize low blink rate (avoids spurious inflation before enough data is collected).
- Rate deviation: healthy range `[15, 24]` blinks / 60 s; penalty scales linearly to the limits `[8, 32]`. The upper bound was widened from 20 → 24 because users focused on the front-camera app blink 20-26/min naturally (focus, dry eyes, lighting), which the original PERCLOS-literature range interpreted as pathological.

## Lighting adaptation (Fase 1)

Cabin lighting varies wildly (tunnels, night driving, oncoming headlights). Without adaptation, MediaPipe drops face detection in low-light rushes, blendshapes get noisy, and the `NO_FACE_GRACE_MS = 500` grace clears PERCLOS/blink buffers on every glare. Fase 1 gives the pipeline explicit awareness of the current lighting condition and reacts on four fronts: preprocessing, camera hardware, scorer grace, and telemetry.

### `LightingMonitor` — 3-state FSM
Module: `app/src/main/java/com/vigilia/app/lighting/LightingMonitor.kt`.

Modes: **NORMAL → LOW_LIGHT → DARK**. Two input signals per call to `update(lux, frameLuminance, tsMs)`:
- **Y-luminance**: mean of an 8×8 grid over the Y plane (0..255), computed in `FaceAnalyzer.computeYPlaneMean` (~0.3 ms/frame).
- **Ambient lux** (optional): `SensorManager` `TYPE_LIGHT`. Devices without the sensor pass `null` and the classifier still works from Y alone.

Asymmetric policy — **"hard to enter, easy to exit"**:

**Entry** requires BOTH Y and lux (when lux is available) to indicate dim (devices without lux fall back to Y alone):
- `ENTER_DARK_Y = 30f`, `ENTER_DARK_LUX = 5f`
- `ENTER_LOW_Y = 65f`, `ENTER_LOW_LUX = 50f`

**Exit** uses Y alone:
- `EXIT_DARK_Y = 45f` (+15 hysteresis with `ENTER_DARK_Y = 30`)
- `EXIT_LOW_Y = 70f` (+5 hysteresis with `ENTER_LOW_Y = 65` — smaller band because front-camera AE tops out around 70-75 in normally-lit rooms; the 3 s lighter dwell prevents flapping)
- (`EXIT_DARK_LUX = 20f` and `EXIT_LOW_LUX = 65f` are kept as constants but no longer consulted on exit.)

**Why AND on entry**: front-camera AE routinely produces frame Y in the 60-90 range even in bright rooms because it spot-meters the face. Under the earlier OR policy, a normally-lit room with lux ≈ 300 but frame Y = 70 flipped to LOW_LIGHT and drove the "low light" banner. AND requires the ambient sensor to also confirm dimness before flipping.

**Why Y-alone on exit**: the lux sensor is often mispositioned in real deployments — covered by a phone holder, reflective mount, or shaded corner — and can stay pinned low even in a bright room. The earlier symmetric AND-exit trapped the mode in LOW_LIGHT/DARK indefinitely in that case. Since Y is what the analysis pipeline actually processes, once Y recovers above the exit threshold, CLAHE/adaptation is no longer needed regardless of what lux reads.

**Dwell**: a candidate mode change must persist for a direction-dependent window before it commits — `DWELL_MS_DARKER = 2_000L` when moving toward DARK, `DWELL_MS_LIGHTER = 3_000L` when moving back to lighter. Slower recovery is intentional: erring on "stay adapted a bit longer" is safe; premature return to NORMAL degrades detection.

Emits `StateFlow<LightingMode>` — `MonitoringService` and `CameraManager` react without touching the frame loop.

### Preprocessing in `FaceAnalyzer` — CLAHE + gamma
When `shouldApplyClahe(mode)` is true (i.e. mode ≠ NORMAL) **and** OpenCV loaded successfully at init, each frame is enhanced before landmarking:

1. `Utils.bitmapToMat` → `cvtColor(RGBA2YUV)` → `Core.split` to isolate the Y channel.
2. **CLAHE** on Y (`clipLimit = 2.0`, tile `8×8`) — local histogram equalization; clip limit prevents amplifying sensor noise in DARK.
3. **Gamma LUT** on Y (`γ_LOW_LIGHT = 1.2`, `γ_DARK = 1.4`) — pre-built via `buildGammaLut()` as a `Mat(1, 256, CV_8U)` and applied with `Core.LUT` (O(pixels)).
4. `Core.merge` → `cvtColor(YUV2RGBA)` → `matToBitmap`.

**Reused `Mat` optimization**: `bgrMat`, `yuvMat`, `yuvChannels`, both LUTs, and the CLAHE object are all allocated once per analyzer (`by lazy`) and reused per frame. Cuts enhancement cost from ~25 ms (fresh alloc) to ~10 ms. Safe because the analysis executor is single-threaded.

**Fallback safety**: `OpenCVLoader.initLocal()` runs in a `try/catch`; on failure `opencvReady = false` and enhancement is silently skipped — the app behaves identically to pre-Fase-1.

### Camera hardware tuning — `CameraManager.applyLightingMode(mode)`
Runs on the Main executor when the `LightingMonitor` emits a mode change. Best-effort — devices that don't support a knob just fall back to their default.

| Knob | NORMAL | LOW_LIGHT | DARK |
|---|---|---|---|
| Exposure compensation | 0 | ~+1 EV (`range.upper/3`) | ~+2 EV (`range.upper*2/3`) |
| `CONTROL_AE_TARGET_FPS_RANGE` | 24–30 | 20–30 | 15–20 |
| `CONTROL_SCENE_MODE` | DISABLED | NIGHT | NIGHT |

FPS is dropped in DARK to leave the AE headroom for longer per-frame exposures. Applied via `Camera2CameraControl.from(cam.cameraControl).captureRequestOptions = ...`. If the camera isn't bound yet, `currentLightingMode` is remembered and reapplied after `bindToLifecycle` (also after `updatePreview`, which resets the `CaptureSession`).

`currentLightingMode` is `@Volatile` — written from Main, read from the analysis executor via the getter passed to `FaceAnalyzer`.

### Scorer grace override
`FatigueScorer` constructor takes two optional providers:
```kotlin
lightingModeProvider: () -> LightingMode = { LightingMode.NORMAL },
ambientLuxProvider:  () -> Float?      = { null },
```
Neither influences the algorithm's math — the mode is used only to swap `NO_FACE_GRACE_MS` for `NO_FACE_GRACE_MS_DARK` in DARK. Both values are echoed into `FatigueAssessment` for telemetry.

### User gate — SetupScreen toggle
`ServiceController.lastLowLightAdaptationEnabled` (default `true`, persisted across Start/Stop cycles) drives the `EXTRA_LOW_LIGHT_ADAPTATION_ENABLED` intent extra. When **false**:
- `LightingMonitor` still classifies every frame (so telemetry captures the ambient condition for post-hoc analysis in Supabase).
- `MonitoringService` skips the `lightingMonitor.mode.collect { cameraManager.applyLightingMode(...) }` coroutine — no Camera2 tuning.
- CLAHE preprocessing is still gated by `shouldApplyClahe(mode)` — since the mode still changes, preprocessing still runs. If a user needs *zero* Fase-1 side effects, this is a known limitation (tracked but not fixed).

### Telemetry
Three new fields flow through `FatigueMetrics` → `FatigueAssessment` → `TelemetryRecord` → CSV → `TelemetryRecordDto` → Supabase `telemetry_records`:
- `ambientLightLux: Float?` (from TYPE_LIGHT, may be null)
- `frameLuminance: Float` (0..255, from computeYPlaneMean)
- `lightingMode: String` ("NORMAL" | "LOW_LIGHT" | "DARK")

Supabase columns `ambient_light_lux`, `frame_luminance`, `lighting_mode` exist on `telemetry_records` (schema migration done manually in the Supabase dashboard).

## Session lifecycle & threading

### MonitoringService

- **Foreground service type**: `camera|location`. Started via `ServiceController.startMonitoring(context, calibrationEnabled: Boolean? = null, lowLightAdaptationEnabled: Boolean? = null)`. Both `null` defaults reuse the last preferences stored in `ServiceController.lastCalibrationEnabled` and `ServiceController.lastLowLightAdaptationEnabled` (both initialized to `true`).
- **Bound by `MonitoringScreen`** with `BIND_AUTO_CREATE` while the screen is composed — this is why `onDestroy()` doesn't fire between Start/Stop cycles on that screen. All lifecycle-critical cleanup runs in `stopMonitoring()` explicitly, with `onDestroy()` as a fallback.
- **`currentAssessment: MutableStateFlow<FatigueAssessment?>`** lives on the companion object — the single source of truth consumed by every UI collector.

### `startMonitoring()`
1. Recreate `scorer = FatigueScorer(calibrationEnabled, lightingModeProvider = { lightingMonitor.mode.value }, ambientLuxProvider = { lastAmbientLux })` from scratch. `lightingMonitor.reset()`.
2. `startForeground()`, `startLocationUpdates()`, `startSensorUpdates()` (registers accel, gyro, and `TYPE_LIGHT` when available).
3. Launch on Main: `acquireWakeLock()` (2 h ceiling, safety only — released on Stop), preload ringtone, `telemetryWriter.startSession()` (guard-rail finalizes any orphaned previous session), set `isProcessRunning = true`, `cameraManager.startCamera(...)`.
4. Inside the frame callback, before `scorer.processFrame(metrics)`: `lightingMonitor.update(lastAmbientLux, metrics.frameLuminance, metrics.timestampMs)`.
5. If `lowLightAdaptationEnabled`, launch a `serviceScope` collector on `lightingMonitor.mode` that calls `cameraManager.applyLightingMode(mode)` on transitions (off the frame loop; cancelled on stop).

### `stopMonitoring()` (async — no `runBlocking`)
1. `isProcessRunning = false` (frame callback returns early after this).
2. `stopAlert()`, `currentAssessment.value = null`, `stopLocationUpdates()`, `stopSensorUpdates()`, `releaseWakeLock()`.
3. `cameraManager.stopCamera()` — releases CameraX + MediaPipe so the next Start reinitializes them (fixes second-session inflation from commit `0faa6a5`).
4. `sessionId = null` **synchronously** — closes the race window where in-flight `handleAssessment` could enqueue a `writeRecord` against a session being torn down.
5. `writerScope.launch { telemetryWriter.stopSession() }` — off Main. Ordering is safe because `TelemetryWriter.writeMutex` serializes `writeRecord()` and `stopSession()`; after `stopSession()` the writer's `csvFile` is null and later writes are no-ops.
6. `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`.

### `handleAssessment()` — alert logic
```
Transition into WARNING or FATIGUED: alert immediately, set lastAlertTimeMs.
Sustained FATIGUED: re-alert after ALERT_COOLDOWN_MS = 8_000 ms.
Return to NORMAL/NO_FACE from WARNING/FATIGUED: stopAlert().
```

### Foreground notification
Ongoing notification (channel `vigilia_monitoring`, IMPORTANCE_LOW) shows current fatigue state + score (or calibration progress). Tapping opens `MainActivity`; a **"Parar"** action button sends `ACTION_STOP` back to the service so the driver can stop monitoring from the notification shade. State labels in the notification are translated to PT-BR (`Normal`, `Atenção`, `Fadigado`, `Rosto não detectado`, `Calibrando`).

### Task-removal behavior
`onTaskRemoved()` is overridden to call `stopMonitoring()`. Rationale: swiping the app off the recent-apps screen is a strong "I'm done" signal from the user — otherwise the alarm would keep firing from an app they thought they'd closed, which is confusing and undermines trust. Home button, screen off, and switching to another app (Waze, WhatsApp, nav) do **not** trigger `onTaskRemoved`, so those cases correctly keep monitoring alive.

### Camera & analyzer threading
- `analysisExecutor`: single-threaded `Executors.newSingleThreadExecutor()`.
- MediaPipe init: `Handler(Looper.getMainLooper()).post { ... }` — required by MediaPipe.
- `FaceLandmarker.detect()` runs on the analysis thread; ~10–30 ms per frame — never blocks Main.

## Local telemetry format

Session directory: `context.filesDir/sessions/{sessionId}/`

Files:
- `session.csv` — one row per 2 s window (25 columns): `sessionId,timestamp,score,state,eyeOpenness,blinkRate,isYawning,isFaceDetected,alertActive,latitude,longitude,speed,accelX,accelY,accelZ,gyroX,gyroY,gyroZ,perclos,perclosContribution,blinkContribution,yawnContribution,ambientLightLux,frameLuminance,lightingMode`. Columns 19–22 are FatigueScorer sub-scores exposed for post-hoc diagnosis — they let you decompose the aggregated `score` and calibrate weights from real data. Columns 23–25 are the Fase-1 lighting context (see "Lighting adaptation"). Old CSVs (18 columns from pre-sub-score sessions, 22 columns from pre-Fase-1 sessions) are still parseable by `SyncRepository.parseCsvLine`; missing columns just come back as null.
- `session_summary.json` — aggregated: `sessionId, startTime, endTime, durationMs, totalAlerts, dominantState, averageScore, peakScore`
- `.synced` — empty marker written by `SyncRepository` after successful upload to Supabase

`TelemetryWriter.writeMutex` (`kotlinx.coroutines.sync.Mutex`) protects both writeRecord and stopSession. `startSession()` includes a guard-rail: if a previous session was never explicitly stopped, it finalizes that summary first so orphan folders never accumulate.

## Supabase integration

- Config: `SUPABASE_URL` and `SUPABASE_KEY` in `local.properties` → injected into `BuildConfig` via `buildConfigField` in `app/build.gradle.kts`. `local.properties` is gitignored. The key used is a `sb_publishable_*` (public by design); **RLS policies on the Supabase side are the actual security boundary** — verified configured for `profiles`, `sessions`, `telemetry_records`.
- Client: `SupabaseClient.client` — installs `Auth` (with scheme `vigilia`, host `reset-password`) and `Postgrest`.
- Auth ops (`AuthRepository`): `signIn`, `signUp` (also upserts `ProfileDto`), `signOut`, `sendPasswordReset`, `updatePassword`, `isLoggedIn`, `refreshAndGetUserId`.
- Password reset deep link: `vigilia://reset-password` → handled in `MainActivity.onNewIntent()` via `SupabaseClient.client.handleDeeplinks(intent)`; sets a flag consumed by `VigiliaNavGraph` to navigate to `ResetPasswordScreen`.
- Sync (`SyncWorker` — CoroutineWorker, unique work, network required, exponential backoff 30 s, max 3 attempts):
  - Walks `filesDir/sessions/*` looking for folders without `.synced`.
  - Parses `session_summary.json` → `SessionSummaryDto` → upsert `sessions`.
  - Streams `session.csv` line by line → `TelemetryRecordDto` batches of 100 → upsert `telemetry_records`.
  - CSV parser validates `p.size >= 9` before indexing and uses `?.toDoubleOrNull()` / `?.toFloatOrNull()` for optional columns [9..17]; sub-scores [18..21] and lighting fields [22..24] use `if (p.size > N) p[N].toFloatOrNull() else null` guards so older CSV formats parse cleanly.
  - Writes `.synced` on success. `SyncWorker.enqueue(context)` is called from `MonitoringService.onDestroy()`.

## UI

### Navigation (`VigiliaNavGraph`)
- Routes: `auth`, `forgot_password`, `reset_password`, `setup`, `monitoring`, `history`.
- Start destination decided by `AuthRepository().isLoggedIn()` in `MainActivity`.
- Bottom bar shown only outside the auth flow.
- `ActiveMonitoringBanner` renders whenever `MonitoringService.currentAssessment != null` and the current route isn't auth-related.
- Auth guard: `LaunchedEffect` observes `AuthUiState.isLoggedIn` and force-navigates back to `auth` with `popUpTo(0)` on logout.
- Password reset deep link: `LaunchedEffect` reacts to `isPasswordResetDeepLink` state.

### Screens
- **AuthScreen**: sign-in / sign-up toggle. Uses `android.util.Patterns.EMAIL_ADDRESS`; password ≥ 8 chars. Errors mapped to PT-BR messages ("E-mail ou senha incorretos", "Confirme seu e-mail antes de entrar", etc.). Sign-up creates a `profiles` row via `ProfileDto` upsert.
- **ForgotPasswordScreen**: sends reset email via Supabase, shows confirmation state.
- **ResetPasswordScreen**: reached via deep link; calls `AuthRepository.updatePassword`.
- **SetupScreen**: CAMERA and location permission launcher (`ActivityResultContracts.RequestMultiplePermissions`). Two toggles persisted through `ServiceController`: calibration and low-light adaptation. Start button enabled iff CAMERA granted. Logout via `AuthViewModel.signOut`.
- **MonitoringScreen**: `AndroidView { PreviewView }` inside `Box`. `ServiceConnection` binds to `MonitoringService` to `attachPreview`/`detachPreview`. Toggle button start/stop calls `viewModel.startMonitoring/stopMonitoring`. Overlays: state pill, radial score gauge, calibration progress bar, positioning warning banner, and `LightingWarningBanner` when mode is LOW_LIGHT/DARK. `MonitoringViewModel` maintains `frameWindow` (rolling 60 s of face-detected flags) to trigger a "reposicione o rosto" warning when > 30 % of frames are NO_FACE; it also unpacks `assessment.lightingMode` (String) into the `LightingMode` enum via `runCatching { LightingMode.valueOf(...) }`.
- **HistoryScreen**: `LazyColumn` of `SessionSummary` items sorted newest-first. `SessionRepository.getSessions()` reloads on `Lifecycle.State.RESUMED` via `repeatOnLifecycle`. Export via `FileProvider` + `Intent.ACTION_SEND_MULTIPLE` (CSV + JSON).

### Theme
`VigiliaTheme` — dark-only Material 3. Custom colors in `Color.kt` (BackgroundDark, SurfaceDark, AccentAmber, AlertRed, NormalGreen, TextPrimary, TextSecondary).

## Permissions & manifest

Declared:
- `INTERNET` (Supabase)
- `CAMERA` (dangerous — required for face detection)
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (dangerous — for trip context; degrades gracefully if denied)
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` + `FOREGROUND_SERVICE_LOCATION`
- `WAKE_LOCK`
- `POST_NOTIFICATIONS` (Android 13+)

Manifest highlights:
- `<uses-feature android:name="android.hardware.camera" android:required="false" />` — installable on devices without a camera (degrades).
- `MainActivity` has an intent filter with `android:scheme="vigilia" android:host="reset-password"` for the Supabase reset deep link.
- `MonitoringService` has `android:foregroundServiceType="camera|location"` and is not exported.
- `FileProvider` authority `com.vigilia.app.fileprovider` with `xml/file_paths.xml` exposing `filesDir/sessions/` for CSV/JSON export.

## Testing

Unit tests (`app/src/test/`):
- **FatigueScorerTest** — 21 tests: PERCLOS calc, blink detection, yawn (1.5 s trigger), NORMAL↔WARNING↔FATIGUED hysteresis, reset, calibration stabilization + tolerance + hard cap, consecutive-session score integrity, look-away not inflating PERCLOS, look-away preserving buffer, PERCLOS-alone can't promote to FATIGUED, NO_FACE grace, post-calibration perclosWindow seeding.
- **TelemetryWriterTest** — 3 tests: two consecutive sessions both persist summaries, `startSession` guard-rail finalizes unfinished previous session, single session write/stop.
- **SessionRepositoryTest** — 2 tests: sessions sorted by startTime desc, folder path resolution.
- **LightingMonitorTest** (`app/src/test/java/com/vigilia/app/lighting/`) — 11 tests: starts in NORMAL, single dark frame doesn't flip, sustained 2 s → DARK, asymmetric 3 s exit dwell, brief 1 s tunnel doesn't commit, null-lux classification, reset.
- **FaceAnalyzerLightingModeTest** (`app/src/test/java/com/vigilia/app/camera/`) — 6 tests: `shouldApplyClahe(mode)` decisions, `gammaFor(mode)` mapping, gamma ordering (DARK > LOW_LIGHT > NORMAL).
- **LuminanceCalcTest** (same directory) — 6 tests: `computeYPlaneMean` on uniform buffers, unsigned byte masking (0xFF), rowStride padding, zero-dim guard, buffer bounds.

Patterns:
- JUnit 4 + `@Rule TemporaryFolder` for file I/O isolation.
- No Mockito — `FatigueMetrics` values are hand-crafted per frame.
- `runBlocking { ... }` inside tests for suspending TelemetryWriter calls.
- Uncovered by unit tests: `MonitoringService`, `MonitoringViewModel`, `SetupViewModel`, `AuthViewModel`, `HistoryViewModel`, `CameraManager`, `FaceAnalyzer` (end-to-end enhance pipeline), `AuthRepository`, `SyncRepository`, `SyncWorker`. These are verified by smoke testing on device. Fase-1 helpers (`shouldApplyClahe`, `gammaFor`, `computeYPlaneMean`, `LightingMonitor.update`) are unit-tested; the OpenCV Mat operations in `FaceAnalyzer.enhance` are not (needs instrumented tests).

## File organization

```
app/src/main/java/com/vigilia/app/
├── MainActivity.kt                   # Nav host + deep link handling
├── service/
│   ├── MonitoringService.kt          # Foreground service, monitoring loop, LightingMonitor wiring
│   ├── ServiceController.kt          # start/stop helper + calibration & low-light preferences
│   └── SyncWorker.kt                 # WorkManager job for Supabase upload
├── camera/
│   ├── CameraManager.kt              # CameraX binding + Camera2Interop lighting-mode tuning
│   └── FaceAnalyzer.kt               # MediaPipe landmarker + EAR + yaw/pitch + OpenCV CLAHE
├── lighting/
│   └── LightingMonitor.kt            # NORMAL/LOW_LIGHT/DARK FSM with hysteresis + dwell
├── domain/
│   ├── model/
│   │   └── FatigueModels.kt          # FatigueMetrics, FatigueAssessment, SessionSummary, TelemetryRecord, FatigueState
│   └── scoring/
│       └── FatigueScorer.kt          # Algorithm + FSM + dark-mode NO_FACE grace
├── data/
│   ├── telemetry/
│   │   └── TelemetryWriter.kt        # Local CSV/JSON + writeMutex
│   ├── repository/
│   │   ├── SessionRepository.kt      # Read past sessions
│   │   ├── AuthRepository.kt         # Supabase Auth
│   │   └── SyncRepository.kt         # Upload to Supabase (Postgrest)
│   └── remote/
│       ├── SupabaseClient.kt         # Supabase-kt singleton
│       └── dto/                      # ProfileDto, SessionSummaryDto, TelemetryRecordDto
└── ui/
    ├── navigation/VigiliaNavGraph.kt
    ├── auth/{AuthScreen, AuthViewModel, ForgotPasswordScreen, ResetPasswordScreen}
    ├── setup/{SetupScreen, SetupViewModel}
    ├── monitoring/{MonitoringScreen, MonitoringViewModel}
    ├── history/{HistoryScreen, HistoryViewModel}
    └── theme/{Theme, Color, Type}
```

## Dependencies (see `gradle/libs.versions.toml`)

- **CameraX** 1.6.1: core, camera2, lifecycle, view (camera2-interop used for lighting-mode tuning)
- **MediaPipe** Tasks Vision 0.10.14 (not ML Kit — this doc previously misidentified the library)
- **OpenCV** 4.11.0 (Fase 1 — CLAHE + gamma LUT preprocessing in `FaceAnalyzer`)
- **Compose** BOM 2026.05.00 (Material 3)
- **Navigation Compose** 2.9.8
- **Supabase-kt** 3.1.4 (auth + postgrest + android engine) with Ktor 3.1.2 client
- **WorkManager** 2.10.1
- **Play Services Location** 21.3.0
- **Kotlin Serialization** 2.3.21 (matches Kotlin version)
- **Testing**: JUnit 4.13.2, org.json 20231013 (for parsing session summaries in tests)

## Adjusting fatigue thresholds

All tuning constants are in `FatigueScorer.kt`'s `companion object`. Common tweaks:

- Increase `TRANSITION_NORMAL_TO_WARNING_MS` (currently 3000) to be less trigger-happy on WARNING.
- Increase `LOOK_AWAY_YAW_DEGREES` (currently 25) if drivers legitimately turn further while still monitoring the phone camera.
- Increase `CALIBRATION_STABILIZATION_MS` (currently 800) if devices with fast MediaPipe init still catch too-early samples.
- Note the score weights sum to 90 — this is intentional (see "Score weights"). Under 100 means the score never saturates from any two-signal combination alone, preserving the "FATIGUED requires PERCLOS + at least one secondary" invariant.
- To retune Fase-1 lighting behavior, edit constants in `LightingMonitor.companion object` (thresholds, dwell) and `FaceAnalyzer.companion object` (`GAMMA_LOW_LIGHT`, `GAMMA_DARK`, `CLAHE_CLIP_LIMIT`, `CLAHE_TILE_SIZE`). Camera2 knobs live in `CameraManager.applyLightingMode`.

## Debug telemetry

Session files:
```bash
adb pull /data/data/com.vigilia.app/files/sessions/
```

Log tags of interest:
- `FaceAnalyzer` — per-frame blinks, EAR, jawOpen, yaw, pitch.
- `FatigueScorer` — smoothed score, state, perclos, blinkRate, isYawning, lookAway.
- `MonitoringService` — location, ringtone, WakeLock errors.
- `SyncWorker` / `SyncRepository` — upload state.
- `TelemetryWriter` — guard-rail trigger.

WakeLock check (should be absent after Stop):
```bash
adb shell dumpsys power | grep vigilia
```

## Performance notes

- Analysis @ 640×480 (fallback closest higher then lower).
- `KEEP_ONLY_LATEST` backpressure — frames dropped if MediaPipe falls behind.
- Telemetry batched at 2 s intervals; MediaPipe `detect()` on a dedicated executor never touches Main.
- Exponential smoothing (α=0.3) suppresses per-frame jitter.
- PERCLOS window uses an `ArrayDeque<FrameRecord>` drained by age each frame (O(k) per frame where k = expired frames — typically 0 or 1).

## Known limitations & scope-out items

- **Multi-face selection**: FaceLandmarker requests up to `MAX_FACES = 4` (driver + up to 3 back-seat passengers). `FaceAnalyzer.pickDriverIndex` picks the driver by bbox area over 5 stable central landmarks (nose/forehead/chin/cheeks), with a sticky bias that prefers the face closest to the previously-selected driver's center provided its area is ≥70% of the largest candidate. Prevents flicker when a passenger leans forward and briefly overtakes the driver's bbox. Edge case not handled: passenger with a face exactly the same size and position as the driver (rare — child on lap, etc.).
- **compileSdk = 37 vs targetSdk = 34**: intentional — we build against the newest API for future-proofing but target 34 for Play Store compliance.
- **`isLookingAway` not surfaced to UI**: computed in the scorer but not part of `FatigueAssessment`. Adding a "Olhando ao lado" badge on `MonitoringScreen` is a small future improvement.
- **PT-BR only (scope decision, not a limitation)**: user-facing strings are inlined in Compose (`"Iniciar monitoramento"`, `"Fadigado"`, etc.). `strings.xml` is intentionally near-empty. Full i18n / localization is out of scope by design — the app targets Brazilian drivers only.
- **MonitoringService / camera code lacks unit tests**: covered by smoke testing.

## History of decisions worth remembering

- **Weights are 65/15/25, not 50/30/20** (as an earlier draft of this doc suggested). Total = 105, clamped to 100. This keeps FATIGUED (score > 70) unreachable by any single signal. `SCORE_WEIGHT_BLINK` was pulled from 20 → 15 in commit `3c30b8a` after focused/dry-eye blink rates (~25/min) were tripping WARNING on their own.
- **PERCLOS window is 30 s** — briefly tried 6 s (commit before `9f97457`); it was too sensitive to natural look-away and single 5 s eye-closure episodes. Reverted.
- **WARNING → FATIGUED threshold is 70** — briefly tried 55 (before `9f97457`); PERCLOS = ~0.85 alone would trip it. Reverted to 70 so combination of signals is required.
- **Calibration is capped at 10 s total** — 800 ms stabilization gate with `openness ≥ 0.35` and up to 3 blink-tolerance frames, plus a hard 3 s upper bound that force-starts collection when framing/lighting is chronically bad. Sample collection is 7 s. Originally added as an unbounded 1.5 s / `openness ≥ 0.5` gate (`0faa6a5`) that could stall for a minute or more with natural blinks or glasses; this iteration makes it robust and time-bounded.
- **Camera is torn down on every Stop** (commit `0faa6a5`) — otherwise the second consecutive session on the same screen inherited a hot MediaPipe pipeline with no warmup gap, causing score inflation.
- **`stopMonitoring()` is fully async** (commit `558a30b`) — no `runBlocking` on Main. The `TelemetryWriter.writeMutex` provides the ordering guarantee that used to require `join()`.
- **WakeLock ceiling is 2 h, released on Stop** (commit `558a30b`) — the previous 10 h ceiling drained battery when `onDestroy()` didn't fire between Start/Stop cycles.
- **Head yaw / pitch pause scoring during look-away** (commit `9f97457`) — mirrors, dashboard checks, side glances no longer inflate PERCLOS.
- **Raise the WARNING trigger and widen the healthy blink range**: `TRANSITION_NORMAL_TO_WARNING_SCORE` 40 → 50, `TRANSITION_NORMAL_TO_WARNING_MS` 2 000 → 3 000, `BLINK_RATE_MAX` 20 → 24, `BLINK_DEVIATION_LIMIT_HIGH` 25 → 32. Even after the PERCLOS-70 realignment (`3c30b8a`), users blinking at 25/min (normal when focused on the front camera) were still hitting `blink deviation = 1.0` and landing near the WARNING threshold. The new numbers mean PERCLOS alone needs 77 % closure sustained 3 s, and blink deviation caps out at rates over 30/min — real fatigue signals still trigger, casual focused blinking does not. Recovery threshold (30) and FATIGUED gate (70) unchanged.
- **Seed `perclosWindow` from calibration samples at the end of the collection phase** — otherwise the buffer is empty at the exact moment state flips to NORMAL, and a single natural blink (5 closed frames in a buffer of ~6) inflates PERCLOS to ~80 %, spiking the score right after calibration. `calibrationSamples` was extended to `MutableList<Pair<Long, Float>>` (timestamp + openness) so the samples can be re-evaluated against the newly-computed threshold and pushed into the window. `monitoringStartMs` is also anchored at the start of the collection phase so blink warmup runs in parallel with calibration.
- **PERCLOS-70 realignment + sub-score telemetry**: `EYE_CLOSED_RATIO` dropped from 0.60 to 0.40, `EYE_CLOSED_MAX` from 0.60 to 0.45, `SCORE_WEIGHT_BLINK` from 20 to 15, `TRANSITION_WARNING_TO_NORMAL_SCORE` from 25 to 30. Before this the calibrated eye-closed threshold sat around 0.51, so normally-open frames were counted as closed → PERCLOS baseline was ~30 % (should be ~5 %), score got stuck around 26-30, and recovery from WARNING to NORMAL never converged (25 threshold unreachable). Also added four sub-score columns to `session.csv` (`perclos`, `perclosContribution`, `blinkContribution`, `yawnContribution`) and mirror fields in `FatigueAssessment` / `TelemetryRecord` / `TelemetryRecordDto` so future weight tuning can be data-driven instead of guesswork.
- **Fase 1 — lighting adaptation subsystem**: added `LightingMonitor` (NORMAL/LOW_LIGHT/DARK FSM with +15 hysteresis and asymmetric dwell 2 s darker / 3 s lighter), driven by mean Y-luminance of each frame plus optional `TYPE_LIGHT` lux. Feeds three consumers: (1) `FaceAnalyzer` applies OpenCV CLAHE (`clipLimit=2.0`, tile `8×8`) + gamma LUT (`γ=1.2` LOW_LIGHT, `γ=1.4` DARK) on Y, with reusable Mats keeping cost around ~10 ms/frame; (2) `CameraManager.applyLightingMode` hot-applies EV compensation (+1/+2 EV nudges), target FPS ranges (24-30 → 20-30 → 15-20), and `CONTROL_SCENE_MODE_NIGHT` via `Camera2CameraControl`; (3) `FatigueScorer` widens `NO_FACE_GRACE_MS` from 500 ms → 1500 ms in DARK so headlight-induced detection drops don't clear buffers. Motivation: night driving and tunnels caused runs of failed frames where PERCLOS/blink windows kept resetting, and blendshapes got noisy from low contrast. `SetupScreen` gates the whole subsystem via a `lastLowLightAdaptationEnabled` toggle (default on, persisted). Three new telemetry columns (`ambient_light_lux`, `frame_luminance`, `lighting_mode`) added to `session.csv`, `TelemetryRecordDto`, and the Supabase `telemetry_records` table (schema migration applied manually in the dashboard). OpenCV 4.11.0 added as a dependency; load failures degrade silently to raw frames.
- **LightingMonitor: raise Y thresholds and switch entry from OR to AND**: after field testing, the "Luz reduzida" / "Ambiente escuro" banner was firing in normally-lit rooms because entry required only one of Y or lux to indicate dim. Front-camera AE spot-meters on the face and routinely produces frame Y in the 60-90 range even under lux ≈ 300, so any dim frame flipped the mode. Two changes to `LightingMonitor.kt`: (1) entry logic now requires BOTH signals (when both available) to agree — matches the existing AND on exit. Devices with null lux fall back to Y alone. (2) Y thresholds lowered: `ENTER_DARK_Y` 40 → 30, `ENTER_LOW_Y` 90 → 65, `EXIT_DARK_Y` 55 → 45, `EXIT_LOW_Y` 105 → 80. Adaptation still kicks in when it should (both frame and ambient dim, or Y very low on no-sensor devices), but stationary users in well-lit rooms no longer see the banner. Also rewrote the DARK banner message: was "Precisão reduzida. Ligue a luz interna do veículo." — this instructed drivers to take an action that could be dangerous in some contexts (dark streets, security). New copy is purely informative: "Ambiente escuro / Adaptação noturna ativa — precisão pode variar."
- **Align `EYE_CLOSED_RATIO` with the documented PERCLOS-70 standard + lower `EXIT_LOW_Y`**: after the previous tuning, field test showed baseline score of ~40 in a wide-awake user sitting at a computer (immediately after calibration and sustained through casual use). Root cause: `EYE_CLOSED_RATIO` was 0.40 while the code comment and CLAUDE.md always claimed the value was aligned with PERCLOS-70's "30 % of baseline" rule. Frames with openness 0.30-0.34 (screen focus, mild squint under artificial light) were being classified as closed against a threshold of p90 × 0.40 ≈ 0.34. Also in the same session: LOW_LIGHT banner stayed on after the room was brightened — DARK unstuck correctly (EXIT_DARK_Y=45 is achievable) but LOW_LIGHT couldn't clear EXIT_LOW_Y=80 because front-camera AE tops out around 70-75 in a typical bright room. Two fixes: `EYE_CLOSED_RATIO` 0.40 → 0.30 (matches the documented standard, threshold now ~0.255); `EXIT_LOW_Y` 80 → 70 (5-pt hysteresis with ENTER=65; the 3 s lighter dwell blocks flapping without needing a wide gap). Baseline score for stationary users drops to expected 5-15 range. Real fatigue detection unchanged — PERCLOS ≥ 0.7 (< 25 % openness sustained) still promotes.
- **LightingMonitor: exit uses Y alone (asymmetric with AND-entry) + slower FATIGUED recovery**: two problems in the same field session — (a) the "Luz reduzida" banner never turned off after activating because the exit logic (symmetric AND with the entry) required both frame Y and ambient lux to agree on recovery; real-world lux sensors are often mispositioned (phone holder, mount, reflective bezel) and stay pinned low even in bright rooms; and (b) the prior tuning that fixed the "stuck 15-30 s in FATIGUED" swung too far — the score visually collapsed in ~150 ms after a yawn released and the state left FATIGUED in ~3 s, both feeling premature. Fixes: LightingMonitor exit changed to Y-only (lux ignored on the recovery path but still required for entry — "hard to enter, easy to exit"); `YAWN_RESET_MS` 3 000 → 4 000; `TRANSITION_FATIGUED_TO_WARNING_MS` 3 000 → 4 000 (now symmetric with the WARNING → FATIGUED upgrade gate); `SMOOTHING_ALPHA` 0.3 → 0.2 (score converges in ~10-15 frames instead of ~4-5). Expected FATIGUED recovery: 8-10 s (was 5-6 s post-first-tuning, 15-30 s originally).
- **Tuning: reduce false positives for stationary users and speed up FATIGUED recovery**: after field testing, a user sitting still at a computer (no glasses, calibration on) reached WARNING from baseline signal, then a single yawn tipped him into FATIGUED — with recovery taking 15-30 s. Five constant changes: `SCORE_WEIGHT_BLINK` 15 → 10 (focus-blink at the phone camera drives deviation near 1.0 for wide-awake users, keeping high weight pushed the baseline near WARNING); `SCORE_WEIGHT_YAWN` 25 → 15 (yawn is tier-2 in the automotive literature — should not promote to FATIGUED unilaterally); `YAWN_RESET_MS` 5 000 → 3 000 (physical yawn is 1.5-2 s, the 5 s state extension pinned the score high during recovery); `TRANSITION_FATIGUED_TO_WARNING_MS` 5 000 → 3 000 (asymmetric with the 4 s upgrade gate was unjustified); `EYE_CLOSED_MIN` 0.15 → 0.18 (noisy calibrations could pin the threshold at 0.15 and misclassify half-open frames as closed). Weight sum drops from 105 to 90, preserving the "no single signal reaches FATIGUED" invariant with a bit more headroom. Real fatigue detection (sustained PERCLOS ≥ 50 % with corroborating signals) is unchanged and covered by the existing `WARNING to FATIGUED transition` test. New regression test `single yawn from a WARNING baseline does not promote to FATIGUED` guards the specific scenario reported.
- **Multi-face handling — driver selection by bbox area with sticky bias**: field test with a rear-seat passenger caused MediaPipe to silently swap between measuring the driver and the passenger frame-to-frame (both had detection confidence above the 0.5 gate; MediaPipe orders results by confidence, not size, and returned whichever won that frame). `setNumFaces(1)` → `setNumFaces(4)` (covers driver + 3 rear passengers; MediaPipe cost scales with actual detected faces so solo-driver latency is unchanged). New `FaceAnalyzer.pickDriverIndex` selects among all returned faces: (1) computes bboxes from 5 stable central landmarks (nose 1, forehead 10, chin 152, cheeks 234/454) rather than all 478 points — periphery is foreshortened at oblique angles and would artificially shrink the total bbox; (2) among candidates whose area is ≥ `STICKY_AREA_RATIO = 0.70` of the largest, picks the one closest to the previously-selected driver's center, if that distance is within `STICKY_CENTER_TOLERANCE = 0.15` (normalized coords); (3) falls back to the largest when no sticky match qualifies. Sticky state (`lastDriverCenterX/Y`) is reset on `close()` and on NO_FACE so a new session starts clean. Refactored `extractYawPitchDegrees` to accept `index` (matches the parallel indexing across `faceBlendshapes()` / `faceLandmarks()` / `facialTransformationMatrixes()`). Added defensive guard: if the three collections come back with mismatched sizes, `safeIndex` falls back to `-1` (treat as NO_FACE) rather than risk IndexOutOfBounds. New pure math helper `decomposeYawPitchFromMatrix` extracted so the Y-X-Z Euler decomposition is unit-testable. Total 15 new tests in `FaceAnalyzerFaceSelectionTest.kt`. `FatigueMetrics` schema unchanged — deferred adding a `selectedFaceCount` column to avoid ripple across CSV/DTO/Supabase schema; multi-face count logged at DEBUG only.
- **Password reset flow — gate submit on Supabase session ready**: audit found a P1 race in the deep-link reset flow. `SupabaseClient.client.handleDeeplinks(intent)` in `MainActivity.processDeepLink` parses the token async, so a fast user could hit "Salvar nova senha" before the session was established — the update failed and, worse, the single-use token in the URL was already consumed, silently forcing the user to start over. Fix in three layers: (1) `AuthRepository.isSessionReady: StateFlow<Boolean>` mirrors `SupabaseClient.client.auth.sessionStatus` mapped to `is SessionStatus.Authenticated`; (2) `AuthViewModel` collects it into `AuthUiState.isResetSessionReady` in `init`; (3) `ResetPasswordScreen` disables the button with `enabled = !isLoading && isResetSessionReady` and shows a "Preparando sessão de redefinição…" hint until ready. Also added a defensive `enterResetPasswordFlow()`/`leaveResetPasswordFlow()` pair called from the screen's `DisposableEffect` — starts a 10 s timeout that surfaces "Link de redefinição expirado. Solicite um novo e-mail." if the session never establishes (corrupt/expired token). Extended `mapError` with an `expired`/`invalid token`/`otp_expired` clause so the same message shows if `updateUser` itself returns the error. Other auth flows unaffected: `sessionStatus` is `Authenticated` immediately after normal sign-in so the gate is transparent outside the reset flow.
- **R8 minification + resource shrinking enabled for release**: previously `isMinifyEnabled = false` and `proguard-rules.pro` had only boilerplate. APK release size was ~109 MB, method signatures un-obfuscated, and the codebase was carrying dead code from Supabase/Ktor/Compose. Enabled `isMinifyEnabled = true` and `isShrinkResources = true`. `proguard-rules.pro` now covers: (1) kotlinx.serialization — `-keepclassmembers @kotlinx.serialization.Serializable ...` pattern for the `$Companion`/`$serializer` lookup, plus `com.vigilia.app.data.remote.dto.**` fallback; (2) enums touched via `.name`/`.valueOf` — `FatigueState` (persisted to CSV/JSON, read back on upload) and `LightingMode` (unpacked in MonitoringViewModel); (3) whole-package keeps for native/reflection-heavy libs: MediaPipe (`com.google.mediapipe.**` + `com.google.protobuf.**`), OpenCV (`org.opencv.**`), Ktor (`io.ktor.**`), Supabase-kt (`io.github.jan.supabase.**`), CameraX (`androidx.camera.**`); (4) WorkManager `Worker`/`ListenableWorker`/`CoroutineWorker` subclasses (SyncWorker is discovered by class name); (5) `-dontwarn javax.lang.model.**` for CameraX's compile-time AutoValue/JavaPoet references that R8 flagged as missing on the Android runtime classpath. Since distribution is side-load (not Play Store), `signingConfig = signingConfigs.getByName("debug")` is the permanent release signing — no keystore rotation needed. APK release size dropped from ~109 MB to ~80 MB (27 % reduction). Bulk of the remaining size is inelastic native assets (`face_landmarker.task` + `libopencv_java4.so`). Unit tests run against unminified debug — smoke test on device is mandatory before shipping any rule change; the auth / monitoring / sync flows must be verified end-to-end.
