package com.vigilia.app.ui.monitoring

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vigilia.app.data.repository.SyncRepository
import com.vigilia.app.data.telemetry.TelemetryWriter
import com.vigilia.app.domain.model.FatigueAssessment
import com.vigilia.app.lighting.LightingMode
import com.vigilia.app.service.MonitoringService
import com.vigilia.app.service.ServiceController
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale

/**
 * UI State for the Monitoring screen.
 */
data class MonitoringUiState(
    val assessment: FatigueAssessment? = null,
    val isMonitoringActive: Boolean = false,
    val elapsedTimeFormatted: String = "00:00:00",
    val alertCount: Int = 0,
    val showPositioningWarning: Boolean = false,
    val lightingMode: LightingMode = LightingMode.NORMAL,
)

/**
 * State machine for the end-of-session flow shown after the driver taps "Parar".
 * Transitions: Idle → Syncing → (Success | Failed) → (via continueToRating) → Rating → Idle.
 */
sealed class SessionEndState {
    data object Idle : SessionEndState()
    data object Syncing : SessionEndState()
    data object Success : SessionEndState()
    data class Failed(val message: String) : SessionEndState()
    data class Rating(val sessionId: String, val syncSucceeded: Boolean) : SessionEndState()
    data object Submitting : SessionEndState()
}

/**
 * ViewModel for the Monitoring screen.
 * Collects real-time data from the MonitoringService.
 */
@Suppress("unused")
class MonitoringViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MonitoringUiState())
    val uiState: StateFlow<MonitoringUiState> = _uiState.asStateFlow()

    private val _sessionEndState = MutableStateFlow<SessionEndState>(SessionEndState.Idle)
    val sessionEndState: StateFlow<SessionEndState> = _sessionEndState.asStateFlow()

    private var pendingSessionId: String? = null

    private var timerJob: Job? = null
    private var startTimeMillis: Long = 0

    // Rolling 60-second window of (timestampMs, isFaceDetected) for positioning check
    private val frameWindow = ArrayDeque<Pair<Long, Boolean>>()
    private var consecutiveFaceStartMs: Long? = null
    private var sessionStartMs: Long = 0L

    init {
        observeAssessment()
    }

    private fun observeAssessment() {
        viewModelScope.launch {
            MonitoringService.currentAssessment.collect { assessment ->
                val wasActive = _uiState.value.isMonitoringActive
                val isActive = assessment != null

                if (isActive && !wasActive) {
                    startTimer()
                } else if (!isActive && wasActive) {
                    stopTimer()
                }

                val positioningWarning = computePositioningWarning(assessment)

                _uiState.update { state ->
                    val prevState = state.assessment?.fatigueState
                    val wasAlerting = prevState == com.vigilia.app.domain.model.FatigueState.WARNING ||
                                     prevState == com.vigilia.app.domain.model.FatigueState.FATIGUED
                    val newAlertCount = if (
                        assessment != null &&
                        !wasAlerting &&
                        (assessment.fatigueState == com.vigilia.app.domain.model.FatigueState.WARNING ||
                         assessment.fatigueState == com.vigilia.app.domain.model.FatigueState.FATIGUED)
                    ) {
                        state.alertCount + 1
                    } else {
                        state.alertCount
                    }

                    val lightingMode = assessment?.lightingMode
                        ?.let { runCatching { LightingMode.valueOf(it) }.getOrNull() }
                        ?: LightingMode.NORMAL

                    state.copy(
                        assessment = assessment,
                        isMonitoringActive = isActive,
                        alertCount = newAlertCount,
                        showPositioningWarning = positioningWarning,
                        lightingMode = lightingMode,
                    )
                }
            }
        }
    }

    private fun computePositioningWarning(assessment: FatigueAssessment?): Boolean {
        if (assessment == null) return false

        val now = System.currentTimeMillis()
        frameWindow.addLast(now to assessment.isFaceDetected)
        while (frameWindow.size > 2000) {
            frameWindow.removeFirst()
        }
        while (frameWindow.isNotEmpty() && now - frameWindow.first().first > 60_000L) {
            frameWindow.removeFirst()
        }

        if (assessment.isFaceDetected) {
            if (consecutiveFaceStartMs == null) consecutiveFaceStartMs = now
        } else {
            consecutiveFaceStartMs = null
        }

        val current = _uiState.value.showPositioningWarning

        if (now - sessionStartMs < 30_000L || frameWindow.isEmpty()) return current

        val consecutiveMs = consecutiveFaceStartMs
        if (consecutiveMs != null && now - consecutiveMs >= 10_000L) return false

        val noFaceCount = frameWindow.count { !it.second }
        val ratio = noFaceCount.toFloat() / frameWindow.size
        return if (ratio > 0.30f) true else current
    }

    private fun startTimer() {
        startTimeMillis = System.currentTimeMillis()
        sessionStartMs = startTimeMillis
        frameWindow.clear()
        consecutiveFaceStartMs = null
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val elapsed = System.currentTimeMillis() - startTimeMillis
                _uiState.update { it.copy(elapsedTimeFormatted = formatElapsedTime(elapsed)) }
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        frameWindow.clear()
        consecutiveFaceStartMs = null
        _uiState.update { it.copy(elapsedTimeFormatted = "00:00:00", showPositioningWarning = false) }
    }

    private fun formatElapsedTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * Starts the monitoring session.
     */
    fun startMonitoring(context: Context) {
        ServiceController.startMonitoring(context)
    }

    /**
     * Stops the session, opens the sync dialog, waits for the writer to finalize the summary,
     * then attempts sync in foreground. 30s timeout so the driver doesn't wait forever if
     * the network is dying.
     */
    fun stopMonitoring(context: Context) {
        _sessionEndState.value = SessionEndState.Syncing
        ServiceController.stopMonitoring(context)
        stopTimer()
        _uiState.update { it.copy(assessment = null, isMonitoringActive = false, alertCount = 0) }

        val appContext = context.applicationContext
        viewModelScope.launch {
            val outcome: SessionEndState = try {
                val id = withTimeout(30_000L) {
                    MonitoringService.lastFinalizedSessionId
                        .filterNotNull()
                        .first()
                }
                // Consume so the next Stop doesn't grab this id.
                MonitoringService.lastFinalizedSessionId.value = null
                pendingSessionId = id
                val syncResult = SyncRepository(appContext).syncSingleSession(id)
                syncResult.fold(
                    onSuccess = { SessionEndState.Success },
                    onFailure = { SessionEndState.Failed(it.message ?: "Erro desconhecido") },
                )
            } catch (_: TimeoutCancellationException) {
                SessionEndState.Failed("Demorou demais para finalizar a sessão.")
            } catch (e: Exception) {
                SessionEndState.Failed(e.message ?: "Erro inesperado")
            }
            _sessionEndState.value = outcome
        }
    }

    /** Called by the "Continuar" button on the Success/Failed screen — moves to rating. */
    fun continueToRating() {
        val id = pendingSessionId
        val current = _sessionEndState.value
        if (id == null) {
            _sessionEndState.value = SessionEndState.Idle
            return
        }
        val syncSucceeded = current is SessionEndState.Success
        _sessionEndState.value = SessionEndState.Rating(id, syncSucceeded)
    }

    /**
     * Persists the driver's rating locally (always) and pushes it to Supabase (only if the
     * session already synced — otherwise it piggybacks on the next sync attempt from History).
     */
    fun submitRating(context: Context, rating: Int, comment: String?) {
        val current = _sessionEndState.value as? SessionEndState.Rating ?: return
        val trimmed = comment?.take(255)?.trim()?.takeIf { it.isNotEmpty() }
        _sessionEndState.value = SessionEndState.Submitting
        val appContext = context.applicationContext
        viewModelScope.launch {
            TelemetryWriter(appContext).updateSessionRating(current.sessionId, rating, trimmed)
                .onFailure { Log.w("MonitoringVM", "updateSessionRating falhou: ${it.message}") }
            if (current.syncSucceeded) {
                SyncRepository(appContext).uploadRating(current.sessionId, rating, trimmed)
                    .onFailure { Log.w("MonitoringVM", "uploadRating falhou (fica local): ${it.message}") }
            }
            pendingSessionId = null
            _sessionEndState.value = SessionEndState.Idle
        }
    }

    fun skipRating() {
        pendingSessionId = null
        _sessionEndState.value = SessionEndState.Idle
    }

    fun dismissSessionEndDialog() {
        pendingSessionId = null
        _sessionEndState.value = SessionEndState.Idle
    }
}
