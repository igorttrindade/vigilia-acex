package com.vigilia.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigilia.app.data.repository.SessionRepository
import com.vigilia.app.domain.model.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI State for the History screen.
 */
data class HistoryUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = true,
    val isEmpty: Boolean = false,
)

/**
 * ViewModel for the History screen.
 * Responsible for loading session summaries. Tapping a session navigates to the detail
 * screen — this VM no longer exports files.
 */
@Suppress("unused")
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SessionRepository(application)
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.deleteOldSessions()
            loadSessions()
        }
    }

    /**
     * Loads the list of saved sessions from the repository.
     */
    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val sessions = repository.getSessions()
            _uiState.update {
                it.copy(
                    sessions = sessions,
                    isLoading = false,
                    isEmpty = sessions.isEmpty(),
                )
            }
        }
    }
}
