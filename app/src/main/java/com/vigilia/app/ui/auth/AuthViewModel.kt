package com.vigilia.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vigilia.app.data.repository.AuthRepository
import com.vigilia.app.data.repository.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** Dados do cadastro pendente aguardando aceite dos termos. */
data class PendingSignup(
    val email: String,
    val password: String,
    val fullName: String,
)

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
    // True once Supabase has finished processing the deep-link token and the session is
    // authenticated. Gates the "save new password" button so the user can't submit before
    // the async handshake completes (which would burn the single-use token silently).
    val isResetSessionReady: Boolean = false,
    // Sign-up requires email confirmation before accessing the app
    val registrationPendingConfirmation: Boolean = false,
    // Fluxo de aceite de termos:
    // Set quando o usuário apertou "Criar conta" com campos válidos e ainda
    // não aceitou os termos. O nav observa essa flag pra ir para terms_signup.
    val awaitingTermsForSignup: Boolean = false,
    // Set quando um signIn foi bem-sucedido mas o profile do usuário precisa
    // atualizar/aceitar termos (versão vigente diferente da gravada). O nav
    // observa essa flag pra ir para terms_login em vez de setup.
    val requiresTermsAcceptance: Boolean = false,
)

/** Manages email/password authentication state for [AuthScreen]. */
class AuthViewModel : ViewModel() {

    private val authRepository = AuthRepository()
    private val profileRepository = ProfileRepository()

    private val _uiState = MutableStateFlow(
        AuthUiState(
            isLoggedIn = authRepository.isLoggedIn(),
            isResetSessionReady = authRepository.isSessionReady.value,
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var resetSessionTimeoutJob: Job? = null
    private var pendingSignup: PendingSignup? = null

    init {
        // Mirror Supabase's session status into the UI state so the password-reset flow can
        // gate its submit button on it. All other flows are unaffected: sessionStatus is
        // Authenticated after a normal sign-in so the flag is true immediately.
        viewModelScope.launch {
            authRepository.isSessionReady.collect { ready ->
                _uiState.update { it.copy(isResetSessionReady = ready) }
            }
        }
    }

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
        message.contains("expired", ignoreCase = true) ||
        message.contains("invalid token", ignoreCase = true) ||
        message.contains("otp_expired", ignoreCase = true) ->
            "Link de redefinição expirado. Solicite um novo e-mail."
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
                .onSuccess {
                    // Auto-heal + gate de termos. Se o profile não existe (órfão), cria.
                    // Depois checa se aceite está em dia; se não, exige termos antes de logar.
                    val ensured = profileRepository.ensureProfile()
                    ensured.onFailure { e ->
                        android.util.Log.w("AuthViewModel", "ensureProfile falhou: ${e.message}", e)
                    }
                    val profile = profileRepository.getCurrentProfile()
                    val needsTerms = profileRepository.needsTermsAcceptance(profile)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoggedIn = !needsTerms,
                            requiresTermsAcceptance = needsTerms,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = mapError(e.message)) }
                }
        }
    }

    /**
     * Chamado pelo botão "Criar conta" na AuthScreen. Valida os campos e — se
     * OK — guarda o payload em [pendingSignup] e ativa a flag
     * [AuthUiState.awaitingTermsForSignup]. O nav observa essa flag e vai
     * para `terms_signup`; a criação real da conta só acontece depois em
     * [completeSignupAfterTerms].
     */
    fun beginSignup() {
        if (!validateFields(isSignUp = true)) return
        val state = _uiState.value
        pendingSignup = PendingSignup(
            email = state.email.trim(),
            password = state.password,
            fullName = state.fullName.trim(),
        )
        _uiState.update { it.copy(errorMessage = null, awaitingTermsForSignup = true) }
    }

    /**
     * Chamado pela TermsAcceptanceScreen(SIGNUP) ao aceitar os dois termos.
     * Dispara o signUp real com os timestamps, mesmo passando por metadata
     * para o trigger `handle_new_user` do Supabase preencher `profiles`.
     */
    fun completeSignupAfterTerms(tosAcceptedAt: Instant, privacyAcceptedAt: Instant) {
        val payload = pendingSignup ?: run {
            _uiState.update { it.copy(errorMessage = "Sessão de cadastro expirada. Tente novamente.", awaitingTermsForSignup = false) }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, awaitingTermsForSignup = false) }
        viewModelScope.launch {
            authRepository.signUp(
                email = payload.email,
                password = payload.password,
                fullName = payload.fullName,
                tosAcceptedAt = tosAcceptedAt,
                privacyAcceptedAt = privacyAcceptedAt,
            )
                .onSuccess {
                    pendingSignup = null
                    if (authRepository.isLoggedIn()) {
                        // Sessão ativa imediatamente (email confirm desabilitado). Termos já
                        // foram gravados pelo trigger + metadata — vai direto pro app.
                        _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                    } else {
                        // Email confirmation habilitado: sessão só abre após confirmar. Mostra
                        // tela "confira seu email".
                        _uiState.update { it.copy(isLoading = false, registrationPendingConfirmation = true) }
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = mapError(e.message)) }
                }
        }
    }

    /** Cancela o cadastro pendente (usuário voltou da TermsAcceptanceScreen). */
    fun cancelPendingSignup() {
        pendingSignup = null
        _uiState.update { it.copy(awaitingTermsForSignup = false) }
    }

    /**
     * Chamado pela TermsAcceptanceScreen(LOGIN) ao aceitar os dois termos
     * durante o fluxo de login de um usuário existente. Grava as versões +
     * timestamps no profile e libera acesso ao app.
     */
    fun completeTermsAcceptanceForLogin(tosAcceptedAt: Instant, privacyAcceptedAt: Instant) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            profileRepository.recordTermsAcceptance(tosAcceptedAt, privacyAcceptedAt)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoggedIn = true,
                            requiresTermsAcceptance = false,
                        )
                    }
                }
                .onFailure { e ->
                    android.util.Log.w("AuthViewModel", "recordTermsAcceptance falhou", e)
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

    /**
     * Called by [ResetPasswordScreen] on entry. Starts a defensive timeout — if Supabase
     * hasn't established the session from the deep-link token after 10 s, we surface a
     * "link expired" message so the user isn't stuck staring at a disabled button forever.
     */
    fun enterResetPasswordFlow() {
        resetSessionTimeoutJob?.cancel()
        resetSessionTimeoutJob = viewModelScope.launch {
            delay(RESET_SESSION_TIMEOUT_MS)
            if (!_uiState.value.isResetSessionReady) {
                _uiState.update {
                    it.copy(errorMessage = "Link de redefinição expirado. Solicite um novo e-mail.")
                }
            }
        }
    }

    /** Called by [ResetPasswordScreen] on dispose. Cancels the pending timeout. */
    fun leaveResetPasswordFlow() {
        resetSessionTimeoutJob?.cancel()
        resetSessionTimeoutJob = null
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
                .onFailure { e ->
                    android.util.Log.w("AuthViewModel", "Sign out failed on server", e)
                }
            pendingSignup = null
            _uiState.update {
                it.copy(
                    isLoggedIn = false,
                    requiresTermsAcceptance = false,
                    awaitingTermsForSignup = false,
                )
            }
        }
    }

    fun clearError() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                nameError = null,
                emailError = null,
                passwordError = null,
                registrationPendingConfirmation = false,
            )
        }
    }

    private companion object {
        // How long the reset screen waits for Supabase to establish the session before it
        // gives up and surfaces "link expired". Handles the case where the deep-link token
        // is corrupt or already-consumed and the session status never flips to Authenticated.
        const val RESET_SESSION_TIMEOUT_MS = 10_000L
    }
}
