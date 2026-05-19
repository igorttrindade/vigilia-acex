package com.vigilia.app.ui.history

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
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
 * Responsible for loading session summaries and exporting session data.
 */
@Suppress("unused")
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SessionRepository(application)
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
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

    /**
     * Triggers the Android ShareSheet to export session data (CSV and JSON).
     *
     * @param sessionId The ID of the session to export.
     */
    fun exportSession(sessionId: String) {
        try {
            val folder = repository.getSessionFolder(sessionId)
            if (!folder.exists()) return

            val files = folder.listFiles()?.filter { it.isFile } ?: return
            if (files.isEmpty()) return

            val uris = ArrayList(files.map { file ->
                FileProvider.getUriForFile(
                    getApplication(),
                    "com.vigilia.app.fileprovider",
                    file,
                )
            })

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                // ClipData required on API 29+ for permission grants to propagate
                clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
                    uris.drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val shareIntent = Intent.createChooser(intent, "Exportar Sessão").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            getApplication<Application>().startActivity(shareIntent)
        } catch (e: Exception) {
            Log.e("HistoryViewModel", "Export failed", e)
        }
    }
}
