package com.vigilia.app.data.telemetry

import android.content.Context
import android.util.Log
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.SessionSummary
import com.vigilia.app.domain.model.TelemetryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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

    private val writeMutex = Mutex()

    private var currentSessionId: String? = null
    private var sessionFolder: File? = null
    private var csvFile: File? = null

    // Session metrics tracked in memory
    private var startTimeMillis: Long = 0
    // AtomicInteger so recordAlert() can be called from any thread (frame callback lives
    // on the analysis executor, not on Main). No mutex needed for a monotonic counter.
    private val totalAlerts = AtomicInteger(0)
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
        // Guard-rail: previous session was not finalized (missed stopSession, crash, or a
        // service instance being reused across sessions). Write its summary now so the
        // orphan folder still shows up in history instead of being silently discarded.
        if (currentSessionId != null) {
            Log.w("TelemetryWriter", "Previous session $currentSessionId was not stopped; finalizing it before starting a new one")
            try {
                stopSession()
            } catch (e: Exception) {
                Log.e("TelemetryWriter", "Failed to finalize previous session", e)
            }
        }

        val sessionId = UUID.randomUUID().toString()
        val folder = File(baseDir, sessionId)
        if (!folder.exists()) {
            folder.mkdirs()
        }

        val file = File(folder, "session.csv")
        // Columns 0-17: core telemetry. Columns 18-21: FatigueScorer sub-scores (diagnostic).
        // Columns 22-24: lighting context (ambient lux, mean frame Y, lighting mode).
        // Columns 25-26: head orientation in degrees (yaw, pitch) — persisted for post-hoc
        // disambiguation of "head down vs. drowsy" cases.
        file.writeText("sessionId,timestamp,score,state,eyeOpenness,blinkRate,isYawning,isFaceDetected,alertActive,latitude,longitude,speed,accelX,accelY,accelZ,gyroX,gyroY,gyroZ,perclos,perclosContribution,blinkContribution,yawnContribution,ambientLightLux,frameLuminance,lightingMode,headYawDegrees,headPitchDegrees\n")

        currentSessionId = sessionId
        sessionFolder = folder
        csvFile = file

        // Reset metrics
        startTimeMillis = System.currentTimeMillis()
        totalAlerts.set(0)
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
        writeMutex.withLock {
            val file = csvFile ?: return@withLock
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
                    append(record.alertActive).append(",")
                    append(record.latitude?.toString() ?: "").append(",")
                    append(record.longitude?.toString() ?: "").append(",")
                    append(record.speed?.toString() ?: "").append(",")
                    append(record.accelX?.toString() ?: "").append(",")
                    append(record.accelY?.toString() ?: "").append(",")
                    append(record.accelZ?.toString() ?: "").append(",")
                    append(record.gyroX?.toString() ?: "").append(",")
                    append(record.gyroY?.toString() ?: "").append(",")
                    append(record.gyroZ?.toString() ?: "").append(",")
                    append(record.perclos).append(",")
                    append(record.perclosContribution).append(",")
                    append(record.blinkContribution).append(",")
                    append(record.yawnContribution).append(",")
                    append(record.ambientLightLux?.toString() ?: "").append(",")
                    append(record.frameLuminance).append(",")
                    append(record.lightingMode).append(",")
                    append(record.headYawDegrees?.toString() ?: "").append(",")
                    append(record.headPitchDegrees?.toString() ?: "").append("\n")
                }

                FileWriter(file, true).use { writer ->
                    writer.append(row)
                }

                // Update metrics for summary. `totalAlerts` is NOT touched here — see
                // `recordAlert()`. The old logic incremented when `record.alertActive` was
                // true, but that field is only true during the ~550ms the alarm tone plays,
                // so it undercounted alerts by roughly (2000 - 550) / 2000 = 72.5 % on
                // average.
                scoreSum += record.score
                recordCount++
                if (record.score > peakScore) {
                    peakScore = record.score
                }
                stateCounts[record.state] = stateCounts.getOrDefault(record.state, 0) + 1

            } catch (e: Exception) {
                Log.e(
                    "TelemetryWriter",
                    "writeRecord failed (session=$currentSessionId, row #${recordCount + 1}, state=${record.state}): ${e.message}",
                    e,
                )
            }
        }
    }

    /** Number of rows persisted so far in the active session. Read from any thread. */
    fun currentRecordCount(): Long = recordCount

    /**
     * Register that an alert was fired. Called from MonitoringService.triggerAlert() on
     * every trigger — one call per audible alarm. Idempotent no-op when no session is
     * active. Prefer this over relying on `TelemetryRecord.alertActive` for counting
     * because that field only reflects whether the tone was playing at the exact instant
     * a 2s telemetry row was written — a heavy sampling bias against short alerts.
     */
    fun recordAlert() {
        if (currentSessionId != null) totalAlerts.incrementAndGet()
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
        val (durationMs, avgScore, dominantState) = writeMutex.withLock {
            val duration = if (startTimeMillis > 0) endTimeMillis - startTimeMillis else 0L
            val avg = if (recordCount > 0) (scoreSum / recordCount).toFloat() else 0f
            val dominant = stateCounts.maxByOrNull { it.value }?.key ?: FatigueState.NORMAL
            Triple(duration, avg, dominant)
        }

        val summary = SessionSummary(
            sessionId = sessionId,
            startTime = startTimeMillis,
            endTime = endTimeMillis,
            durationMs = durationMs,
            totalAlerts = totalAlerts.get(),
            dominantState = dominantState,
            averageScore = avgScore,
            peakScore = peakScore
        )

        // Write session_summary.json using manual JSON building
        val summaryFile = File(folder, "session_summary.json")
        val json = buildSummaryJson(summary)
        summaryFile.writeText(json)

        // Health check: compare expected row count vs actual. Telemetry writes every 2s, so
        // expected ≈ durationMs / 2000. Anything below 90% signals a silent write failure
        // and warrants investigating logcat for the culprit.
        val expectedRows = durationMs / 2000L
        val healthPct = if (expectedRows > 0) (100.0 * recordCount / expectedRows) else 0.0
        Log.i(
            "TelemetryWriter",
            "Session $sessionId finished: duration=${durationMs}ms, expected≈$expectedRows rows, wrote $recordCount (${"%.1f".format(healthPct)}%)",
        )
        if (recordCount > 0 && expectedRows > 0 && healthPct < 90.0) {
            Log.w(
                "TelemetryWriter",
                "Session $sessionId had significant telemetry loss — expected $expectedRows rows, only $recordCount persisted",
            )
        }

        // Reset session state
        currentSessionId = null
        sessionFolder = null
        csvFile = null

        summary
    }

    private fun buildSummaryJson(s: SessionSummary): String {
        return JSONObject().apply {
            put("sessionId", s.sessionId)
            put("startTime", s.startTime)
            put("endTime", s.endTime)
            put("durationMs", s.durationMs)
            put("totalAlerts", s.totalAlerts)
            put("dominantState", s.dominantState.name)
            put("averageScore", s.averageScore.toDouble())
            put("peakScore", s.peakScore.toDouble())
        }.toString(2)
    }
}
