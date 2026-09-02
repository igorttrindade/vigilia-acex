package com.vigilia.app.data.repository

import com.vigilia.app.data.remote.dto.ProfileDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testa somente [ProfileRepository.Companion.needsTermsAcceptance] — a versão
 * pura que recebe as versões vigentes como parâmetro. A versão de instância
 * (que depende de [com.vigilia.app.terms.TermsConfig]) é apenas um wrapper.
 */
class ProfileRepositoryTest {

    private val currentTos = "2026-08-31"
    private val currentPrivacy = "2026-08-31"

    private fun check(profile: ProfileDto?) = ProfileRepository.needsTermsAcceptance(
        profile = profile,
        currentTosVersion = currentTos,
        currentPrivacyVersion = currentPrivacy,
    )

    @Test
    fun `null profile requires acceptance`() {
        assertTrue(check(null))
    }

    @Test
    fun `profile with both versions matching and timestamps does not require acceptance`() {
        val profile = ProfileDto(
            id = "u1",
            fullname = "Test",
            tosVersion = currentTos,
            tosAcceptedAt = "2026-08-31T12:00:00Z",
            privacyVersion = currentPrivacy,
            privacyAcceptedAt = "2026-08-31T12:00:00Z",
        )
        assertFalse(check(profile))
    }

    @Test
    fun `profile with null ToS version requires acceptance`() {
        val profile = ProfileDto(
            id = "u1",
            fullname = "Test",
            tosVersion = null,
            tosAcceptedAt = null,
            privacyVersion = currentPrivacy,
            privacyAcceptedAt = "2026-08-31T12:00:00Z",
        )
        assertTrue(check(profile))
    }

    @Test
    fun `profile with null privacy version requires acceptance`() {
        val profile = ProfileDto(
            id = "u1",
            fullname = "Test",
            tosVersion = currentTos,
            tosAcceptedAt = "2026-08-31T12:00:00Z",
            privacyVersion = null,
            privacyAcceptedAt = null,
        )
        assertTrue(check(profile))
    }

    @Test
    fun `profile with outdated ToS version requires acceptance`() {
        val profile = ProfileDto(
            id = "u1",
            fullname = "Test",
            tosVersion = "2026-01-01",
            tosAcceptedAt = "2026-01-01T00:00:00Z",
            privacyVersion = currentPrivacy,
            privacyAcceptedAt = "2026-08-31T12:00:00Z",
        )
        assertTrue(check(profile))
    }

    @Test
    fun `profile with outdated privacy version requires acceptance`() {
        val profile = ProfileDto(
            id = "u1",
            fullname = "Test",
            tosVersion = currentTos,
            tosAcceptedAt = "2026-08-31T12:00:00Z",
            privacyVersion = "2026-01-01",
            privacyAcceptedAt = "2026-01-01T00:00:00Z",
        )
        assertTrue(check(profile))
    }

    @Test
    fun `profile with matching version but null timestamp requires acceptance`() {
        val profile = ProfileDto(
            id = "u1",
            fullname = "Test",
            tosVersion = currentTos,
            tosAcceptedAt = null,
            privacyVersion = currentPrivacy,
            privacyAcceptedAt = "2026-08-31T12:00:00Z",
        )
        assertTrue(check(profile))
    }

    @Test
    fun `profile with matching version but blank timestamp requires acceptance`() {
        val profile = ProfileDto(
            id = "u1",
            fullname = "Test",
            tosVersion = currentTos,
            tosAcceptedAt = "",
            privacyVersion = currentPrivacy,
            privacyAcceptedAt = "2026-08-31T12:00:00Z",
        )
        assertTrue(check(profile))
    }

    @Test
    fun `orphan safety-net row with full_name but no terms fields requires acceptance`() {
        // Simula o INSERT do safety-net SQL: profile criado sem aceites.
        val profile = ProfileDto(
            id = "u1",
            fullname = "usuario",
            tosVersion = null,
            tosAcceptedAt = null,
            privacyVersion = null,
            privacyAcceptedAt = null,
        )
        assertTrue(check(profile))
    }
}
