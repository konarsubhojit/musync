package com.musync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.musync.data.repository.UserPreferencesRepository
import com.musync.logging.AppLogger
import com.musync.navigation.MuSyncNavGraph
import com.musync.ui.theme.MuSyncTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    private lateinit var navController: NavController

    /**
     * On API 26-28 the shared `Download/MuSync` log folder is a plain filesystem path
     * that requires a runtime grant. API 29+ reaches it through MediaStore instead, so
     * no prompt is shown there.
     */
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLogger.i(TAG, "Storage permission ${if (granted) "granted" else "denied"}")
            if (granted) AppLogger.attachPublicSink(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i(TAG, "MainActivity onCreate (intent=${intent?.dataString ?: "<none>"})")
        requestLegacyStoragePermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            val controller = rememberNavController()
            val darkThemeEnabled by userPreferencesRepository.darkTheme.collectAsState(initial = true)
            navController = controller
            MuSyncTheme(darkTheme = darkThemeEnabled) {
                MuSyncNavGraph(navController = controller)
            }
        }
    }

    /**
     * Requests `WRITE_EXTERNAL_STORAGE` on API 26-28 only, and only when it has not
     * already been granted, so the logger can write to the shared Download folder.
     */
    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        storagePermissionLauncher.launch(permission)
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
