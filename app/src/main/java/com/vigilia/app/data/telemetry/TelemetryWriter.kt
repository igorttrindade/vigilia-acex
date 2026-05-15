package com.vigilia.app.data.telemetry

import android.content.Context
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.SessionSummary
import com.vigilia.app.domain.model.TelemetryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.util.UUID

/**
 * Responsible for persisting session data to local storage.
 *
 * This class manages telemetry sessions by creating unique folders, recording
 * individual frames in CSV format, and generating a session summary in JSON format.
 * All file operations are executed on [Dispatchers.IO].
 */
class TelemetryWriter private constructor(
    @Suppress("unused") private val context: Context?,
    private val baseDir: File,
) {

    /**
     * Primary constructor required by the app infrastructure.
     */
    @Suppress("unused")
    constructor(context: Context) : this(context, File(context.filesDir, "sessions"))

    /**
     * Internal constructor for testing without a full Android Context.
     */
    internal constructor(baseDir: File) : this(null, baseDir)

    private var currentSessionId: String? = null
    private var sessionFolder: File? = null
    private var csvFile: File? = null

    // Session metrics tracked in memory
    private var startTimeMillis: Long = 0
    private var totalAlerts: Int = 0
    private var scoreSum: Double = 0.0
    private var recordCount: Long = 0
    private var peakScore: Float = 0f
    private val stateCounts = mutableMapOf<FatigueState, Int>()

    /**
     * Starts a new telemetry session.
     * Generates a unique sessionId and creates a dedicated folder for the session.
     * Initializes the CSV file with a header row.
     *
     * @return The unique sessionId.
     */
    suspend fun startSession(): String = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val folder = File(baseDir, sessionId)
        if (!folder.exists()) {
            folder.mkdirs()
        }

        val file = File(folder, "session.csv")
        file.writeText("sessionId,timestamp,score,state,eyeOpenness,blinkRate,isYawning,isFaceDetected,alertActive\n")

        currentSessionId = sessionId
        sessionFolder = folder
        csvFile = file

        // Reset metrics
        startTimeMillis = System.currentTimeMillis()
        totalAlerts = 0
        scoreSum = 0.0
        recordCount = 0
        peakScore = 0f
        stateCounts.clear()

        sessionId
    }

    /**
     * Appends a [TelemetryRecord] to the current session's CSV file and updates metrics.
     * Fails gracefully on IOException by logging the error.
     *
     * @param record The telemetry record to persist.
     */
    suspend fun writeRecord(record: TelemetryRecord) = withContext(Dispatchers.IO) {
        val file = csvFile ?: return@withContext

        try {
            val row = buildString {
                append(record.sessionId).append(",")
                append(record.timestamp).append(",")
                append(record.score).append(",")
                append(record.state.name).append(",")
                append(record.eyeOpenness).append(",")
                append(record.blinkRate).append(",")
                append(record.isYawning).append(",")
                append(record.isFaceDetected).append(",")
                append(record.alertActive).append("\n")
            }

            FileWriter(file, true).use { writer ->
                writer.append(row)
            }

            // Update metrics for summary
            if (record.alertActive) {
                totalAlerts++
            }
            scoreSum += record.score
            recordCount++
            if (record.score > peakScore) {
                peakScore = record.score
            }
            stateCounts[record.state] = stateCounts.getOrDefault(record.state, 0) + 1

        } catch (_: Exception) {
            // Log error and continue to avoid crashing the session
        }
    }

    /**
     * Stops the session, calculates summary metrics, and writes session_summary.json.
     *
     * @return The [SessionSummary] for the completed session.
     */
    suspend fun stopSession(): SessionSummary = withContext(Dispatchers.IO) {
        val sessionId = currentSessionId ?: throw IllegalStateException("No active session to stop")
        val folder = sessionFolder ?: throw IllegalStateException("Session folder is missing")

        val endTimeMillis = System.currentTimeMillis()
        val durationMs = if (startTimeMillis > 0) endTimeMillis - startTimeMillis else 0L
        val avgScore = if (recordCount > 0) (scoreSum / recordCount).toFloat() else 0f
        val dominantState = stateCounts.maxByOrNull { it.value }?.key ?: FatigueState.NORMAL

        val summary = SessionSummary(
            sessionId = sessionId,
            startTime = startTimeMillis,
            endTime = endTimeMillis,
            durationMs = durationMs,
            totalAlerts = totalAlerts,
            dominantState = dominantState,
            averageScore = avgScore,
            peakScore = peakScore
        )

        // Write session_summary.json using manual JSON building
        val summaryFile = File(folder, "session_summary.json")
        val json = buildSummaryJson(summary)
        summaryFile.writeText(json)

        // Reset session state
        currentSessionId = null
        sessionFolder = null
        csvFile = null

        summary
    }

    private fun buildSummaryJson(s: SessionSummary): String {
        return """
            {
              "sessionId": "${s.sessionId}",
              "startTime": ${s.startTime},
              "endTime": ${s.endTime},
              "durationMs": ${s.durationMs},
              "totalAlerts": ${s.totalAlerts},
              "dominantState": "${s.dominantState.name}",
              "averageScore": ${s.averageScore},
              "peakScore": ${s.peakScore}
            }
        """.trimIndent()
    }
}
