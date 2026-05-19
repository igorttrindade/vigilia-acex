package com.vigilia.app.data.repository

import android.content.Context
import android.util.Log
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                if (!summaryFile.exists()) continue
                val startTime = extractLong(summaryFile.readText(), "startTime") ?: continue
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
     * Manually parses a session summary JSON string into a [SessionSummary] object.
     * This avoids adding external JSON library dependencies.
     */
    private fun parseSummaryJson(json: String): SessionSummary? {
        return try {
            val sessionId = extractString(json, "sessionId") ?: return null
            val startTime = extractLong(json, "startTime") ?: 0L
            val endTime = extractLong(json, "endTime") ?: 0L
            val durationMs = extractLong(json, "durationMs") ?: 0L
            val totalAlerts = extractInt(json, "totalAlerts") ?: 0
            val dominantStateStr = extractString(json, "dominantState") ?: "NORMAL"
            val dominantState = try {
                FatigueState.valueOf(dominantStateStr)
            } catch (_: Exception) {
                FatigueState.NORMAL
            }
            val averageScore = extractFloat(json, "averageScore") ?: 0f
            val peakScore = extractFloat(json, "peakScore") ?: 0f

            SessionSummary(
                sessionId = sessionId,
                startTime = startTime,
                endTime = endTime,
                durationMs = durationMs,
                totalAlerts = totalAlerts,
                dominantState = dominantState,
                averageScore = averageScore,
                peakScore = peakScore
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractLong(json: String, key: String): Long? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    @Suppress("SameParameterValue")
    private fun extractInt(json: String, key: String): Int? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractFloat(json: String, key: String): Float? {
        val pattern = "\"$key\"\\s*:\\s*([\\d.]+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }
}
