package com.vigilia.app.data.repository

import android.content.Context
import android.util.Log
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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
    suspend fun getSessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        if (!baseDir.exists() || !baseDir.isDirectory) {
            return@withContext emptyList<SessionSummary>()
        }

        val summaries = mutableListOf<SessionSummary>()
        baseDir.listFiles()?.forEach { sessionFolder ->
            if (sessionFolder.isDirectory) {
                val summaryFile = File(sessionFolder, "session_summary.json")
                if (summaryFile.exists()) {
                    val json = summaryFile.readText()
                    parseSummaryJson(json)?.let { summaries.add(it) }
                }
            }
        }

        summaries.sortedByDescending { it.startTime }
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

    private fun parseSummaryJson(json: String): SessionSummary? {
        return try {
            val obj = JSONObject(json)
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
        } catch (_: Exception) {
            null
        }
    }
}
