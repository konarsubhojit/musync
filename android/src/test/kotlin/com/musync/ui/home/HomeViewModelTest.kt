package com.musync.ui.home

import com.musync.data.model.Track
import com.musync.data.repository.MusicRepository
import com.musync.sync.QueueManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The view-model exposes its state via `stateIn(WhileSubscribed)` so the
     * upstream `combine` only runs while a collector is active.  In tests
     * we attach a background collector to keep the flow live.
     */
    private fun TestScope.subscribe(state: StateFlow<*>) {
        backgroundScope.launch { state.collect { } }
    }

    @Test
    fun `valid invite link is parsed and pendingJoinRoomId is set`() =
        runTest {
            val viewModel = newViewModel()
            subscribe(viewModel.uiState)
            viewModel.onJoinInputChanged("https://listen.yourdomain.com/room/abc-123")
            viewModel.onJoinConfirm()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals("abc-123", state.pendingJoinRoomId)
            assertFalse(state.joinError)
        }

    @Test
    fun `invalid invite link sets joinError`() =
        runTest {
            val viewModel = newViewModel()
            subscribe(viewModel.uiState)
            viewModel.onJoinInputChanged("not a link")
            viewModel.onJoinConfirm()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertNull(state.pendingJoinRoomId)
            assertTrue(state.joinError)
        }

    @Test
    fun `editing input after error clears the error`() =
        runTest {
            val viewModel = newViewModel()
            subscribe(viewModel.uiState)
            viewModel.onJoinInputChanged("bad")
            viewModel.onJoinConfirm()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.joinError)
            viewModel.onJoinInputChanged("better")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.joinError)
        }

    @Test
    fun `onJoinNavigationConsumed clears pending state and collapses the form`() =
        runTest {
            val viewModel = newViewModel()
            subscribe(viewModel.uiState)
            viewModel.onToggleJoinExpanded()
            viewModel.onJoinInputChanged("abcd1234")
            viewModel.onJoinConfirm()
            advanceUntilIdle()
            assertEquals("abcd1234", viewModel.uiState.value.pendingJoinRoomId)
            viewModel.onJoinNavigationConsumed()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertNull(state.pendingJoinRoomId)
            assertFalse(state.joinExpanded)
            assertEquals("", state.joinInput)
        }

    @Test
    fun `toggleJoinExpanded flips the flag`() =
        runTest {
            val viewModel = newViewModel()
            subscribe(viewModel.uiState)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.joinExpanded)
            viewModel.onToggleJoinExpanded()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.joinExpanded)
            viewModel.onToggleJoinExpanded()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.joinExpanded)
        }

    @Test
    fun `currentTrack and queue from repository propagate to uiState`() =
        runTest {
            val track = Track("1", "T", "A", "vid", 1L)
            val repo =
                fakeRepo(
                    initialTrack = track,
                    initialQueue = listOf(track),
                )
            val viewModel = newViewModel(repo = repo)
            subscribe(viewModel.uiState)
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(track, state.currentTrack)
            assertEquals(listOf(track), state.queue)
        }

    // --- Helpers ---

    @Suppress("UNUSED_PARAMETER")
    private fun unused(scope: CoroutineScope) = Unit

    private fun newViewModel(
        repo: MusicRepository = fakeRepo(),
        queueManager: QueueManager =
            mockk(relaxed = true) {
                every { startListening() } just runs
                every { stopListening() } just runs
            },
    ): HomeViewModel = HomeViewModel(repo, queueManager)

    private fun fakeRepo(
        initialTrack: Track? = null,
        initialQueue: List<Track> = emptyList(),
    ): MusicRepository =
        object : MusicRepository {
            override val currentTrack: Flow<Track?> = MutableStateFlow(initialTrack)
            override val queue: Flow<List<Track>> = MutableStateFlow(initialQueue)

            override fun updateQueue(tracks: List<Track>) = Unit

            override fun addToQueue(track: Track) = Unit
        }
}
