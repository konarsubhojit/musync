package com.musync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.musync.logging.AppLogger
import com.musync.navigation.MuSyncNavGraph
import com.musync.ui.theme.MuSyncTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i(TAG, "MainActivity onCreate (intent=${intent?.dataString ?: "<none>"})")
        enableEdgeToEdge()
        setContent {
            val controller = rememberNavController()
            navController = controller
            MuSyncTheme {
                MuSyncNavGraph(navController = controller)
            }
        }
    }

    /**
     * Forward deep-link intents to the [NavController] when the app is already
     * running in the foreground (e.g. the user taps a second room link while
     * the player screen is open).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLogger.i(TAG, "onNewIntent: ${intent.dataString ?: "<no data>"}")
        if (::navController.isInitialized) {
            try {
                navController.handleDeepLink(intent)
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed to handle deep link ${intent.dataString}", t)
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
