package com.vigilia.app.data.repository

import android.content.Context
import android.util.Log
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One row of the session CSV projected to just what the detail chart needs.
 * `alertActive` samples the CSV column directly — it's true only during the ~550 ms the
 * ringtone was playing, so treat it as "at least one alarm around this bucket", not as a
 * ground-truth of every alarm.
 */
data class SessionTelemetryPoint(
    val timestamp: Long,
    val score: Float,
    val state: FatigueState,
    val alertActive: Boolean,
)

/**
 * Repository responsible for reading and managing saved sessions from local storage.
 *
 * It scans the application's session directory and retrieves session summaries
 * for the history screen. All operations are executed on [Dispatchers.IO].
 */
class SessionRepository private constructor(
    @Suppress("unused") private val context: Context?,
    private val baseDir: File
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

    /**
     * Scans the sessions directory and retrieves all valid session summaries.
     * Sessions are returned sorted by startTime in descending order (newest first).
     * Malformed or missing summary files are skipped.
     *
     * @return A list of [SessionSummary] objects.
     */
    suspend fun getSessions(userId: String): List<SessionSummary> = withContext(Dispatchers.IO) {
        if (!baseDir.exists() || !baseDir.isDirectory) {
            return@withContext emptyList<SessionSummary>()
        }

        val summaries = mutableListOf<SessionSummary>()
        baseDir.listFiles()?.forEach { sessionFolder ->
            if (!sessionFolder.isDirectory) return@forEach
            val summaryFile = File(sessionFolder, "session_summary.json")
            if (!summaryFile.exists()) return@forEach
            // Guard against unreadable files (corrupted UTF-8, truncated after a process
            // kill mid-write, permission denied). Without this, a single bad folder crashes
            // the entire HistoryScreen load.
            try {
                parseSummaryJson(summaryFile.readText())
                    // Sessions without userId were recorded before this fix — include them
                    // only for the currently logged-in user so they're visible once more
                    // before being cleared on the next logout.
                    ?.takeIf { it.userId == null || it.userId == userId }
                    ?.let { summaries.add(it) }
            } catch (e: Exception) {
                Log.w("SessionRepository", "Skipping unreadable session ${sessionFolder.name}", e)
            }
        }

        summaries.sortedByDescending { it.startTime }
    }

    suspend fun clearSessions() = withContext(Dispatchers.IO) {
        baseDir.listFiles()?.forEach { it.deleteRecursively() }
        Log.d("SessionRepository", "All local sessions cleared")
    }

    suspend fun deleteOldSessions(daysToKeep: Int = 60) = withContext(Dispatchers.IO) {
        if (!baseDir.exists() || !baseDir.isDirectory) return@withContext

        val cutoff = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        val folders = baseDir.listFiles()?.filter { it.isDirectory } ?: return@withContext

        var deleted = 0
        for (folder in folders) {
            try {
                val summaryFile = File(folder, "session_summary.json")
                if (!summaryFile.exists()) {
                    // Orphaned folder from a crashed session — clean up if older than 1 day
                    val lastModified = File(folder, "session.csv").takeIf { it.exists() }?.lastModified()
                        ?: folder.lastModified()
                    if (System.currentTimeMillis() - lastModified > 24 * 60 * 60 * 1000L) {
                        folder.deleteRecursively()
                        deleted++
                    }
                    continue
                }
                val startTime = parseSummaryJson(summaryFile.readText())?.startTime ?: continue
                if (startTime < cutoff) {
                    folder.deleteRecursively()
                    deleted++
                }
            } catch (e: Exception) {
                Log.w("SessionRepository", "Auto-cleanup: skipping ${folder.name}", e)
            }
        }

        Log.d("SessionRepository", "Auto-cleanup: deleted $deleted of ${folders.size} sessions older than $daysToKeep days")
    }

    /**
     * Provides a [File] reference to a session directory.
     * Used by ExportManager to locate files for sharing or processing.
     *
     * @param sessionId The unique ID of the session.
     * @return The [File] object representing the session folder.
     */
    fun getSessionFolder(sessionId: String): File {
        return File(baseDir, sessionId)
    }

    /**
     * Reads the CSV of a session and returns a lightweight timeline suitable for the
     * detail chart. Skips malformed lines. Absent files → empty list.
     *
     * Downsamples to at most [maxPoints] using bucketed averaging (preserves alerts and
     * peak scores within each bucket) so long sessions don't hurt Canvas rendering.
     */
    suspend fun getSessionTelemetry(
        sessionId: String,
        maxPoints: Int = 600,
    ): List<SessionTelemetryPoint> = withContext(Dispatchers.IO) {
        val csv = File(baseDir, "$sessionId/session.csv")
        if (!csv.exists()) return@withContext emptyList<SessionTelemetryPoint>()

        val raw = try {
            csv.useLines { lines ->
                lines.drop(1)
                    .filter { it.isNotBlank() }
                    .mapNotNull { parseTelemetryLine(it) }
                    .toList()
            }
        } catch (e: Exception) {
            Log.w("SessionRepository", "Failed to read telemetry for $sessionId", e)
            return@withContext emptyList<SessionTelemetryPoint>()
        }

        if (raw.size <= maxPoints) raw else downsample(raw, maxPoints)
    }

    private fun parseTelemetryLine(line: String): SessionTelemetryPoint? {
        return try {
            val p = line.split(",")
            if (p.size < 9) return null
            val state = try {
                FatigueState.valueOf(p[3])
            } catch (_: Exception) {
                FatigueState.NORMAL
            }
            SessionTelemetryPoint(
                timestamp = p[1].toLong(),
                score = p[2].toFloat(),
                state = state,
                alertActive = p[8].toBoolean(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Bucketed downsampling that preserves the peak score and any alert within each bucket,
     * so the resulting curve still shows spikes and every alarm dot.
     */
    private fun downsample(points: List<SessionTelemetryPoint>, target: Int): List<SessionTelemetryPoint> {
        val bucketSize = (points.size + target - 1) / target
        val out = ArrayList<SessionTelemetryPoint>(target)
        var i = 0
        while (i < points.size) {
            val end = minOf(i + bucketSize, points.size)
            var peak = points[i]
            var anyAlert = false
            for (j in i until end) {
                val q = points[j]
                if (q.score > peak.score) peak = q
                if (q.alertActive) anyAlert = true
            }
            out.add(peak.copy(alertActive = anyAlert))
            i = end
        }
        return out
    }

    private fun parseSummaryJson(json: String): SessionSummary? {
        return try {
            val obj = JSONObject(json)
            val dominantState = try {
                FatigueState.valueOf(obj.getString("dominantState"))
            } catch (_: Exception) {
                FatigueState.NORMAL
            }
            val alertTimestamps = try {
                val arr: JSONArray? = obj.optJSONArray("alertTimestamps")
                if (arr != null) (0 until arr.length()).map { arr.getLong(it) } else emptyList()
            } catch (_: Exception) { emptyList() }
            val driverRating = obj.optInt("driverRating", -1).takeIf { it in 1..5 }
            val driverComment = obj.optString("driverComment", "").takeIf { it.isNotBlank() }
            SessionSummary(
                sessionId = obj.getString("sessionId"),
                userId = obj.optString("userId").ifBlank { null },
                startTime = obj.getLong("startTime"),
                endTime = obj.getLong("endTime"),
                durationMs = obj.getLong("durationMs"),
                totalAlerts = obj.getInt("totalAlerts"),
                dominantState = dominantState,
                averageScore = obj.getDouble("averageScore").toFloat(),
                peakScore = obj.getDouble("peakScore").toFloat(),
                alertTimestamps = alertTimestamps,
                driverRating = driverRating,
                driverComment = driverComment,
            )
        } catch (_: Exception) {
            null
        }
    }
}
