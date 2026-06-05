package com.vigilia.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** DTO for the Supabase `sessions` table. */
@Serializable
data class SessionSummaryDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("total_alerts") val totalAlerts: Int,
    @SerialName("dominant_state") val dominantState: String,
    @SerialName("average_score") val averageScore: Float,
    @SerialName("peak_score") val peakScore: Float,
)
