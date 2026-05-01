package com.musync.ui.settings

/**
 * UI state for [SettingsScreen].
 *
 * @property isExporting `true` while a folder has been picked and the log files are
 *  being copied; used to disable the export button.
 * @property message Transient user-facing message (success or error). Cleared once
 *  shown via [SettingsViewModel.onMessageShown].
 */
data class SettingsUiState(
    val isExporting: Boolean = false,
    val message: String? = null,
)
