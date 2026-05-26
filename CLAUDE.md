# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Vigília** is an Android fatigue monitoring application that uses real-time face detection via ML Kit to assess driver/operator fatigue levels. The app monitors eye openness (PERCLOS), blink rate, and yawn detection to calculate a fatigue score (0-100) and trigger alerts when the user appears dangerously fatigued.

**Target SDK**: 34 | **Min SDK**: 26 | **Kotlin**: 2.2.10 | **Gradle**: 9.4.1 | **Compose**: Latest Material 3

## Build Commands

All commands should be run from the project root.

### Build
```bash
# Debug build
./gradlew build

# Release build
./gradlew assembleRelease

# Clean build
./gradlew clean build
```

### Tests
```bash
# Run all unit tests
./gradlew test

# Run a single test file
./gradlew test --tests com.vigilia.app.domain.scoring.FatigueScorerTest

# Run a specific test method
./gradlew test --tests com.vigilia.app.domain.scoring.FatigueScorerTest.processFrame*

# Run instrumented tests (requires emulator/device)
./gradlew connectedAndroidTest

# Test coverage report
./gradlew testDebugUnitTest --tests '*'
```

### Development
```bash
# Install debug APK to connected device/emulator
./gradlew installDebug

# Run with logging
./gradlew build -d  # Full debug output

# Check for lint issues
./gradlew lint
```

## Architecture Overview

### Layered Architecture

The app follows a Domain-Driven Design with clear separation:

```
UI Layer (Compose)
├── SetupScreen → SetupViewModel → ServiceController
├── MonitoringScreen → MonitoringViewModel → MonitoringService
└── HistoryScreen → HistoryViewModel → SessionRepository

Service Layer
└── MonitoringService (Foreground Service)
    ├── CameraManager + CameraX
    ├── FaceAnalyzer (ML Kit)
    └── TelemetryWriter

Domain Layer (Core Logic)
├── FatigueScorer (State machine + metrics calculation)
├── FatigueMetrics / FatigueAssessment (Models)
└── SessionSummary / TelemetryRecord (Models)

Data Layer
├── SessionRepository (Read sessions from disk)
└── TelemetryWriter (Write sessions to disk)
```

### Data Flow: Real-Time Fatigue Monitoring

1. **Camera Capture**: CameraX (front-facing) → ImageAnalysis @ 640x480
2. **Face Detection**: FaceAnalyzer uses ML Kit to extract eye/mouth probabilities per frame
3. **Frame Metrics**: Produces `FatigueMetrics` (leftEyeOpenness, rightEyeOpenness, mouthOpenness)
4. **Scoring**: FatigueScorer processes metrics through:
   - **PERCLOS** (Percentage of Eye Closure): 30-second rolling window → primary fatigue signal
   - **Blink Detection**: 60-second rolling window → detects abnormally low/high rates
   - **Yawn Detection**: Sustained mouth opening (≥2s) → secondary fatigue signal
   - **Score Calculation**: Weighted sum (50% PERCLOS, 30% blink deviation, 20% yawn) → 0-100 scale
5. **State Machine**: Exponential smoothing + hysteresis transitions between NORMAL → WARNING → FATIGUED
6. **Alerts**: Audio alert triggered on WARNING/FATIGUED states
7. **Telemetry**: Records written to CSV every 2 seconds; session summary (JSON) generated on stop

### Key Components

#### **MonitoringService** (com.vigilia.app.service)
- **Lifecycle**: Foreground Service (camera permission type)
- **Responsibilities**:
  - Bind CameraManager, FatigueScorer, TelemetryWriter
  - Emit real-time assessments via `MutableStateFlow<FatigueAssessment>`
  - Manage WakeLock (prevents device sleep during monitoring)
  - Play/stop audio alerts (Ringtone API)
  - Update notification with current state
  - Write telemetry records on 2-second intervals
- **Key Methods**:
  - `startMonitoring()`: Initializes session, starts camera, sets lifecycle to RESUMED
  - `stopMonitoring()`: Stops camera, stops session, releases resources
  - `attachPreview(SurfaceProvider)`: Binds preview for UI display
  - `handleAssessment(assessment)`: Core loop that processes scores, triggers alerts, writes telemetry

#### **CameraManager** (com.vigilia.app.camera)
- Manages CameraX lifecycle binding to a LifecycleOwner
- Splits Analysis (always running) and Preview (optional, for UI feedback)
- Analyzer runs on a single-threaded executor with KEEP_ONLY_LATEST backpressure

#### **FaceAnalyzer** (com.vigilia.app.camera)
- ImageAnalysis.Analyzer implementation
- Extracts eye openness and mouth probability (uses smiling as proxy) from ML Kit
- Non-blocking: emits `FatigueMetrics` via callback for every frame

#### **FatigueScorer** (com.vigilia.app.domain.scoring)
- **State Machine States**: NORMAL, WARNING, FATIGUED, NO_FACE
- **Hysteresis Transitions** (require sustained thresholds + time):
  - NORMAL → WARNING: score > 40 for 3s
  - WARNING → FATIGUED: score > 70 for 5s
  - WARNING → NORMAL: score < 25 for 10s
  - FATIGUED → WARNING: score < 50 for 10s
- **Internal Windows**: ArrayDeques for PERCLOS (30s) and blinks (60s)
- **Reset Method**: Clears all buffers and state (for testing)

#### **TelemetryWriter** (com.vigilia.app.data.telemetry)
- Persists monitoring sessions to local storage (`context.filesDir/sessions/{sessionId}/`)
- **Files Generated**:
  - `session.csv`: Frame-by-frame telemetry (timestamp, score, state, etc.)
  - `session_summary.json`: Aggregated session metrics
- **Thread-Safe**: All I/O on Dispatchers.IO
- **Metrics Tracked**: totalAlerts, peakScore, averageScore, stateCounts (per state)

#### **SessionRepository** (com.vigilia.app.data.repository)
- Reads and parses session summaries from disk
- Manual JSON parsing (no external JSON library)
- Returns sessions sorted by startTime (newest first)
- Used by HistoryViewModel for session listing

### UI Components (Compose)

#### **SetupScreen**
- Permission request launcher (CAMERA, ACCESS_COARSE_LOCATION)
- Toggles: Calibration, Video Recording (UI-only, not implemented)
- Start Monitoring button (enabled only if CAMERA permission granted)
- Navigates to MonitoringScreen on button click

#### **MonitoringScreen**
- Real-Time Display:
  - Camera preview (PreviewView via AndroidView)
  - State pill (color-coded: NORMAL/WARNING/FATIGUED/NO_FACE)
  - Score indicator (radial progress gauge, 0-100)
  - Elapsed time counter
  - Alert count
  - Metric indicators: Eyes, Blink, Yawn, Face detection
- ServiceConnection: Binds to MonitoringService to attach/detach preview
- Reactive Preview: Attaches surface provider only when service is ready and monitoring is active

#### **HistoryScreen**
- Lazy list of past sessions (sorted newest first)
- Per-session: date, duration, dominant fatigue state, alert count, average score
- Export button: Uses FileProvider + Intent.ACTION_SEND_MULTIPLE to share CSV + JSON

### Navigation Structure

```
VigiliaNavGraph (bottom bar navigation)
├── Setup Route: SetupScreen
│   └── Callback: onMonitoringStarted → Navigate to Monitoring
├── Monitoring Route: MonitoringScreen
│   └── Real-time display of active session
└── History Route: HistoryScreen
    └── Past sessions list + export
```

**Active Monitoring Banner**: Displayed when `MonitoringService.currentAssessment` is non-null (any screen)

## State Management

- **MonitoringService**: Single source of truth via `currentAssessment` StateFlow (shared across UI)
- **ViewModels**: Lightweight state holders (SetupViewModel, MonitoringViewModel, HistoryViewModel)
  - Use AndroidViewModel for Application context
  - Observe MonitoringService.currentAssessment for real-time updates
  - All I/O on Dispatchers.IO or viewModelScope

## Testing

### Unit Tests

- **FatigueScorerTest**: Validates PERCLOS calculation, blink detection, yawn detection, state transitions with hysteresis, reset behavior
- **TelemetryWriterTest**: CSV/JSON file generation, session start/stop, metrics aggregation
- **SessionRepositoryTest**: Disk reads, JSON parsing, session sorting

### Key Testing Patterns
- Use JUnit 4 + TemporaryFolder for file I/O isolation
- Mock metrics manually (no Mockito—keep dependencies light)
- Test state transitions with sustained score inputs over time
- All telemetry tests use coroutines (`runBlocking`)

## File Organization

```
app/src/main/java/com/vigilia/app/
├── MainActivity.kt                    # Entry point, navigation setup
├── service/
│   ├── MonitoringService.kt          # Foreground Service, core monitoring loop
│   └── ServiceController.kt          # Helper to start/stop service
├── camera/
│   ├── CameraManager.kt              # CameraX lifecycle & binding
│   └── FaceAnalyzer.kt               # ML Kit face detection → metrics
├── domain/
│   ├── model/
│   │   └── FatigueModels.kt          # Data classes (FatigueMetrics, Assessment, SessionSummary)
│   └── scoring/
│       └── FatigueScorer.kt          # Fatigue algorithm (PERCLOS, blinks, yawns, state machine)
├── data/
│   ├── telemetry/
│   │   └── TelemetryWriter.kt        # Session persistence (CSV + JSON)
│   └── repository/
│       └── SessionRepository.kt      # Read sessions from disk
└── ui/
    ├── navigation/
    │   └── VigiliaNavGraph.kt        # Bottom bar + route definitions
    ├── setup/
    │   ├── SetupScreen.kt
    │   └── SetupViewModel.kt
    ├── monitoring/
    │   ├── MonitoringScreen.kt
    │   └── MonitoringViewModel.kt
    ├── history/
    │   ├── HistoryScreen.kt
    │   └── HistoryViewModel.kt
    └── theme/
        ├── Theme.kt
        ├── Color.kt
        └── Type.kt
```

## Dependencies Overview

- **AndroidX**: Core, Lifecycle, Activity Compose, Navigation Compose
- **Compose**: Material 3, UI, Graphics, Icons Extended
- **CameraX**: Core, Camera2 backend, Lifecycle integration, PreviewView
- **ML Kit**: Face Detection (real-time, performance mode, 15% min face size)
- **Testing**: JUnit 4, AndroidX Test (Espresso, Instrumented)

See `gradle/libs.versions.toml` for pinned versions.

## Useful References

### Adjust Fatigue Thresholds
Edit constants in `FatigueScorer` companion object: `TRANSITION_NORMAL_TO_WARNING_SCORE/MS`, `TRANSITION_WARNING_TO_FATIGUED_SCORE/MS`, `PERCLOS_WINDOW_MS`, `BLINK_WINDOW_MS`, `YAWN_THRESHOLD_PROB`.

### Debug Telemetry
Session files stored in `/data/data/com.vigilia.app/files/sessions/{sessionId}/`. Pull via:
```bash
adb pull /data/data/com.vigilia.app/files/sessions/
```

## Permissions & Manifest

- **CAMERA**: Required for face detection
- **ACCESS_FINE_LOCATION** / **ACCESS_COARSE_LOCATION**: Requested (not critical for core function)
- **FOREGROUND_SERVICE** & **FOREGROUND_SERVICE_CAMERA**: Required for persistent monitoring
- **WAKE_LOCK**: Prevents device sleep during sessions
- **POST_NOTIFICATIONS**: For alert notifications (Android 13+)

## Performance Considerations

- **CameraX Resolution**: Fixed at 640x480 (balance quality/performance)
- **Image Analysis Backpressure**: KEEP_ONLY_LATEST (drops frames if UI thread lags)
- **Telemetry Interval**: 2 seconds (reduces I/O vs. per-frame writes)
- **Blink/PERCLOS Windows**: Fixed sizes (30s/60s) → O(1) memory per session
- **Exponential Smoothing**: Score smoothed with α=0.3 to reduce jitter
- **ML Kit**: PERFORMANCE_MODE_FAST (lower accuracy, higher speed)

## Known Limitations & TODOs

- **Mouth Open Proxy**: Currently uses smiling probability (not true mouth opening)—ML Kit doesn't provide native mouth-open detection
- **Calibration Toggle**: UI-only; not implemented in service
- **Video Recording**: UI-only; not implemented
- **Single Face**: Always processes first face detected; no multi-face support
- **No Persistence of Settings**: Setup toggles are not saved across app restarts

