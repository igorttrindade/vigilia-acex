package com.vigilia.app.ui.options

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vigilia.app.data.remote.SupabaseClient
import com.vigilia.app.data.repository.AuthRepository
import com.vigilia.app.data.repository.ProfileRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val email: String = "",
    val fullName: String = "",
    val isLoading: Boolean = false,
    val currentPasswordError: String? = null,
    val newPasswordError: String? = null,
    val passwordUpdated: Boolean = false,
)

/**
 * Estado do Perfil + fluxo de troca de senha.
 *
 * Supabase-kt não tem API dedicada de re-auth. Padrão adotado: valida a senha
 * atual chamando `signIn(email, currentPassword)` — se falhar, bloqueia antes
 * de chamar `updatePassword`. Um signIn com as mesmas credenciais renova o
 * token da sessão vigente sem deslogar.
 */
class ProfileViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val profileRepository: ProfileRepository = ProfileRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val email = SupabaseClient.client.auth.currentUserOrNull()?.email.orEmpty()
            val profile = runCatching { profileRepository.getCurrentProfile() }.getOrNull()
            _uiState.update {
                it.copy(email = email, fullName = profile?.fullname.orEmpty())
            }
        }
    }

    fun changePassword(current: String, new: String, confirm: String) {
        if (new.length < 8) {
            _uiState.update { it.copy(newPasswordError = "Mínimo de 8 caracteres", passwordUpdated = false) }
            return
        }
        if (new != confirm) {
            _uiState.update { it.copy(newPasswordError = "As senhas não coincidem", passwordUpdated = false) }
            return
        }
        _uiState.update {
            it.copy(
                isLoading = true,
                currentPasswordError = null,
                newPasswordError = null,
                passwordUpdated = false,
            )
        }
        viewModelScope.launch {
            val email = _uiState.value.email
            if (email.isBlank()) {
                _uiState.update {
                    it.copy(isLoading = false, currentPasswordError = "Sessão inválida. Faça login novamente.")
                }
                return@launch
            }
            val reauth = authRepository.signIn(email, current)
            if (reauth.isFailure) {
                _uiState.update {
                    it.copy(isLoading = false, currentPasswordError = "Senha atual incorreta")
                }
                return@launch
            }
            val result = authRepository.updatePassword(new)
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(isLoading = false, passwordUpdated = true)
                } else {
                    it.copy(
                        isLoading = false,
                        newPasswordError = "Não foi possível atualizar. Tente novamente.",
                    )
                }
            }
        }
    }

    fun clearErrors() {
        _uiState.update { it.copy(currentPasswordError = null, newPasswordError = null) }
    }

    fun consumePasswordUpdated() {
        _uiState.update { it.copy(passwordUpdated = false) }
    }
}
