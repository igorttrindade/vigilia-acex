package com.vigilia.app.data.repository

import android.util.Log
import com.vigilia.app.data.remote.SupabaseClient
import com.vigilia.app.data.remote.dto.ProfileDto
import com.vigilia.app.terms.TermsConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** Handles Supabase email/password authentication. */
class AuthRepository {

    /**
     * True when Supabase-kt has an authenticated session ready. Consumed by the password
     * reset flow: [SupabaseClient.client.handleDeeplinks] parses the token asynchronously,
     * so the reset UI must gate its "save new password" button on this flag or the user
     * can submit before the session is ready and burn the single-use token silently.
     */
    val isSessionReady: StateFlow<Boolean> = SupabaseClient.client.auth.sessionStatus
        .map { it is SessionStatus.Authenticated }
        .stateIn(
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            started = SharingStarted.Eagerly,
            initialValue = SupabaseClient.client.auth.currentSessionOrNull() != null,
        )

    /** Signs in with email and password. */
    suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        SupabaseClient.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /**
     * Creates a new account with email, password and full name, gravando o
     * aceite dos termos na mesma chamada.
     *
     * O `full_name` e as versões/timestamps dos aceites são passados como
     * `raw_user_meta_data` do usuário Supabase. O trigger `handle_new_user`
     * do lado do banco lê esses campos e cria a linha correspondente em
     * `profiles` (idempotente via `ON CONFLICT DO NOTHING`).
     *
     * Como fallback (caso o trigger tenha sido desabilitado no dashboard), o
     * método também tenta um `upsert` direto em `profiles` — mas isolado num
     * `try/catch`, porque com "Confirm email" habilitado a sessão ainda não
     * está ativa nesse ponto e a RLS bloqueia o upsert. O trigger é a
     * garantia principal; o upsert é opcional e não pode fazer o signUp
     * inteiro falhar.
     */
    suspend fun signUp(
        email: String,
        password: String,
        fullName: String,
        tosAcceptedAt: Instant,
        privacyAcceptedAt: Instant,
    ): Result<Unit> = runCatching {
        val user = SupabaseClient.client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            this.data = buildJsonObject {
                put("full_name", fullName)
                put("tos_version", TermsConfig.TOS_CURRENT_VERSION)
                put("tos_accepted_at", tosAcceptedAt.toString())
                put("privacy_version", TermsConfig.PRIVACY_CURRENT_VERSION)
                put("privacy_accepted_at", privacyAcceptedAt.toString())
            }
        }
        val userId = user?.id
            ?: SupabaseClient.client.auth.currentUserOrNull()?.id

        // Fallback opcional. Se falhar (RLS bloqueando por falta de sessão, ou
        // trigger já preencheu), tudo bem — o trigger é a garantia.
        if (userId != null) {
            try {
                SupabaseClient.client.from("profiles").upsert(
                    ProfileDto(
                        id = userId,
                        fullname = fullName,
                        tosVersion = TermsConfig.TOS_CURRENT_VERSION,
                        tosAcceptedAt = tosAcceptedAt.toString(),
                        privacyVersion = TermsConfig.PRIVACY_CURRENT_VERSION,
                        privacyAcceptedAt = privacyAcceptedAt.toString(),
                    )
                )
            } catch (e: Exception) {
                Log.i(
                    "AuthRepository",
                    "Profile upsert fallback falhou (esperado com email confirmation): ${e.message}",
                )
            }
        }
    }

    /** Sends a password reset email with a deep link back to the app. */
    suspend fun sendPasswordReset(email: String): Result<Unit> = runCatching {
        SupabaseClient.client.auth.resetPasswordForEmail(
            email = email,
            redirectUrl = "vigilia://reset-password",
        )
    }

    /** Updates the password of the currently authenticated user. */
    suspend fun updatePassword(newPassword: String): Result<Unit> = runCatching {
        SupabaseClient.client.auth.updateUser {
            password = newPassword
        }
    }

    /** Signs out the current user. */
    suspend fun signOut(): Result<Unit> = runCatching {
        SupabaseClient.client.auth.signOut()
    }

    /** Returns true if there is a currently logged-in user. */
    fun isLoggedIn(): Boolean = SupabaseClient.client.auth.currentSessionOrNull() != null

    /** Returns the current user ID, or null if not logged in. */
    fun currentUserId(): String? = SupabaseClient.client.auth.currentUserOrNull()?.id

    /** Refreshes the Supabase session and returns the user ID, or null if not logged in / refresh fails. */
    suspend fun refreshAndGetUserId(): String? = runCatching {
        SupabaseClient.client.auth.refreshCurrentSession()
        SupabaseClient.client.auth.currentUserOrNull()?.id
    }.getOrNull()
}
