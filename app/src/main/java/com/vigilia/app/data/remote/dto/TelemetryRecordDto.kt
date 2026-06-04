package com.vigilia.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** DTO for the Supabase `telemetry_records` table. */
@Serializable
data class TelemetryRecordDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("score") val score: Float,
    @SerialName("state") val state: String,
    @SerialName("eye_openness") val eyeOpenness: Float,
    @SerialName("blink_rate") val blinkRate: Float,
    @SerialName("is_yawning") val isYawning: Boolean,
    @SerialName("is_face_detected") val isFaceDetected: Boolean,
    @SerialName("alert_active") val alertActive: Boolean,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
)
