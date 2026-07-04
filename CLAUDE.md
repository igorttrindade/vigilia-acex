# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Vigília** is an Android fatigue-monitoring app for drivers. It uses on-device face landmark detection (MediaPipe Face Landmarker) to estimate eye closure (PERCLOS), blink rate, yawns, and head orientation, and produces a fatigue score 0–100 with a hysteresis state machine (NORMAL → WARNING → FATIGUED). Sessions are persisted locally (CSV + JSON) and optionally synced to Supabase (Auth + Postgrest) via WorkManager. Alerts are played through the Ringtone API when the driver appears fatigued.

**compileSdk**: 37 | **targetSdk**: 34 | **minSdk**: 26 | **Kotlin**: 2.3.21 | **AGP**: 9.2.1 | **Compose BOM**: 2026.05.00 | **Java**: 17

## Build Commands

```bash
# Debug build
./gradlew :app:assembleDebug

# Release build (isMinifyEnabled=false today — see "Known Limitations")
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
│   ├── CameraManager + CameraX
│   ├── FaceAnalyzer (MediaPipe Face Landmarker)
│   ├── FatigueScorer
│   ├── TelemetryWriter
│   ├── FusedLocationProviderClient (GPS)
│   └── SensorManager (accel + gyro)
├── ServiceController (start/stop + last calibration preference)
└── SyncWorker (CoroutineWorker via WorkManager)

Domain
├── FatigueScorer (PERCLOS + blinks + yawns + hysteresis FSM + calibration)
└── FatigueModels (FatigueMetrics, FatigueAssessment, SessionSummary, TelemetryRecord, FatigueState)

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

1. **Camera capture**: CameraX (front) → ImageAnalysis 640×480 (via `ResolutionSelector` with `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`) → single-threaded `analysisExecutor` with `STRATEGY_KEEP_ONLY_LATEST` backpressure.
2. **Face detection**: `FaceAnalyzer` runs MediaPipe FaceLandmarker (asset `face_landmarker.task`) with `setOutputFaceBlendshapes(true)` and `setOutputFacialTransformationMatrixes(true)`. Model init is scheduled on the Main Looper (MediaPipe requires it) — early frames return NO_FACE until the model is ready.
3. **Per-frame extraction**:
   - `eyeBlinkLeft` / `eyeBlinkRight` blendshapes (0=open, 1=closed) → inverted to give openness probability.
   - `jawOpen` blendshape → mouth-open probability (not "smiling proxy" — real mouth openness).
   - EAR (Eye Aspect Ratio) computed geometrically from landmarks 33/133/159/145 (left) and 263/362/386/374 (right) — invariant to lens reflections from glasses; `min(blendshape, EAR)` is used so reflections cannot hide a blink.
   - Head yaw and pitch decomposed from the 4×4 facial transformation matrix (column-major, Y-X-Z Euler: `pitch=asin(-m[9])`, `yaw=atan2(m[8], m[10])`).
4. **`FatigueMetrics`** is emitted for every frame (leftEyeOpen, rightEyeOpen, mouthOpen, isFaceDetected, timestamp, headYawDegrees, headPitchDegrees).
5. **Scoring** (`FatigueScorer.processFrame`) — see next section.
6. **Alerts** (Ringtone, USAGE_ALARM, 3s clip, 8s cooldown for sustained FATIGUED).
7. **Telemetry** — every 2 s writes an 18-column record to `session.csv`; on Stop, `session_summary.json` is generated.

## FatigueScorer — algorithm details

Constants live in the `companion object` of `FatigueScorer`. **These are the values in the code, which are the authoritative source; they were tuned iteratively and diverge intentionally from earlier drafts of this file.**

### Signal windows
| Signal | Window | Purpose |
|---|---|---|
| PERCLOS | **30 000 ms** | % of eye-closed frames — primary fatigue signal |
| Blink   | **60 000 ms** | Rolling blink count for rate deviation |
| Yawn    | ≥ 1 500 ms sustained open + 5 000 ms cooldown | Secondary fatigue signal |

### Score weights (sum = 105, clamped to 0–100)
- `SCORE_WEIGHT_PERCLOS = 65f`
- `SCORE_WEIGHT_BLINK = 15f`
- `SCORE_WEIGHT_YAWN = 25f`

**Design decision**: total weight slightly exceeds 100 on purpose. It preserves headroom so PERCLOS alone (worst case = 65) sits below the FATIGUED threshold (70) — FATIGUED requires a *combination* of PERCLOS + blink deviation + yawn, not any single signal. `SCORE_WEIGHT_BLINK` was lowered from 20 to 15 so a moderately high blink rate (under focus / dry eyes / stress) can't sit right at the WARNING threshold on its own.

Exponential smoothing: `smoothedScore = 0.3 * rawScore + 0.7 * prev` (`SMOOTHING_ALPHA = 0.3`).

### State machine — hysteresis transitions
| From | To | Score threshold | Sustained duration |
|---|---|---|---|
| NORMAL   | WARNING  | > 50 | 3 000 ms |
| WARNING  | FATIGUED | > 70 | 4 000 ms |
| WARNING  | NORMAL   | < 30 | 5 000 ms |
| FATIGUED | WARNING  | < 50 | 5 000 ms |

`TRANSITION_MAX_FRAME_DELTA_MS = 200` caps per-frame delta added to the transition accumulator — brief excursions into the neutral band don't reset progress.

### Eye-closed threshold
- Default `EYE_CLOSED_THRESHOLD_DEFAULT = 0.30f`.
- After calibration, replaced by `baseline * 0.40f` clamped to `[0.15, 0.45]` where `baseline` is the p90 of collected openness samples. This is the **PERCLOS-70 automotive standard** — a frame counts as "closed" only when the eye is at less than 30 % of its calibrated open baseline. A previous iteration used `0.60 * baseline` (clamped 0.15..0.60), which pushed the threshold to ~0.51 with a normal user, misclassifying open frames as closed and inflating PERCLOS steady-state to 30-40 %.

### Calibration (up to 10 s total: ≤ 3 s gate + 7 s collection)
- `calibrationEnabled` is a `Boolean` passed to the scorer constructor (default `true`).
- **Stabilization gate (≤ 3 s)**: waits for `CALIBRATION_STABILIZATION_MS = 800L` of frames with `eyeOpenness ≥ 0.35`. Tolerates up to `CALIBRATION_STABILIZATION_TOLERANCE_FRAMES = 3` consecutive frames below the threshold (≈ 1 natural blink at 30 fps) without resetting the timer. If the gate hasn't converged after `CALIBRATION_STABILIZATION_MAX_MS = 3 000L`, sample collection **force-starts** with a warning log — the p90 during collection is robust to residual noise.
- **Sample collection (7 s)**: `CALIBRATION_DURATION_MS = 7 000L`, min `CALIBRATION_MIN_SAMPLES = 20` — otherwise defaults are kept.
- `eyeClosedThreshold = p90(samples) * 0.60` clamped to `[0.15, 0.60]`.
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

### Blink detection specifics
- `BLINK_MIN_CLOSED_FRAMES = 3` — debounce for confirming a closure.
- `BLINK_MAX_DURATION_MS = 500L` — closure beyond this counts as PERCLOS, not a blink.
- `BLINK_MIN_OBSERVATION_MS = 30 000L` — first 30 s don't penalize low blink rate (avoids spurious inflation before enough data is collected).
- Rate deviation: healthy range `[15, 24]` blinks / 60 s; penalty scales linearly to the limits `[8, 32]`. The upper bound was widened from 20 → 24 because users focused on the front-camera app blink 20-26/min naturally (focus, dry eyes, lighting), which the original PERCLOS-literature range interpreted as pathological.

## Session lifecycle & threading

### MonitoringService

- **Foreground service type**: `camera|location`. Started via `ServiceController.startMonitoring(context, calibrationEnabled: Boolean? = null)`. The `null` default reuses the last preference stored in `ServiceController.lastCalibrationEnabled` (initialized to `true`).
- **Bound by `MonitoringScreen`** with `BIND_AUTO_CREATE` while the screen is composed — this is why `onDestroy()` doesn't fire between Start/Stop cycles on that screen. All lifecycle-critical cleanup runs in `stopMonitoring()` explicitly, with `onDestroy()` as a fallback.
- **`currentAssessment: MutableStateFlow<FatigueAssessment?>`** lives on the companion object — the single source of truth consumed by every UI collector.

### `startMonitoring()`
1. Recreate `scorer = FatigueScorer(calibrationEnabled)` from scratch.
2. `startForeground()`, `startLocationUpdates()`, `startSensorUpdates()`.
3. Launch on Main: `acquireWakeLock()` (2 h ceiling, safety only — released on Stop), preload ringtone, `telemetryWriter.startSession()` (guard-rail finalizes any orphaned previous session), set `isProcessRunning = true`, `cameraManager.startCamera(...)`.

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

### Camera & analyzer threading
- `analysisExecutor`: single-threaded `Executors.newSingleThreadExecutor()`.
- MediaPipe init: `Handler(Looper.getMainLooper()).post { ... }` — required by MediaPipe.
- `FaceLandmarker.detect()` runs on the analysis thread; ~10–30 ms per frame — never blocks Main.

## Local telemetry format

Session directory: `context.filesDir/sessions/{sessionId}/`

Files:
- `session.csv` — one row per 2 s window (22 columns): `sessionId,timestamp,score,state,eyeOpenness,blinkRate,isYawning,isFaceDetected,alertActive,latitude,longitude,speed,accelX,accelY,accelZ,gyroX,gyroY,gyroZ,perclos,perclosContribution,blinkContribution,yawnContribution`. The last four columns are FatigueScorer sub-scores exposed for post-hoc diagnosis — they let you decompose the aggregated `score` and calibrate weights from real data. Old CSVs (18 columns from earlier sessions) are still parseable by `SyncRepository.parseCsvLine`; the new columns just come back as null.
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
  - CSV parser validates `p.size >= 9` before indexing and uses `?.toDoubleOrNull()` / `?.toFloatOrNull()` for optional columns [9..17].
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
- **SetupScreen**: CAMERA and location permission launcher (`ActivityResultContracts.RequestMultiplePermissions`). Calibration toggle (persisted through `ServiceController`). Start button enabled iff CAMERA granted. Logout via `AuthViewModel.signOut`.
- **MonitoringScreen**: `AndroidView { PreviewView }` inside `Box`. `ServiceConnection` binds to `MonitoringService` to `attachPreview`/`detachPreview`. Toggle button start/stop calls `viewModel.startMonitoring/stopMonitoring`. Overlays: state pill, radial score gauge, calibration progress bar, positioning warning banner. `MonitoringViewModel` maintains `frameWindow` (rolling 60 s of face-detected flags) to trigger a "reposicione o rosto" warning when > 30 % of frames are NO_FACE.
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
- **FatigueScorerTest** — 13 tests: PERCLOS calc, blink detection, yawn (2 s trigger), NORMAL→WARNING/WARNING→FATIGUED/WARNING→NORMAL hysteresis, reset, calibration stabilization, consecutive-session score integrity, look-away not inflating PERCLOS, look-away preserving buffer, PERCLOS-alone can't promote to FATIGUED.
- **TelemetryWriterTest** — 3 tests: two consecutive sessions both persist summaries, `startSession` guard-rail finalizes unfinished previous session, single session write/stop.
- **SessionRepositoryTest** — 2 tests: sessions sorted by startTime desc, folder path resolution.

Patterns:
- JUnit 4 + `@Rule TemporaryFolder` for file I/O isolation.
- No Mockito — `FatigueMetrics` values are hand-crafted per frame.
- `runBlocking { ... }` inside tests for suspending TelemetryWriter calls.
- Uncovered by unit tests: `MonitoringService`, `MonitoringViewModel`, `SetupViewModel`, `AuthViewModel`, `HistoryViewModel`, `CameraManager`, `FaceAnalyzer`, `AuthRepository`, `SyncRepository`, `SyncWorker`. These are verified by smoke testing on device.

## File organization

```
app/src/main/java/com/vigilia/app/
├── MainActivity.kt                   # Nav host + deep link handling
├── service/
│   ├── MonitoringService.kt          # Foreground service, monitoring loop
│   ├── ServiceController.kt          # start/stop helper + calibration preference
│   └── SyncWorker.kt                 # WorkManager job for Supabase upload
├── camera/
│   ├── CameraManager.kt              # CameraX use case binding
│   └── FaceAnalyzer.kt               # MediaPipe landmarker + EAR + yaw/pitch
├── domain/
│   ├── model/
│   │   └── FatigueModels.kt          # FatigueMetrics, FatigueAssessment, SessionSummary, TelemetryRecord, FatigueState
│   └── scoring/
│       └── FatigueScorer.kt          # Algorithm + FSM
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

- **CameraX** 1.6.1: core, camera2, lifecycle, view
- **MediaPipe** Tasks Vision 0.10.14 (not ML Kit — this doc previously misidentified the library)
- **Compose** BOM 2026.05.00 (Material 3)
- **Navigation Compose** 2.9.8
- **Supabase-kt** 3.1.4 (auth + postgrest + android engine) with Ktor 3.1.2 client
- **WorkManager** 2.10.1
- **Play Services Location** 21.3.0
- **Kotlin Serialization** 2.3.21 (matches Kotlin version)
- **Testing**: JUnit 4.13.2, org.json 20231013 (for parsing session summaries in tests)

## Adjusting fatigue thresholds

All tuning constants are in `FatigueScorer.kt`'s `companion object`. Common tweaks:

- Increase `TRANSITION_NORMAL_TO_WARNING_MS` (currently 2000) to be less trigger-happy on WARNING.
- Increase `LOOK_AWAY_YAW_DEGREES` (currently 25) if drivers legitimately turn further while still monitoring the phone camera.
- Increase `CALIBRATION_STABILIZATION_MS` (currently 1500) if devices with fast MediaPipe init still catch too-early samples.
- Note the score weights sum to 110 — this is intentional (see "Score weights").

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

- **Release minification disabled**: `isMinifyEnabled = false` in the release build type. Supabase publishable key ends up in `BuildConfig` and is readable in the DEX. Not a leak of a secret (the key is public by design) but full minification + ProGuard rules for Supabase-kt/Ktor/Compose is a pending hardening task.
- **Video recording toggle**: UI-only, not wired to any recorder.
- **Single face**: FaceLandmarker configured with `setNumFaces(1)`.
- **compileSdk = 37 vs targetSdk = 34**: intentional — we build against the newest API for future-proofing but target 34 for Play Store compliance.
- **`isLookingAway` not surfaced to UI**: computed in the scorer but not part of `FatigueAssessment`. Adding a "Olhando ao lado" badge on `MonitoringScreen` is a small future improvement.
- **`i18n`**: most user-facing strings are hard-coded in Compose (PT-BR). `strings.xml` is nearly empty. i18n / accessibility is not scaffolded.
- **MonitoringService / camera code lacks unit tests**: covered by smoke testing.

## History of decisions worth remembering

- **Weights are 65/20/25, not 50/30/20** (as an earlier draft of this doc suggested). Total = 110, clamped to 100. This keeps FATIGUED (score > 70) unreachable by any single signal.
- **PERCLOS window is 30 s** — briefly tried 6 s (commit before `9f97457`); it was too sensitive to natural look-away and single 5 s eye-closure episodes. Reverted.
- **WARNING → FATIGUED threshold is 70** — briefly tried 55 (before `9f97457`); PERCLOS = ~0.85 alone would trip it. Reverted to 70 so combination of signals is required.
- **Calibration is capped at 10 s total** — 800 ms stabilization gate with `openness ≥ 0.35` and up to 3 blink-tolerance frames, plus a hard 3 s upper bound that force-starts collection when framing/lighting is chronically bad. Sample collection is 7 s. Originally added as an unbounded 1.5 s / `openness ≥ 0.5` gate (`0faa6a5`) that could stall for a minute or more with natural blinks or glasses; this iteration makes it robust and time-bounded.
- **Camera is torn down on every Stop** (commit `0faa6a5`) — otherwise the second consecutive session on the same screen inherited a hot MediaPipe pipeline with no warmup gap, causing score inflation.
- **`stopMonitoring()` is fully async** (commit `558a30b`) — no `runBlocking` on Main. The `TelemetryWriter.writeMutex` provides the ordering guarantee that used to require `join()`.
- **WakeLock ceiling is 2 h, released on Stop** (commit `558a30b`) — the previous 10 h ceiling drained battery when `onDestroy()` didn't fire between Start/Stop cycles.
- **Head yaw / pitch pause scoring during look-away** (commit `9f97457`) — mirrors, dashboard checks, side glances no longer inflate PERCLOS.
- **Raise the WARNING trigger and widen the healthy blink range**: `TRANSITION_NORMAL_TO_WARNING_SCORE` 40 → 50, `TRANSITION_NORMAL_TO_WARNING_MS` 2 000 → 3 000, `BLINK_RATE_MAX` 20 → 24, `BLINK_DEVIATION_LIMIT_HIGH` 25 → 32. Even after the PERCLOS-70 realignment (`3c30b8a`), users blinking at 25/min (normal when focused on the front camera) were still hitting `blink deviation = 1.0` and landing near the WARNING threshold. The new numbers mean PERCLOS alone needs 77 % closure sustained 3 s, and blink deviation caps out at rates over 30/min — real fatigue signals still trigger, casual focused blinking does not. Recovery threshold (30) and FATIGUED gate (70) unchanged.
- **Seed `perclosWindow` from calibration samples at the end of the collection phase** — otherwise the buffer is empty at the exact moment state flips to NORMAL, and a single natural blink (5 closed frames in a buffer of ~6) inflates PERCLOS to ~80 %, spiking the score right after calibration. `calibrationSamples` was extended to `MutableList<Pair<Long, Float>>` (timestamp + openness) so the samples can be re-evaluated against the newly-computed threshold and pushed into the window. `monitoringStartMs` is also anchored at the start of the collection phase so blink warmup runs in parallel with calibration.
- **PERCLOS-70 realignment + sub-score telemetry**: `EYE_CLOSED_RATIO` dropped from 0.60 to 0.40, `EYE_CLOSED_MAX` from 0.60 to 0.45, `SCORE_WEIGHT_BLINK` from 20 to 15, `TRANSITION_WARNING_TO_NORMAL_SCORE` from 25 to 30. Before this the calibrated eye-closed threshold sat around 0.51, so normally-open frames were counted as closed → PERCLOS baseline was ~30 % (should be ~5 %), score got stuck around 26-30, and recovery from WARNING to NORMAL never converged (25 threshold unreachable). Also added four sub-score columns to `session.csv` (`perclos`, `perclosContribution`, `blinkContribution`, `yawnContribution`) and mirror fields in `FatigueAssessment` / `TelemetryRecord` / `TelemetryRecordDto` so future weight tuning can be data-driven instead of guesswork. Supabase schema needs matching columns before sync will populate them server-side (SQL migration is a manual TODO in that dashboard).
