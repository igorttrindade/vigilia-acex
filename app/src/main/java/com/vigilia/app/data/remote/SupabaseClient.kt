package com.vigilia.app.data.remote

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseClient {
    val client = createSupabaseClient(
        supabaseUrl = "https://udunplsgguybcyiscjpp.supabase.co",
        supabaseKey = "sb_publishable_7EKwEv-HbOZdAq77oOGpNw_Mo1KZ3as",
    ) {
        install(Auth)
        install(Postgrest)
    }
}
