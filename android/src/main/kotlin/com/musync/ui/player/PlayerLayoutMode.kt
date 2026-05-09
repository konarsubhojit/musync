package com.musync.ui.player

import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass

internal enum class PlayerLayoutMode {
    Default,
    TabletSplit,
    Fullscreen,
}

internal fun resolvePlayerLayoutMode(
    orientation: Int,
    widthSizeClass: WindowWidthSizeClass,
    fullscreenEnabled: Boolean,
): PlayerLayoutMode {
    if (widthSizeClass != WindowWidthSizeClass.Compact) {
        return PlayerLayoutMode.TabletSplit
    }
    if (orientation == Configuration.ORIENTATION_LANDSCAPE && fullscreenEnabled) {
        return PlayerLayoutMode.Fullscreen
    }
    return PlayerLayoutMode.Default
}
