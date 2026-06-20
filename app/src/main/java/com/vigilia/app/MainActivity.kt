package com.vigilia.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.vigilia.app.data.remote.SupabaseClient
import com.vigilia.app.data.repository.AuthRepository
import com.vigilia.app.service.SyncWorker
import com.vigilia.app.ui.navigation.VigiliaNavGraph
import com.vigilia.app.ui.theme.BackgroundDark
import com.vigilia.app.ui.theme.VigiliaTheme
import io.github.jan.supabase.auth.handleDeeplinks

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
                    VigiliaNavGraph(
                        navController = navController,
                        isPasswordResetDeepLink = isPasswordResetDeepLink.value,
                        onPasswordResetHandled = {
                            isPasswordResetDeepLink.value = false
                        },
                    )
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
