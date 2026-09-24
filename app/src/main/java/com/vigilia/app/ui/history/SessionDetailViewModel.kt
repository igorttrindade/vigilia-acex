package com.vigilia.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigilia.app.data.repository.AuthRepository
import com.vigilia.app.data.repository.SessionRepository
import com.vigilia.app.data.repository.SessionTelemetryPoint
import com.vigilia.app.domain.model.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionDetailUiState(
    val summary: SessionSummary? = null,
    val timeline: List<SessionTelemetryPoint> = emptyList(),
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
)

/**
 * Loads a single session (summary + downsampled telemetry) for the detail screen.
 * The sessionId is provided via the constructor by the navigation host.
 */
class SessionDetailViewModel(
    application: Application,
    private val sessionId: String,
) : AndroidViewModel(application) {

    private val repository = SessionRepository(application)
    private val authRepository = AuthRepository()
    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val userId = authRepository.currentUserId() ?: ""
            val summary = repository.getSessions(userId).firstOrNull { it.sessionId == sessionId }
            if (summary == null) {
                _uiState.update { it.copy(isLoading = false, notFound = true) }
                return@launch
            }
            val timeline = repository.getSessionTelemetry(sessionId)
            _uiState.update {
                it.copy(
                    summary = summary,
                    timeline = timeline,
                    isLoading = false,
                    notFound = false,
                )
            }
        }
    }
}
