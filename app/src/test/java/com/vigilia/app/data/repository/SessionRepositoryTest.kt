package com.vigilia.app.data.repository

import com.vigilia.app.domain.model.FatigueState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: SessionRepository
    private lateinit var testBaseDir: File

    @Before
    fun setUp() {
        testBaseDir = tempFolder.newFolder("sessions")
        repository = SessionRepository(testBaseDir)
    }

    @Test
    fun `getSessions returns sorted sessions from disk`() = runBlocking {
        // Create two dummy session folders
        val session1 = File(testBaseDir, "session1").apply { mkdirs() }
        val session2 = File(testBaseDir, "session2").apply { mkdirs() }
        
        val summary1 = """
            {
              "sessionId": "session1",
              "startTime": 1000,
              "endTime": 2000,
              "durationMs": 1000,
              "totalAlerts": 1,
              "dominantState": "NORMAL",
              "averageScore": 10.5,
              "peakScore": 20.0
            }
        """.trimIndent()
        
        val summary2 = """
            {
              "sessionId": "session2",
              "startTime": 5000,
              "endTime": 6000,
              "durationMs": 1000,
              "totalAlerts": 5,
              "dominantState": "FATIGUED",
              "averageScore": 80.0,
              "peakScore": 95.0
            }
        """.trimIndent()
        
        File(session1, "session_summary.json").writeText(summary1)
        File(session2, "session_summary.json").writeText(summary2)
        
        val sessions = repository.getSessions()
        
        assertEquals(2, sessions.size)
        assertEquals("session2", sessions[0].sessionId) // Newest first (startTime 5000)
        assertEquals("session1", sessions[1].sessionId)
        assertEquals(FatigueState.FATIGUED, sessions[0].dominantState)
        assertEquals(80.0f, sessions[0].averageScore, 0.01f)
    }

    @Test
    fun `getSessionFolder returns correct path`() {
        val folder = repository.getSessionFolder("test-session")
        assertEquals(File(testBaseDir, "test-session").absolutePath, folder.absolutePath)
    }
}
