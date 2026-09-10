package com.vigilia.app.ui.history

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigilia.app.data.repository.SessionRepository
import com.vigilia.app.data.repository.SyncRepository
import com.vigilia.app.domain.model.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Per-session sync state shown on the history card.
 * Transitions: Pending → Syncing → (Synced | Failed). Failed can go back to Syncing on retry.
 */
sealed class SessionSyncStatus {
    data object Synced : SessionSyncStatus()
    data object Pending : SessionSyncStatus()
    data object Syncing : SessionSyncStatus()
    data class Failed(val message: String) : SessionSyncStatus()
}

/**
 * UI State for the History screen.
 */
data class HistoryUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val syncStatuses: Map<String, SessionSyncStatus> = emptyMap(),
    val isLoading: Boolean = true,
    val isEmpty: Boolean = false,
)

/**
 * ViewModel for the History screen. Loads session summaries and exposes per-session
 * sync/export actions the driver can trigger manually when the background WorkManager
 * upload didn't complete (device-specific issue, network outage, aggressive vendor
 * battery saver, etc.).
 */
@Suppress("unused")
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SessionRepository(application)
    private val syncRepository = SyncRepository(application)
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.deleteOldSessions()
            loadSessions()
        }
    }

    /**
     * Loads sessions and (re)builds the per-session sync status map. Cross-checks the
     * local `.synced` marker with what's actually in Supabase, so the UI reflects the
     * truth in both directions:
     *  - marker present but row missing remotely → Pending (backend lost it, retry)
     *  - marker absent but row present remotely → Synced + write marker (self-heal)
     * If the remote query fails (offline / auth error), falls back to the local marker
     * only — no regression from the previous behavior.
     *
     * Statuses currently in `Syncing` are preserved so a refresh mid-upload doesn't
     * reset the spinner.
     */
    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val sessions = repository.getSessions()
            val previous = _uiState.value.syncStatuses
            val remoteIds = syncRepository.getRemoteSyncedSessionIds().getOrNull()

            val statuses = sessions.associate { session ->
                val id = session.sessionId
                val newStatus: SessionSyncStatus = when {
                    previous[id] is SessionSyncStatus.Syncing -> SessionSyncStatus.Syncing
                    remoteIds != null -> {
                        val inRemote = id in remoteIds
                        val localMarker = syncRepository.isSessionSynced(id)
                        when {
                            inRemote && !localMarker -> {
                                // Self-heal: row exists but marker was lost (crash between
                                // upsert and createNewFile). Write marker so future loads
                                // skip the remote check for this one.
                                syncRepository.markSessionSynced(id)
                                SessionSyncStatus.Synced
                            }
                            inRemote -> SessionSyncStatus.Synced
                            else -> previous[id] ?: SessionSyncStatus.Pending
                        }
                    }
                    // Remote query failed — fall back to local marker only.
                    syncRepository.isSessionSynced(id) -> SessionSyncStatus.Synced
                    else -> previous[id] ?: SessionSyncStatus.Pending
                }
                id to newStatus
            }
            _uiState.update {
                it.copy(
                    sessions = sessions,
                    syncStatuses = statuses,
                    isLoading = false,
                    isEmpty = sessions.isEmpty(),
                )
            }
        }
    }

    /**
     * Attempts to upload a single session to Supabase. Updates its status to Syncing,
     * then to Synced/Failed based on the result. On failure, the UI exposes the message
     * and shows the "Exportar dados" fallback.
     */
    fun syncSession(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(syncStatuses = state.syncStatuses + (sessionId to SessionSyncStatus.Syncing))
            }
            val result = syncRepository.syncSingleSession(sessionId)
            val newStatus: SessionSyncStatus = result.fold(
                onSuccess = { SessionSyncStatus.Synced },
                onFailure = { SessionSyncStatus.Failed(it.message ?: "Erro desconhecido") },
            )
            _uiState.update { state ->
                state.copy(syncStatuses = state.syncStatuses + (sessionId to newStatus))
            }
        }
    }

    /**
     * Shares the raw `.csv` + `.json` for the given session through the system share sheet.
     * These are in the app's internal format and are meant for the support team to analyze
     * sessions the auto-sync could not upload.
     */
    fun exportSession(sessionId: String) {
        val app = getApplication<Application>()
        val folder = File(File(app.filesDir, "sessions"), sessionId)
        val files = folder.listFiles()?.filter { it.extension in listOf("csv", "json") } ?: emptyList()
        if (files.isEmpty()) {
            Log.w("HistoryViewModel", "No files to export for session $sessionId")
            return
        }
        val uris = ArrayList(
            files.map { FileProvider.getUriForFile(app, "com.vigilia.app.fileprovider", it) }
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Exportar dados da sessão").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(chooser)
    }
}
