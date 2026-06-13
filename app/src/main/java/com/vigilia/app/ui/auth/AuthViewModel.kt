package com.vigilia.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vigilia.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val errorMessage: String? = null,
    val nameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val email: String = "",
    val password: String = "",
    val fullName: String = "",
    // Forgot password
    val resetEmailSent: Boolean = false,
    // Reset password (after deep link)
    val newPasswordError: String? = null,
    val resetComplete: Boolean = false,
)

/** Manages email/password authentication state for [AuthScreen]. */
class AuthViewModel : ViewModel() {

    private val authRepository = AuthRepository()

    private val _uiState = MutableStateFlow(
        AuthUiState(isLoggedIn = authRepository.isLoggedIn())
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onEmailChanged(email: String) {
        _uiState.update { it.copy(email = email, emailError = null) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, passwordError = null) }
    }

    fun onFullNameChanged(fullName: String) {
        _uiState.update { it.copy(fullName = fullName, nameError = null) }
    }

    private fun validateFields(isSignUp: Boolean): Boolean {
        var valid = true
        val s = _uiState.value

        if (isSignUp && s.fullName.isBlank()) {
            _uiState.update { it.copy(nameError = "Informe seu nome completo") }
            valid = false
        }
        if (s.email.isBlank()) {
            _uiState.update { it.copy(emailError = "Informe seu e-mail") }
            valid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(s.email.trim()).matches()) {
            _uiState.update { it.copy(emailError = "E-mail inválido") }
            valid = false
        }
        if (s.password.isBlank()) {
            _uiState.update { it.copy(passwordError = "Informe sua senha") }
            valid = false
        } else if (s.password.length < 8) {
            _uiState.update { it.copy(passwordError = "A senha deve ter pelo menos 8 caracteres") }
            valid = false
        }
        return valid
    }

    private fun mapError(message: String?): String = when {
        message == null -> "Erro inesperado. Tente novamente."
        message.contains("Invalid login credentials", ignoreCase = true) ->
            "E-mail ou senha incorretos"
        message.contains("already registered", ignoreCase = true) ->
            "Este e-mail já está cadastrado"
        message.contains("Email not confirmed", ignoreCase = true) ->
            "Confirme seu e-mail antes de entrar"
        message.contains("Invalid email", ignoreCase = true) ||
        message.contains("unable to validate", ignoreCase = true) ->
            "E-mail inválido"
        message.contains("Email not found", ignoreCase = true) ||
        message.contains("user not found", ignoreCase = true) ->
            "E-mail não encontrado"
        message.contains("network", ignoreCase = true) ||
        message.contains("Unable to resolve host", ignoreCase = true) ->
            "Sem conexão. Verifique sua internet."
        else -> "Erro inesperado. Tente novamente."
    }

    fun signIn() {
        if (!validateFields(isSignUp = false)) return
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.signIn(state.email, state.password)
                .onSuccess { _uiState.update { it.copy(isLoading = false, isLoggedIn = true) } }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = mapError(e.message)) }
                }
        }
    }

    fun signUp() {
        if (!validateFields(isSignUp = true)) return
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.signUp(state.email, state.password, state.fullName)
                .onSuccess { _uiState.update { it.copy(isLoading = false, isLoggedIn = true) } }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = mapError(e.message)) }
                }
        }
    }

    fun sendPasswordReset(email: String) {
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            _uiState.update { it.copy(emailError = "Informe um e-mail válido") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, emailError = null) }
        viewModelScope.launch {
            authRepository.sendPasswordReset(email.trim())
                .onSuccess { _uiState.update { it.copy(isLoading = false, resetEmailSent = true) } }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = mapError(e.message)) }
                }
        }
    }

    fun updatePassword(newPassword: String) {
        if (newPassword.isBlank()) {
            _uiState.update { it.copy(newPasswordError = "Informe a nova senha") }
            return
        }
        if (newPassword.length < 8) {
            _uiState.update { it.copy(newPasswordError = "A senha deve ter pelo menos 8 caracteres") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, newPasswordError = null) }
        viewModelScope.launch {
            authRepository.updatePassword(newPassword)
                .onSuccess { _uiState.update { it.copy(isLoading = false, resetComplete = true) } }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = mapError(e.message)) }
                }
        }
    }

    fun clearResetState() {
        _uiState.update { it.copy(resetEmailSent = false, resetComplete = false, newPasswordError = null) }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _uiState.update { it.copy(isLoggedIn = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, nameError = null, emailError = null, passwordError = null) }
    }
}
