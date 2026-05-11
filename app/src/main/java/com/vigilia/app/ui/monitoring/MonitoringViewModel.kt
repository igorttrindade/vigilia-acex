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
)

/**
 * ViewModel for the Monitoring screen.
 * Collects real-time data from the MonitoringService.
 */
class MonitoringViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MonitoringUiState())
    val uiState: StateFlow<MonitoringUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var startTimeMillis: Long = 0

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

                _uiState.update { state ->
                    val newAlertCount = if (
                        assessment != null && 
                        assessment.fatigueState != state.assessment?.fatigueState &&
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
                    )
                }
            }
        }
    }

    private fun startTimer() {
        startTimeMillis = System.currentTimeMillis()
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
        _uiState.update { it.copy(elapsedTimeFormatted = "00:00:00") }
    }

    private fun formatElapsedTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * Stops the active monitoring session.
     */
    fun stopMonitoring(context: Context) {
        ServiceController.stopMonitoring(context)
    }
}
