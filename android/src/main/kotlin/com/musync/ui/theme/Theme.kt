package com.musync.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
    darkColorScheme(
        primary = MuSyncPrimary,
        secondary = Purple80,
        tertiary = Pink80,
        background = MuSyncBackground,
        surface = MuSyncSurface,
        onPrimary = MuSyncOnPrimary,
        onBackground = MuSyncOnBackground,
        onSurface = MuSyncOnSurface,
        error = MuSyncError,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = MuSyncPrimary,
        secondary = PurpleGrey40,
        tertiary = Pink40,
        background = MuSyncLightBackground,
        surface = MuSyncLightSurface,
        onPrimary = MuSyncOnPrimary,
        onBackground = MuSyncLightOnBackground,
        onSurface = MuSyncLightOnSurface,
        // Use a darker secondary text colour than Material's default so
        // small captions / hints meet WCAG AA contrast in light mode (#51).
        onSurfaceVariant = MuSyncLightOnSurfaceVariant,
        error = MuSyncError,
    )

@Composable
fun MuSyncTheme(
    // Persisted from Settings. Defaults to dark to preserve MuSync's
    // brand style for existing users.
    darkTheme: Boolean = true,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MuSyncTypography,
        content = content,
    )
}
