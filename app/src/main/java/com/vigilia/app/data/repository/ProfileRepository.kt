package com.vigilia.app.data.repository

import android.util.Log
import com.vigilia.app.data.remote.SupabaseClient
import com.vigilia.app.data.remote.dto.ProfileDto
import com.vigilia.app.terms.TermsConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import java.time.Instant

/**
 * Repositório para leitura/atualização do profile do usuário atualmente
 * autenticado. Também centraliza:
 *   1. **Auto-heal** ([ensureProfile]) — cria a linha em `profiles` caso ela
 *      não exista para o `auth.users.id` atual. Cobre usuários órfãos criados
 *      antes do trigger `handle_new_user` no Supabase existir, ou quando o
 *      trigger é desabilitado por acidente. Idempotente.
 *   2. **Gate de aceite versionado** ([needsTermsAcceptance]) — compara as
 *      versões gravadas com [TermsConfig.TOS_CURRENT_VERSION]/
 *      [TermsConfig.PRIVACY_CURRENT_VERSION].
 */
class ProfileRepository {

    private val authRepository = AuthRepository()

    /**
     * Busca o profile do usuário atual. Retorna null se não há sessão
     * autenticada ou se a row ainda não existe.
     */
    suspend fun getCurrentProfile(): ProfileDto? = runCatching {
        val userId = SupabaseClient.client.auth.currentUserOrNull()?.id ?: return null
        SupabaseClient.client.from("profiles")
            .select(columns = Columns.ALL) {
                filter { eq("id", userId) }
                limit(1)
            }
            .decodeSingleOrNull<ProfileDto>()
    }.onFailure {
        Log.w("ProfileRepository", "getCurrentProfile failed: ${it.message}", it)
    }.getOrNull()

    /**
     * Garante que existe uma linha em `profiles` para o usuário atual.
     * - Se já existe: no-op (a menos que [fullName] seja não-nulo, caso em que
     *   atualiza somente o full_name).
     * - Se não existe: cria com o nome informado ou o local-part do e-mail
     *   como fallback.
     *
     * Deve ser chamado após qualquer sucesso de signIn/signUp com sessão
     * ativa. Requer RLS de INSERT/UPDATE em `profiles` que permita
     * `auth.uid() = id`.
     */
    suspend fun ensureProfile(fullName: String? = null): Result<Unit> = runCatching {
        val user = SupabaseClient.client.auth.currentUserOrNull()
            ?: error("ensureProfile chamado sem sessão ativa")
        val existing = getCurrentProfile()
        if (existing == null) {
            val fallbackName = fullName?.takeIf { it.isNotBlank() }
                ?: user.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
                ?: ""
            SupabaseClient.client.from("profiles").upsert(
                ProfileDto(id = user.id, fullname = fallbackName)
            )
        } else if (fullName != null && fullName.isNotBlank() && fullName != existing.fullname) {
            SupabaseClient.client.from("profiles").upsert(
                existing.copy(fullname = fullName)
            )
        }
    }

    /**
     * Grava o aceite das versões atuais dos termos no profile do usuário
     * atual. Faz [ensureProfile] antes para cobrir o caso de o profile ainda
     * não existir.
     */
    suspend fun recordTermsAcceptance(
        tosAt: Instant,
        privacyAt: Instant,
    ): Result<Unit> = runCatching {
        ensureProfile().getOrThrow()
        val user = SupabaseClient.client.auth.currentUserOrNull()
            ?: error("recordTermsAcceptance chamado sem sessão ativa")
        val current = getCurrentProfile() ?: error("Profile não encontrado após ensureProfile")
        val updated = current.copy(
            tosVersion = TermsConfig.TOS_CURRENT_VERSION,
            tosAcceptedAt = tosAt.toString(),
            privacyVersion = TermsConfig.PRIVACY_CURRENT_VERSION,
            privacyAcceptedAt = privacyAt.toString(),
        )
        SupabaseClient.client.from("profiles").upsert(updated)
        // Also refresh session so that any downstream reads see the new state.
        authRepository.refreshAndGetUserId()
    }

    /**
     * Retorna true quando o profile:
     *   - é null (ainda não existe), ou
     *   - não tem versão gravada para um dos termos, ou
     *   - tem versão gravada diferente da versão vigente em [TermsConfig].
     */
    fun needsTermsAcceptance(profile: ProfileDto?): Boolean =
        needsTermsAcceptance(
            profile = profile,
            currentTosVersion = TermsConfig.TOS_CURRENT_VERSION,
            currentPrivacyVersion = TermsConfig.PRIVACY_CURRENT_VERSION,
        )

    companion object {
        /**
         * Versão testável — recebe as versões vigentes como parâmetro em vez
         * de ler do [TermsConfig] singleton. Isso permite escrever testes que
         * não dependem da versão atual hardcoded.
         */
        fun needsTermsAcceptance(
            profile: ProfileDto?,
            currentTosVersion: String,
            currentPrivacyVersion: String,
        ): Boolean {
            if (profile == null) return true
            val tosOk = profile.tosVersion == currentTosVersion &&
                !profile.tosAcceptedAt.isNullOrBlank()
            val privacyOk = profile.privacyVersion == currentPrivacyVersion &&
                !profile.privacyAcceptedAt.isNullOrBlank()
            return !(tosOk && privacyOk)
        }
    }
}
