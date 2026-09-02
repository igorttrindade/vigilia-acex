package com.vigilia.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.vigilia.app.data.remote.SupabaseClient
import com.vigilia.app.data.repository.AuthRepository
import com.vigilia.app.data.repository.ProfileRepository
import com.vigilia.app.service.SyncWorker
import com.vigilia.app.ui.navigation.VigiliaNavGraph
import com.vigilia.app.ui.theme.AccentAmber
import com.vigilia.app.ui.theme.BackgroundDark
import com.vigilia.app.ui.theme.VigiliaTheme
import io.github.jan.supabase.auth.handleDeeplinks

private const val ROUTE_AUTH = "auth"
private const val ROUTE_SETUP = "setup"
private const val ROUTE_TERMS_LOGIN = "terms_login"

class MainActivity : ComponentActivity() {

    private val isPasswordResetDeepLink = mutableStateOf(value = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        processDeepLink(intent)
        if (AuthRepository().isLoggedIn()) {
            SyncWorker.enqueue(this)
        }

        setContent {
            VigiliaTheme {
                val navController = rememberNavController()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BackgroundDark,
                ) {
                    // Resolve o startDestination assíncrono: se logado, precisa consultar
                    // o profile pra saber se aceites estão em dia. Enquanto resolve, mostra
                    // uma splash simples.
                    var startDestination by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        val authRepo = AuthRepository()
                        if (!authRepo.isLoggedIn()) {
                            startDestination = ROUTE_AUTH
                            return@LaunchedEffect
                        }
                        val profileRepo = ProfileRepository()
                        val profile = profileRepo.getCurrentProfile()
                        // Auto-heal: se profile não existe (órfão), tenta criar. A tela de
                        // termos vai gravar as versões em seguida.
                        if (profile == null) {
                            profileRepo.ensureProfile().onFailure {
                                Log.w("MainActivity", "ensureProfile falhou no start", it)
                            }
                        }
                        val effectiveProfile = profile ?: profileRepo.getCurrentProfile()
                        startDestination = if (profileRepo.needsTermsAcceptance(effectiveProfile)) {
                            ROUTE_TERMS_LOGIN
                        } else {
                            ROUTE_SETUP
                        }
                    }

                    val resolved = startDestination
                    if (resolved == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = AccentAmber)
                        }
                    } else {
                        VigiliaNavGraph(
                            navController = navController,
                            startDestination = resolved,
                            isPasswordResetDeepLink = isPasswordResetDeepLink.value,
                            onPasswordResetHandled = {
                                isPasswordResetDeepLink.value = false
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processDeepLink(intent)
    }

    private fun processDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if ((uri.scheme == "vigilia") && (uri.host == "reset-password")) {
            try {
                SupabaseClient.client.handleDeeplinks(intent)
                isPasswordResetDeepLink.value = true
            } catch (e: Exception) {
                Log.w("MainActivity", "Deep link processing failed", e)
            }
        }
    }
}
