package com.vigilia.app.terms

/**
 * Versão vigente dos termos legais do app.
 *
 * TODO(legal): substituir os placeholders em `assets/terms/` (arquivos .md) pelo texto
 * definitivo antes do release em produção. Ao publicar uma nova versão,
 * bumpar as constantes abaixo (usar a data de publicação) e adicionar novos
 * arquivos em `assets/terms/` com o nome apontado por [TOS_ASSET]/
 * [PRIVACY_ASSET]. Usuários com aceite em versão antiga serão forçados a
 * re-aceitar no próximo login.
 */
object TermsConfig {
    const val TOS_CURRENT_VERSION = "2026-09-04"
    const val PRIVACY_CURRENT_VERSION = "2026-08-31"

    const val TOS_ASSET = "terms/tos_v2026-09-04.md"
    const val PRIVACY_ASSET = "terms/privacy_v2026-08-31.md"
}
