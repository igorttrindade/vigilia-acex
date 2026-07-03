package com.vigilia.app.data.telemetry

import com.vigilia.app.domain.model.TelemetryRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelemetryWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var telemetryWriter: TelemetryWriter
    private lateinit var testBaseDir: File

    @Before
    fun setUp() {
        testBaseDir = tempFolder.newFolder("sessions")
        telemetryWriter = TelemetryWriter(testBaseDir)
    }

    @Test
    fun `two consecutive sessions both persist session_summary json`() = runBlocking {
        val id1 = telemetryWriter.startSession()
        telemetryWriter.writeRecord(
            TelemetryRecord(
                sessionId = id1,
                timestamp = 1000L,
                score = 30.0f,
                state = com.vigilia.app.domain.model.FatigueState.NORMAL,
                eyeOpenness = 0.8f,
                blinkRate = 15.0f,
                isYawning = false,
                isFaceDetected = true,
                alertActive = false,
            )
        )
        telemetryWriter.stopSession()

        val id2 = telemetryWriter.startSession()
        assertTrue("Second session must have a distinct id", id2 != id1)
        telemetryWriter.writeRecord(
            TelemetryRecord(
                sessionId = id2,
                timestamp = 2000L,
                score = 70.0f,
                state = com.vigilia.app.domain.model.FatigueState.FATIGUED,
                eyeOpenness = 0.2f,
                blinkRate = 6.0f,
                isYawning = true,
                isFaceDetected = true,
                alertActive = true,
            )
        )
        telemetryWriter.stopSession()

        val summary1 = File(File(testBaseDir, id1), "session_summary.json")
        val summary2 = File(File(testBaseDir, id2), "session_summary.json")
        assertTrue("Session 1 summary must exist", summary1.exists())
        assertTrue("Session 2 summary must exist", summary2.exists())
        assertTrue("Session 1 JSON references its own id", summary1.readText().contains(id1))
        assertTrue("Session 2 JSON references its own id", summary2.readText().contains(id2))
    }

    @Test
    fun `startSession finalizes an unfinished previous session`() = runBlocking {
        val id1 = telemetryWriter.startSession()
        telemetryWriter.writeRecord(
            TelemetryRecord(
                sessionId = id1,
                timestamp = 500L,
                score = 20.0f,
                state = com.vigilia.app.domain.model.FatigueState.NORMAL,
                eyeOpenness = 0.9f,
                blinkRate = 14.0f,
                isYawning = false,
                isFaceDetected = true,
                alertActive = false,
            )
        )
        // No stopSession() — simulate the bind-keeps-service-alive path

        val id2 = telemetryWriter.startSession()
        telemetryWriter.stopSession()

        val summary1 = File(File(testBaseDir, id1), "session_summary.json")
        val summary2 = File(File(testBaseDir, id2), "session_summary.json")
        assertTrue("Guard-rail must write summary for orphan session 1", summary1.exists())
        assertTrue("Session 2 summary must exist", summary2.exists())
        assertTrue("Session 1 JSON must reference session 1 id", summary1.readText().contains(id1))
    }

    @Test
    fun `session creation and record writing works`() = runBlocking {
        val sessionId = telemetryWriter.startSession()
        assertTrue("SessionId should not be empty", sessionId.isNotEmpty())
        
        val sessionDir = File(testBaseDir, sessionId)
        assertTrue("Session directory should exist", sessionDir.exists())
        
        val csvFile = File(sessionDir, "session.csv")
        assertTrue("CSV file should exist", csvFile.exists())
        
        val record = TelemetryRecord(
            sessionId = sessionId,
            timestamp = 1000L,
            score = 45.0f,
            state = com.vigilia.app.domain.model.FatigueState.WARNING,
            eyeOpenness = 0.5f,
            blinkRate = 12.0f,
            isYawning = false,
            isFaceDetected = true,
            alertActive = true,
        )
        
        telemetryWriter.writeRecord(record)
        
        val summary = telemetryWriter.stopSession()
        assertEquals(sessionId, summary.sessionId)
        assertEquals(45.0f, summary.averageScore, 0.01f)
        assertEquals(1, summary.totalAlerts)
        assertTrue("startTime should be a real wall-clock timestamp", summary.startTime > 0L)
        assertTrue("endTime should be >= startTime", summary.endTime >= summary.startTime)
        
        val summaryFile = File(sessionDir, "session_summary.json")
        assertTrue("Summary file should exist", summaryFile.exists())
        val json = summaryFile.readText()
        assertTrue("JSON should contain sessionId", json.contains(sessionId))
        assertTrue("JSON should contain dominantState", json.contains("WARNING"))
    }
}
