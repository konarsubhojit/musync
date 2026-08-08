package com.musync.ui.settings

/**
 * UI state for [SettingsScreen].
 *
 * @property isExporting `true` while a folder has been picked and the log files are
 *  being copied; used to disable the export button.
 * @property message Transient user-facing message (success or error). Cleared once
 *  shown via [SettingsViewModel.onMessageShown].
 * @property serverUrlInput Current text of the developer server URL field.
 * @property activeServerUrl The URL the running process is actually connected with.
 *  Differs from [serverUrlInput] until the app is restarted after a save.
 * @property serverUrlError `true` when [serverUrlInput] is not a valid http(s) URL.
 * @property isVerboseLogging `true` when high-volume tracing is written to the logs.
 * @property logDirectory Absolute path of the folder holding the log files, shown so
 *  the user can find them with a file manager.
 */
data class SettingsUiState(
    val isExporting: Boolean = false,
    val isDarkTheme: Boolean = true,
    val message: String? = null,
    val serverUrlInput: String = "",
    val activeServerUrl: String = "",
    val serverUrlError: Boolean = false,
    val isVerboseLogging: Boolean = false,
    val logDirectory: String = "",
)
