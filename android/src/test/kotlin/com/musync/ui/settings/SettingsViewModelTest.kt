package com.musync.ui.settings

import android.content.Context
import com.musync.R
import com.musync.data.remote.ServerConfig
import com.musync.data.repository.UserPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val context: Context =
        mockk {
            every { getString(R.string.settings_theme_load_failed) } returns "load-failed"
            every { getString(R.string.settings_theme_save_failed) } returns "save-failed"
            every { getString(R.string.settings_server_url_saved) } returns "url-saved"
            every { getString(R.string.settings_server_url_save_failed) } returns "url-save-failed"
            every { getString(R.string.settings_server_url_invalid) } returns "url-invalid"
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init reflects persisted dark theme preference`() =
        runTest {
            val repository = FakeUserPreferencesRepository(initialDarkTheme = false)
            val viewModel = buildViewModel(repository)

            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.isDarkTheme)
        }

    @Test
    fun `onDarkThemeToggled updates state and persists preference`() =
        runTest {
            val repository = FakeUserPreferencesRepository(initialDarkTheme = true)
            val viewModel = buildViewModel(repository)

            viewModel.onDarkThemeToggled(false)
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.isDarkTheme)
            assertEquals(false, repository.savedDarkTheme)
        }

    @Test
    fun `onDarkThemeToggled reverts state when persistence fails`() =
        runTest {
            val repository = FakeUserPreferencesRepository(initialDarkTheme = true, failOnSave = true)
            val viewModel = buildViewModel(repository)

            viewModel.onDarkThemeToggled(false)
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.isDarkTheme)
            assertEquals("save-failed", viewModel.uiState.value.message)
        }

    @Test
    fun `onServerUrlSaved persists a normalised url`() =
        runTest {
            val repository = FakeUserPreferencesRepository(initialDarkTheme = true)
            val viewModel = buildViewModel(repository)

            viewModel.onServerUrlChanged("  http://192.168.1.10:3000/  ")
            viewModel.onServerUrlSaved()
            advanceUntilIdle()

            assertEquals("http://192.168.1.10:3000", repository.savedServerUrl)
            assertEquals("http://192.168.1.10:3000", viewModel.uiState.value.serverUrlInput)
            assertEquals(false, viewModel.uiState.value.serverUrlError)
            assertEquals("url-saved", viewModel.uiState.value.message)
        }

    @Test
    fun `onServerUrlSaved rejects a url without a scheme`() =
        runTest {
            val repository = FakeUserPreferencesRepository(initialDarkTheme = true)
            val viewModel = buildViewModel(repository)

            viewModel.onServerUrlChanged("192.168.1.10:3000")
            viewModel.onServerUrlSaved()
            advanceUntilIdle()

            assertNull(repository.savedServerUrl)
            assertEquals(true, viewModel.uiState.value.serverUrlError)
            assertEquals("url-invalid", viewModel.uiState.value.message)
        }

    private fun buildViewModel(repository: FakeUserPreferencesRepository): SettingsViewModel =
        SettingsViewModel(context, repository, ServerConfig(repository))

    private class FakeUserPreferencesRepository(
        initialDarkTheme: Boolean,
        private val failOnSave: Boolean = false,
    ) : UserPreferencesRepository {
        private val darkThemeFlow = MutableStateFlow(initialDarkTheme)
        private val serverUrlFlow = MutableStateFlow("http://localhost:3000")
        private val verboseLoggingFlow = MutableStateFlow(false)
        var savedDarkTheme: Boolean? = null
            private set
        var savedServerUrl: String? = null
            private set
        var savedVerboseLogging: Boolean? = null
            private set

        override val displayName: Flow<String> = flowOf("")
        override val darkTheme: Flow<Boolean> = darkThemeFlow
        override val serverUrl: Flow<String> = serverUrlFlow
        override val verboseLogging: Flow<Boolean> = verboseLoggingFlow

        override suspend fun saveDisplayName(name: String) = Unit

        override suspend fun saveDarkTheme(enabled: Boolean) {
            if (failOnSave) {
                throw IllegalStateException("boom")
            }
            savedDarkTheme = enabled
            darkThemeFlow.value = enabled
        }

        override suspend fun saveServerUrl(url: String) {
            savedServerUrl = url
            serverUrlFlow.value = url
        }

        override suspend fun saveVerboseLogging(enabled: Boolean) {
            savedVerboseLogging = enabled
            verboseLoggingFlow.value = enabled
        }
    }
}
