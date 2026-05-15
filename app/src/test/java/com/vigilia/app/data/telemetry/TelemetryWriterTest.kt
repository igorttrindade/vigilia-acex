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
