package com.vigilia.app.domain.model

enum class FatigueState { NORMAL, WARNING, FATIGUED, NO_FACE, CALIBRATING }

data class FatigueMetrics(
    val leftEyeOpenProbability: Float,
    val rightEyeOpenProbability: Float,
    val mouthOpenProbability: Float,
    val isFaceDetected: Boolean,
    val timestampMs: Long,
    // Head orientation in degrees (0 = frontal). Positive/negative sign is defined by the
    // producer (FaceAnalyzer) — the scorer only uses absolute magnitudes and directional
    // thresholds. Defaults to 0 so tests and callers that don't measure orientation
    // continue to behave as before (treated as looking straight at the camera).
    val headYawDegrees: Float = 0f,
    val headPitchDegrees: Float = 0f,
)

data class FatigueAssessment(
    val score: Float,
    val fatigueState: FatigueState,
    val blinkRate: Float,
    val isYawning: Boolean,
    val isFaceDetected: Boolean,
    val timestampMs: Long,
    val calibrationProgress: Float = 0f,
)

data class SessionSummary(
    val sessionId: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val totalAlerts: Int,
    val dominantState: FatigueState,
    val averageScore: Float,
    val peakScore: Float
)

data class TelemetryRecord(
    val sessionId: String,
    val timestamp: Long,
    val score: Float,
    val state: FatigueState,
    val eyeOpenness: Float,
    val blinkRate: Float,
    val isYawning: Boolean,
    val isFaceDetected: Boolean,
    val alertActive: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speed: Float? = null,
    val accelX: Float? = null,
    val accelY: Float? = null,
    val accelZ: Float? = null,
    val gyroX: Float? = null,
    val gyroY: Float? = null,
    val gyroZ: Float? = null,
)
