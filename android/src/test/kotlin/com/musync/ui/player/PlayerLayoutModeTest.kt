package com.musync.ui.player

import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLayoutModeTest {
    @Test
    fun `uses tablet split layout for non-compact width`() {
        assertEquals(
            PlayerLayoutMode.TabletSplit,
            resolvePlayerLayoutMode(
                orientation = Configuration.ORIENTATION_LANDSCAPE,
                widthSizeClass = WindowWidthSizeClass.Medium,
                fullscreenEnabled = true,
            ),
        )
    }

    @Test
    fun `uses fullscreen for compact landscape when enabled`() {
        assertEquals(
            PlayerLayoutMode.Fullscreen,
            resolvePlayerLayoutMode(
                orientation = Configuration.ORIENTATION_LANDSCAPE,
                widthSizeClass = WindowWidthSizeClass.Compact,
                fullscreenEnabled = true,
            ),
        )
    }

    @Test
    fun `uses default layout when fullscreen disabled`() {
        assertEquals(
            PlayerLayoutMode.Default,
            resolvePlayerLayoutMode(
                orientation = Configuration.ORIENTATION_LANDSCAPE,
                widthSizeClass = WindowWidthSizeClass.Compact,
                fullscreenEnabled = false,
            ),
        )
    }
}
