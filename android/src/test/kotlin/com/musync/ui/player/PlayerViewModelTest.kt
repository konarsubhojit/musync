package com.musync.ui.player

import com.musync.data.model.Track
import com.musync.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val sampleTrack = Track(
        id = "1",
        title = "Test Song",
        artist = "Test Artist",
        youtubeVideoId = "testVideoId",
        durationMs = 180_000
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty videoId and is not playing`() = runTest {
        val viewModel = PlayerViewModel(FakeMusicRepository())
        val state = viewModel.uiState.value
        assertEquals("", state.videoId)
        assertFalse(state.isPlaying)
    }

    @Test
    fun `init collects current track from repository and updates videoId`() = runTest {
        val repo = FakeMusicRepository(initialTrack = sampleTrack)
        val viewModel = PlayerViewModel(repo)
        advanceUntilIdle()
        assertEquals("testVideoId", viewModel.uiState.value.videoId)
        assertEquals("Test Song", viewModel.uiState.value.trackTitle)
    }

    @Test
    fun `onPlaybackStateChanged sets isPlaying to true`() = runTest {
        val viewModel = PlayerViewModel(FakeMusicRepository())
        viewModel.onPlaybackStateChanged(isPlaying = true)
        assertTrue(viewModel.uiState.value.isPlaying)
    }

    @Test
    fun `onPlaybackStateChanged sets isPlaying to false`() = runTest {
        val viewModel = PlayerViewModel(FakeMusicRepository())
        viewModel.onPlaybackStateChanged(isPlaying = true)
        viewModel.onPlaybackStateChanged(isPlaying = false)
        assertFalse(viewModel.uiState.value.isPlaying)
    }

    @Test
    fun `onPlaybackStateChanged sets isBuffering`() = runTest {
        val viewModel = PlayerViewModel(FakeMusicRepository())
        viewModel.onPlaybackStateChanged(isPlaying = false, isBuffering = true)
        assertTrue(viewModel.uiState.value.isBuffering)
    }

    @Test
    fun `onCurrentSecond updates currentSecond state`() = runTest {
        val viewModel = PlayerViewModel(FakeMusicRepository())
        viewModel.onCurrentSecond(42.5f)
        assertEquals(42.5f, viewModel.uiState.value.currentSecond)
    }

    @Test
    fun `onDurationReceived updates duration state`() = runTest {
        val viewModel = PlayerViewModel(FakeMusicRepository())
        viewModel.onDurationReceived(180f)
        assertEquals(180f, viewModel.uiState.value.duration)
    }

    // --- Fake repository ---

    private class FakeMusicRepository(
        initialTrack: Track? = null
    ) : MusicRepository {
        private val trackFlow = MutableStateFlow(initialTrack)

        override fun getCurrentTrack(): Flow<Track?> = trackFlow
        override fun getQueue(): Flow<List<Track>> = MutableStateFlow(emptyList())
    }
}
