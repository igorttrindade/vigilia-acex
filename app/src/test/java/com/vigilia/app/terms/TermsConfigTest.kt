package com.vigilia.app.terms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard-rails on [TermsConfig] para evitar regressões:
 *   - Versões nunca podem ficar em branco (o `handle_new_user` trigger no
 *     Supabase converte string vazia em NULL, o que quebraria o gate de
 *     aceite).
 *   - O caminho do asset deve conter a versão — evita bumpar a versão sem
 *     lembrar de criar o novo arquivo em `assets/terms/`.
 */
class TermsConfigTest {

    @Test
    fun `ToS version is non-blank`() {
        assertTrue(TermsConfig.TOS_CURRENT_VERSION.isNotBlank())
    }

    @Test
    fun `privacy version is non-blank`() {
        assertTrue(TermsConfig.PRIVACY_CURRENT_VERSION.isNotBlank())
    }

    @Test
    fun `ToS asset path contains the ToS version`() {
        assertTrue(
            "TOS_ASSET (${TermsConfig.TOS_ASSET}) deve conter TOS_CURRENT_VERSION",
            TermsConfig.TOS_ASSET.contains(TermsConfig.TOS_CURRENT_VERSION),
        )
    }

    @Test
    fun `privacy asset path contains the privacy version`() {
        assertTrue(
            "PRIVACY_ASSET (${TermsConfig.PRIVACY_ASSET}) deve conter PRIVACY_CURRENT_VERSION",
            TermsConfig.PRIVACY_ASSET.contains(TermsConfig.PRIVACY_CURRENT_VERSION),
        )
    }

    @Test
    fun `asset paths point into the terms subfolder`() {
        assertTrue(TermsConfig.TOS_ASSET.startsWith("terms/"))
        assertTrue(TermsConfig.PRIVACY_ASSET.startsWith("terms/"))
    }

    @Test
    fun `ToS asset path is markdown`() {
        assertTrue(TermsConfig.TOS_ASSET.endsWith(".md"))
    }

    @Test
    fun `privacy asset path is markdown`() {
        assertTrue(TermsConfig.PRIVACY_ASSET.endsWith(".md"))
        assertNotNull(TermsConfig.PRIVACY_ASSET)
    }

    @Test
    fun `ToS asset path is distinct from privacy asset path`() {
        assertFalse(
            "TOS_ASSET não pode ser igual a PRIVACY_ASSET",
            TermsConfig.TOS_ASSET == TermsConfig.PRIVACY_ASSET,
        )
    }
}
