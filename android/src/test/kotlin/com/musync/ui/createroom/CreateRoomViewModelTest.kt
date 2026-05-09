package com.musync.ui.createroom

import com.musync.data.model.YouTubeSearchResult
import com.musync.data.model.YouTubeVideoInfo
import com.musync.data.repository.UserPreferencesRepository
import com.musync.data.repository.YouTubeSearchRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateRoomViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val userPrefs =
        mockk<UserPreferencesRepository>(relaxed = true) {
            every { displayName } returns flowOf("")
        }
    private val youTubeSearchRepository = FakeYouTubeSearchRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty input and no parsed video`() =
        runTest {
            val viewModel = buildViewModel()
            val state = viewModel.uiState.value
            assertEquals("", state.urlInput)
            assertNull(state.videoId)
            assertFalse(state.urlError)
            assertFalse(state.started)
            assertTrue(state.displayNamePlaceholder.startsWith("Listener #"))
        }

    @Test
    fun `entering a valid YouTube URL parses the video id`() =
        runTest {
            val viewModel = buildViewModel()
            viewModel.onUrlChanged("https://www.youtube.com/watch?v=jNQXAC9IVRw")
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals("jNQXAC9IVRw", state.videoId)
            assertFalse(state.urlError)
            assertEquals("Test title", state.videoTitle)
            assertEquals("Test channel", state.channelTitle)
        }

    @Test
    fun `entering an invalid URL flags an error`() =
        runTest {
            val viewModel = buildViewModel()
            viewModel.onUrlChanged("not a youtube link")
            val state = viewModel.uiState.value
            assertNull(state.videoId)
            assertTrue(state.urlError)
        }

    @Test
    fun `clearing the URL field clears the error`() =
        runTest {
            val viewModel = buildViewModel()
            viewModel.onUrlChanged("garbage")
            assertTrue(viewModel.uiState.value.urlError)
            viewModel.onUrlChanged("")
            assertFalse(viewModel.uiState.value.urlError)
        }

    @Test
    fun `onStartRoom is a no-op when no valid videoId is present`() =
        runTest {
            val viewModel = buildViewModel()
            viewModel.onStartRoom()
            assertFalse(viewModel.uiState.value.started)
            assertNull(viewModel.uiState.value.sessionId)
        }

    @Test
    fun `onStartRoom generates a sessionId when videoId is valid`() =
        runTest {
            val viewModel = buildViewModel()
            viewModel.onUrlChanged("jNQXAC9IVRw")
            advanceUntilIdle()
            viewModel.onStartRoom()
            val state = viewModel.uiState.value
            assertTrue(state.started)
            assertNotNull(state.sessionId)
            assertTrue(state.sessionId!!.isNotBlank())
        }

    @Test
    fun `onNavigationConsumed clears started flag`() =
        runTest {
            val viewModel = buildViewModel()
            viewModel.onUrlChanged("jNQXAC9IVRw")
            advanceUntilIdle()
            viewModel.onStartRoom()
            assertTrue(viewModel.uiState.value.started)
            viewModel.onNavigationConsumed()
            assertFalse(viewModel.uiState.value.started)
        }

    @Test
    fun `onDisplayNameChanged updates the display name`() =
        runTest {
            val viewModel = buildViewModel()
            viewModel.onDisplayNameChanged("Alice")
            assertEquals("Alice", viewModel.uiState.value.displayName)
        }

    @Test
    fun `saved display name is pre-filled on init`() =
        runTest {
            val prefs =
                mockk<UserPreferencesRepository>(relaxed = true) {
                    every { displayName } returns flowOf("Bob")
                }
            val viewModel = buildViewModel(userPreferencesRepository = prefs)
            advanceUntilIdle() // allow the init coroutine to complete
            assertEquals("Bob", viewModel.uiState.value.displayName)
        }

    @Test
    fun `saved display name does not overwrite user edits made before DataStore resolves`() =
        runTest {
            val prefs =
                mockk<UserPreferencesRepository>(relaxed = true) {
                    every { displayName } returns flowOf("Saved")
                }
            val viewModel = buildViewModel(userPreferencesRepository = prefs)
            // User types before DataStore resolves
            viewModel.onDisplayNameChanged("User Input")
            advanceUntilIdle() // DataStore resolves after user typed
            assertEquals("User Input", viewModel.uiState.value.displayName)
        }

    @Test
    fun `onStartRoom saves the display name to preferences`() =
        runTest {
            val prefs =
                mockk<UserPreferencesRepository>(relaxed = true) {
                    every { displayName } returns flowOf("")
                    coEvery { saveDisplayName(any()) } returns Unit
                }
            val viewModel = buildViewModel(userPreferencesRepository = prefs)
            viewModel.onUrlChanged("jNQXAC9IVRw")
            advanceUntilIdle()
            viewModel.onDisplayNameChanged("Carol")
            viewModel.onStartRoom()
            advanceUntilIdle()
            coVerify { prefs.saveDisplayName("Carol") }
        }

    @Test
    fun `onStartRoom trims and caps display name to 50 characters before saving`() =
        runTest {
            val prefs =
                mockk<UserPreferencesRepository>(relaxed = true) {
                    every { displayName } returns flowOf("")
                    coEvery { saveDisplayName(any()) } returns Unit
                }
            val viewModel = buildViewModel(userPreferencesRepository = prefs)
            viewModel.onUrlChanged("jNQXAC9IVRw")
            advanceUntilIdle()
            val longName = "A".repeat(60)
            viewModel.onDisplayNameChanged("  $longName  ")
            viewModel.onStartRoom()
            advanceUntilIdle()
            coVerify { prefs.saveDisplayName("A".repeat(50)) }
        }

    @Test
    fun `metadata fetch failure sets videoInfoError and keeps parsed videoId`() =
        runTest {
            val failingRepo = FakeYouTubeSearchRepository(Result.failure(Exception("boom")))
            val viewModel = buildViewModel(youTubeRepository = failingRepo)
            viewModel.onUrlChanged("https://youtu.be/jNQXAC9IVRw")
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals("jNQXAC9IVRw", state.videoId)
            assertTrue(state.videoInfoError)
            assertFalse(state.isFetchingVideoInfo)
            assertNull(state.videoTitle)
        }

    private fun buildViewModel(
        userPreferencesRepository: UserPreferencesRepository = userPrefs,
        youTubeRepository: YouTubeSearchRepository = youTubeSearchRepository,
    ): CreateRoomViewModel = CreateRoomViewModel(userPreferencesRepository, youTubeRepository)

    private class FakeYouTubeSearchRepository(
        private val result: Result<YouTubeVideoInfo> =
            Result.success(
                YouTubeVideoInfo(
                    videoId = "jNQXAC9IVRw",
                    title = "Test title",
                    channelTitle = "Test channel",
                ),
            ),
    ) : YouTubeSearchRepository {
        override suspend fun search(query: String): Result<List<YouTubeSearchResult>> = Result.success(emptyList())

        override suspend fun fetchVideoInfo(videoId: String): Result<YouTubeVideoInfo> = result
    }
}
