package com.vigilia.app.data.repository

import com.vigilia.app.data.remote.SupabaseClient
import com.vigilia.app.data.remote.dto.ProfileDto
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from

/** Handles Supabase email/password authentication. */
class AuthRepository {

    /** Signs in with email and password. */
    suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        SupabaseClient.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /** Creates a new account with email, password and full name. Upserts the profile row immediately after auth. */
    suspend fun signUp(email: String, password: String, fullName: String): Result<Unit> = runCatching {
        val user = SupabaseClient.client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        val userId = user?.id
            ?: SupabaseClient.client.auth.currentUserOrNull()?.id
            ?: error("Usuário não encontrado após o cadastro")
        SupabaseClient.client.from("profiles").upsert(
            ProfileDto(id = userId, fullname = fullName)
        )
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
