package com.vigilia.app.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.vigilia.app.data.remote.SupabaseClient
import com.vigilia.app.data.remote.dto.SessionSummaryDto
import com.vigilia.app.data.remote.dto.TelemetryRecordDto
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.SessionSummary
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Syncs completed local sessions to the Supabase cloud. */
class SyncRepository(private val context: Context) {

    private val authRepository = AuthRepository()

    /**
     * Uploads a [SessionSummary] to the Supabase `sessions` table.
     * Skips sessions that have already been synced (`.synced` marker present).
     */
    suspend fun syncSession(summary: SessionSummary): Result<Unit> = runCatching {
        val userId = authRepository.currentUserId()
            ?: error("Not logged in")
        val dto = SessionSummaryDto(
            id = summary.sessionId,
            userId = userId,
            startTime = summary.startTime,
            endTime = summary.endTime,
            durationMs = summary.durationMs,
            totalAlerts = summary.totalAlerts,
            dominantState = summary.dominantState.name,
            averageScore = summary.averageScore,
            peakScore = summary.peakScore,
        )
        SupabaseClient.client.from("sessions").upsert(dto)
    }

    /**
     * Uploads all rows from `session.csv` to the Supabase `telemetry_records` table
     * in batches of 100 to avoid request size limits.
     */
    suspend fun syncTelemetry(sessionId: String): Result<Unit> = runCatching {
        val userId = authRepository.currentUserId()
            ?: error("Not logged in")
        val csvFile = File(File(context.filesDir, "sessions"), "$sessionId/session.csv")
        if (!csvFile.exists()) return@runCatching

        val records = withContext(Dispatchers.IO) {
            csvFile.readLines()
                .drop(1) // skip header row
                .filter { it.isNotBlank() }
                .mapNotNull { parseCsvLine(it, sessionId, userId) }
        }

        records.chunked(100).forEach { batch ->
            SupabaseClient.client.from("telemetry_records").upsert(batch)
        }
    }

    /**
     * Scans local sessions and syncs any that have not yet been uploaded.
     * A `.synced` marker file is created in the session folder after a successful upload.
     * Failures are logged per-session and do not interrupt other sessions.
     */
    suspend fun syncPendingSessions() {
        if (!isOnline()) return
        val sessionsDir = File(context.filesDir, "sessions")
        if (!sessionsDir.exists()) return

        sessionsDir.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
            if (File(folder, ".synced").exists()) return@forEach

            try {
                val summary = parseSummaryJson(folder) ?: return@forEach
                syncSession(summary).getOrThrow()
                syncTelemetry(summary.sessionId).getOrThrow()
                File(folder, ".synced").createNewFile()
                Log.i("SyncRepository", "Synced session ${summary.sessionId}")
            } catch (e: Exception) {
                Log.w("SyncRepository", "Failed to sync session ${folder.name}, will retry", e)
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // CSV column order (from TelemetryWriter):
    // sessionId[0], timestamp[1], score[2], state[3], eyeOpenness[4],
    // blinkRate[5], isYawning[6], isFaceDetected[7], alertActive[8]
    private fun parseCsvLine(line: String, sessionId: String, userId: String): TelemetryRecordDto? {
        return try {
            val p = line.split(",")
            if (p.size < 9) {
                Log.w("SyncRepository", "Skipping malformed CSV line (${p.size} fields): $line")
                return null
            }
            TelemetryRecordDto(
                sessionId = sessionId,
                userId = userId,
                timestamp = p[1].toLong(),
                score = p[2].toFloat(),
                state = p[3],
                eyeOpenness = p[4].toFloat(),
                blinkRate = p[5].toFloat(),
                isYawning = p[6].toBoolean(),
                isFaceDetected = p[7].toBoolean(),
                alertActive = p[8].toBoolean(),
            )
        } catch (_: Exception) { null }
    }

    private fun parseSummaryJson(folder: File): SessionSummary? {
        return try {
            val obj = JSONObject(File(folder, "session_summary.json").readText())
            val dominantState = try {
                FatigueState.valueOf(obj.getString("dominantState"))
            } catch (_: Exception) {
                FatigueState.NORMAL
            }
            SessionSummary(
                sessionId = obj.getString("sessionId"),
                startTime = obj.getLong("startTime"),
                endTime = obj.getLong("endTime"),
                durationMs = obj.getLong("durationMs"),
                totalAlerts = obj.getInt("totalAlerts"),
                dominantState = dominantState,
                averageScore = obj.getDouble("averageScore").toFloat(),
                peakScore = obj.getDouble("peakScore").toFloat(),
            )
        } catch (_: Exception) { null }
    }
}
