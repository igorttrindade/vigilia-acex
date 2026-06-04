package com.vigilia.app.ui.monitoring

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vigilia.app.domain.model.FatigueAssessment
import com.vigilia.app.service.MonitoringService
import com.vigilia.app.service.ServiceController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
)

/**
 * ViewModel for the Monitoring screen.
 * Collects real-time data from the MonitoringService.
 */
@Suppress("unused")
class MonitoringViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MonitoringUiState())
    val uiState: StateFlow<MonitoringUiState> = _uiState.asStateFlow()

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

                    state.copy(
                        assessment = assessment,
                        isMonitoringActive = isActive,
                        alertCount = newAlertCount,
                        showPositioningWarning = positioningWarning,
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
     * Stops the active monitoring session.
     */
    fun stopMonitoring(context: Context) {
        ServiceController.stopMonitoring(context)
        stopTimer()
        _uiState.update { it.copy(isMonitoringActive = false, alertCount = 0) }
    }
}
