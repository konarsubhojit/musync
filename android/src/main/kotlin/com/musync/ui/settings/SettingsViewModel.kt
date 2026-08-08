package com.musync.ui.settings

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.R
import com.musync.data.remote.ServerConfig
import com.musync.data.repository.UserPreferencesRepository
import com.musync.logging.AppLogger
import com.musync.logging.LogExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drives [SettingsScreen]. Owns the asynchronous "Export logs" action so that
 * the I/O happens off the main thread and the UI can show progress / results.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val userPreferencesRepository: UserPreferencesRepository,
        private val serverConfig: ServerConfig,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        init {
            _uiState.update {
                it.copy(
                    activeServerUrl = serverConfig.baseUrl,
                    isVerboseLogging = AppLogger.isVerboseEnabled,
                    logDirectory = AppLogger.logLocation,
                )
            }
            viewModelScope.launch {
                userPreferencesRepository.darkTheme
                    .catch { error ->
                        AppLogger.w(TAG, "Failed to read theme preference", error)
                        _uiState.update { it.copy(message = string(R.string.settings_theme_load_failed)) }
                    }
                    .collect { enabled ->
                        _uiState.update { it.copy(isDarkTheme = enabled) }
                    }
            }
            viewModelScope.launch {
                val stored =
                    runCatching { userPreferencesRepository.serverUrl.first() }
                        .getOrElse { error ->
                            AppLogger.w(TAG, "Failed to read server URL preference", error)
                            serverConfig.baseUrl
                        }
                _uiState.update { it.copy(serverUrlInput = stored) }
            }
            viewModelScope.launch {
                userPreferencesRepository.verboseLogging
                    .catch { error -> AppLogger.w(TAG, "Failed to read verbose logging preference", error) }
                    .collect { enabled ->
                        AppLogger.setVerboseEnabled(enabled)
                        _uiState.update { it.copy(isVerboseLogging = enabled) }
                    }
            }
        }

        fun onVerboseLoggingToggled(enabled: Boolean) {
            val previous = _uiState.value.isVerboseLogging
            AppLogger.setVerboseEnabled(enabled)
            _uiState.update { it.copy(isVerboseLogging = enabled) }
            viewModelScope.launch {
                runCatching { userPreferencesRepository.saveVerboseLogging(enabled) }
                    .onFailure { error ->
                        AppLogger.w(TAG, "Failed to save verbose logging preference", error)
                        AppLogger.setVerboseEnabled(previous)
                        _uiState.update {
                            it.copy(
                                isVerboseLogging = previous,
                                message = string(R.string.settings_verbose_logging_save_failed),
                            )
                        }
                    }
            }
        }

        fun onServerUrlChanged(value: String) {
            _uiState.update { it.copy(serverUrlInput = value, serverUrlError = false) }
        }

        /** Persists the server URL. The new value is applied on the next app launch. */
        fun onServerUrlSaved() {
            val normalized = ServerConfig.normalize(_uiState.value.serverUrlInput)
            if (normalized == null) {
                _uiState.update {
                    it.copy(
                        serverUrlError = true,
                        message = string(R.string.settings_server_url_invalid),
                    )
                }
                return
            }
            viewModelScope.launch {
                runCatching { userPreferencesRepository.saveServerUrl(normalized) }
                    .onSuccess {
                        AppLogger.i(TAG, "Server URL saved: $normalized")
                        _uiState.update {
                            it.copy(
                                serverUrlInput = normalized,
                                serverUrlError = false,
                                message = string(R.string.settings_server_url_saved),
                            )
                        }
                    }
                    .onFailure { error ->
                        AppLogger.w(TAG, "Failed to save server URL", error)
                        _uiState.update { it.copy(message = string(R.string.settings_server_url_save_failed)) }
                    }
            }
        }

        fun onDarkThemeToggled(enabled: Boolean) {
            val previous = _uiState.value.isDarkTheme
            _uiState.update { it.copy(isDarkTheme = enabled) }
            viewModelScope.launch {
                runCatching { userPreferencesRepository.saveDarkTheme(enabled) }
                    .onFailure { error ->
                        AppLogger.w(TAG, "Failed to save theme preference", error)
                        _uiState.update {
                            it.copy(
                                isDarkTheme = previous,
                                message = string(R.string.settings_theme_save_failed),
                            )
                        }
                    }
            }
        }

        /** Called when the SAF picker returned a folder URI. */
        fun onFolderSelected(
            context: Context,
            treeUri: Uri,
        ) {
            if (_uiState.value.isExporting) return
            _uiState.update { it.copy(isExporting = true, message = null) }
            viewModelScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        LogExporter.exportLogs(context, treeUri)
                    }
                val message =
                    when (result) {
                        is LogExporter.Result.Success ->
                            "Exported ${result.fileNames.size} log file(s)."
                        LogExporter.Result.Empty ->
                            "There are no logs to export yet."
                        is LogExporter.Result.Failure -> {
                            AppLogger.w(TAG, "Export failed: ${result.message}")
                            "Export failed: ${result.message}"
                        }
                    }
                _uiState.update { it.copy(isExporting = false, message = message) }
            }
        }

        /** Called when the user dismissed the system folder picker without choosing one. */
        fun onFolderSelectionCancelled() {
            AppLogger.i(TAG, "Log export cancelled by user.")
            _uiState.update { it.copy(message = "Export cancelled.") }
        }

        /** Called once the [SettingsUiState.message] has been shown to the user. */
        fun onMessageShown() {
            _uiState.update { it.copy(message = null) }
        }

        private companion object {
            const val TAG = "SettingsViewModel"
        }

        private fun string(
            @StringRes resId: Int,
        ): String = appContext.getString(resId)
    }
