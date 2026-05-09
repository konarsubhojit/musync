package com.musync.ui.settings

import android.content.Context
import com.musync.R
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val context: Context =
        mockk {
            every { getString(R.string.settings_theme_load_failed) } returns "load-failed"
            every { getString(R.string.settings_theme_save_failed) } returns "save-failed"
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
            val viewModel = SettingsViewModel(context, repository)

            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.isDarkTheme)
        }

    @Test
    fun `onDarkThemeToggled updates state and persists preference`() =
        runTest {
            val repository = FakeUserPreferencesRepository(initialDarkTheme = true)
            val viewModel = SettingsViewModel(context, repository)

            viewModel.onDarkThemeToggled(false)
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.isDarkTheme)
            assertEquals(false, repository.savedDarkTheme)
        }

    @Test
    fun `onDarkThemeToggled reverts state when persistence fails`() =
        runTest {
            val repository = FakeUserPreferencesRepository(initialDarkTheme = true, failOnSave = true)
            val viewModel = SettingsViewModel(context, repository)

            viewModel.onDarkThemeToggled(false)
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.isDarkTheme)
            assertEquals("save-failed", viewModel.uiState.value.message)
        }

    private class FakeUserPreferencesRepository(
        initialDarkTheme: Boolean,
        private val failOnSave: Boolean = false,
    ) : UserPreferencesRepository {
        private val darkThemeFlow = MutableStateFlow(initialDarkTheme)
        var savedDarkTheme: Boolean? = null
            private set

        override val displayName: Flow<String> = flowOf("")
        override val darkTheme: Flow<Boolean> = darkThemeFlow

        override suspend fun saveDisplayName(name: String) = Unit

        override suspend fun saveDarkTheme(enabled: Boolean) {
            if (failOnSave) {
                throw IllegalStateException("boom")
            }
            savedDarkTheme = enabled
            darkThemeFlow.value = enabled
        }
    }
}
