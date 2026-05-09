package com.musync.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musync.data.repository.UserPreferencesRepository
import com.musync.logging.AppLogger
import com.musync.logging.LogExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                userPreferencesRepository.darkTheme
                    .catch { error ->
                        AppLogger.w(TAG, "Failed to read theme preference: ${error.message}")
                        _uiState.update { it.copy(message = "Couldn't load theme preference.") }
                    }
                    .collect { enabled ->
                        _uiState.update { it.copy(isDarkTheme = enabled) }
                    }
            }
        }

        fun onDarkThemeToggled(enabled: Boolean) {
            val previous = _uiState.value.isDarkTheme
            _uiState.update { it.copy(isDarkTheme = enabled) }
            viewModelScope.launch {
                runCatching { userPreferencesRepository.saveDarkTheme(enabled) }
                    .onFailure { error ->
                        AppLogger.w(TAG, "Failed to save theme preference: ${error.message}")
                        _uiState.update {
                            it.copy(
                                isDarkTheme = previous,
                                message = "Couldn't save theme preference.",
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
    }
