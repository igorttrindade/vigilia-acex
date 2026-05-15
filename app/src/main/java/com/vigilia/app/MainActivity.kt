package com.vigilia.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.vigilia.app.ui.navigation.VigiliaNavGraph
import com.vigilia.app.ui.theme.BackgroundDark
import com.vigilia.app.ui.theme.VigiliaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VigiliaTheme {
                val navController = rememberNavController()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BackgroundDark,
                ) {
                    VigiliaNavGraph(navController = navController)
                }
            }
        }
    }
}
