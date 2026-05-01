package com.musync.ui.player

import androidx.lifecycle.SavedStateHandle
import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import com.musync.data.model.Track
import com.musync.data.repository.MusicRepository
import com.musync.data.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val sampleTrack =
        Track(
            id = "1",
            title = "Test Song",
            artist = "Test Artist",
            youtubeVideoId = "testVideoId",
            durationMs = 180_000,
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
    fun `initial state has empty videoId and is not playing`() =
        runTest {
            val viewModel = PlayerViewModel(SavedStateHandle(), FakeMusicRepository(), FakeSessionRepository())
            val state = viewModel.uiState.value
            assertEquals("", state.videoId)
            assertFalse(state.isPlaying)
        }

    @Test
    fun `init collects current track from repository and updates videoId`() =
        runTest {
            val repo = FakeMusicRepository(initialTrack = sampleTrack)
            val viewModel = PlayerViewModel(SavedStateHandle(), repo, FakeSessionRepository())
            advanceUntilIdle()
            assertEquals("testVideoId", viewModel.uiState.value.videoId)
            assertEquals("Test Song", viewModel.uiState.value.trackTitle)
        }

    @Test
    fun `onPlaybackStateChanged sets isPlaying to true`() =
        runTest {
            val viewModel = PlayerViewModel(SavedStateHandle(), FakeMusicRepository(), FakeSessionRepository())
            viewModel.onPlaybackStateChanged(isPlaying = true)
            assertTrue(viewModel.uiState.value.isPlaying)
        }

    @Test
    fun `onPlaybackStateChanged sets isPlaying to false`() =
        runTest {
            val viewModel = PlayerViewModel(SavedStateHandle(), FakeMusicRepository(), FakeSessionRepository())
            viewModel.onPlaybackStateChanged(isPlaying = true)
            viewModel.onPlaybackStateChanged(isPlaying = false)
            assertFalse(viewModel.uiState.value.isPlaying)
        }

    @Test
    fun `onPlaybackStateChanged sets isBuffering`() =
        runTest {
            val viewModel = PlayerViewModel(SavedStateHandle(), FakeMusicRepository(), FakeSessionRepository())
            viewModel.onPlaybackStateChanged(isPlaying = false, isBuffering = true)
            assertTrue(viewModel.uiState.value.isBuffering)
        }

    @Test
    fun `onCurrentSecond updates currentSecond state`() =
        runTest {
            val viewModel = PlayerViewModel(SavedStateHandle(), FakeMusicRepository(), FakeSessionRepository())
            viewModel.onCurrentSecond(42.5f)
            assertEquals(42.5f, viewModel.uiState.value.currentSecond)
        }

    @Test
    fun `onDurationReceived updates duration state`() =
        runTest {
            val viewModel = PlayerViewModel(SavedStateHandle(), FakeMusicRepository(), FakeSessionRepository())
            viewModel.onDurationReceived(180f)
            assertEquals(180f, viewModel.uiState.value.duration)
        }

    @Test
    fun `inviteLink is set to a non-empty URL when no roomId is provided`() =
        runTest {
            val viewModel = PlayerViewModel(SavedStateHandle(), FakeMusicRepository(), FakeSessionRepository())
            val link = viewModel.uiState.value.inviteLink
            assertTrue("inviteLink should be non-empty", link.isNotEmpty())
            assertTrue(
                "inviteLink should start with INVITE_LINK_BASE_URL",
                link.startsWith(PlayerViewModel.INVITE_LINK_BASE_URL),
            )
        }

    @Test
    fun `inviteLink includes the roomId when joining via deep link`() =
        runTest {
            val viewModel =
                PlayerViewModel(
                    SavedStateHandle(mapOf("roomId" to "room-deep")),
                    FakeMusicRepository(),
                    FakeSessionRepository(),
                )
            val link = viewModel.uiState.value.inviteLink
            assertTrue(
                "inviteLink should contain roomId",
                link.endsWith("room-deep"),
            )
        }

    @Test
    fun `onInviteLinkCopied sets inviteLinkCopied to true`() =
        runTest {
            val viewModel = PlayerViewModel(SavedStateHandle(), FakeMusicRepository(), FakeSessionRepository())
            viewModel.onInviteLinkCopied()
            assertTrue(viewModel.uiState.value.inviteLinkCopied)
        }

    @Test
    fun `inviteLinkCopied resets to false after feedback duration`() =
        runTest {
            val viewModel = PlayerViewModel(SavedStateHandle(), FakeMusicRepository(), FakeSessionRepository())
            viewModel.onInviteLinkCopied()
            assertTrue(viewModel.uiState.value.inviteLinkCopied)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.inviteLinkCopied)
        }

    @Test
    fun `joins session when roomId is provided via SavedStateHandle`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            PlayerViewModel(
                SavedStateHandle(mapOf("roomId" to "room-abc")),
                FakeMusicRepository(),
                sessionRepo,
            )
            val joined = sessionRepo.session.value
            assertNotNull(joined)
            assertEquals("room-abc", joined!!.sessionId)
        }

    @Test
    fun `does not join session when roomId is absent`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            PlayerViewModel(SavedStateHandle(), FakeMusicRepository(), sessionRepo)
            assertNull(sessionRepo.session.value)
        }

    @Test
    fun `does not join session when roomId is blank`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            PlayerViewModel(
                SavedStateHandle(mapOf("roomId" to "   ")),
                FakeMusicRepository(),
                sessionRepo,
            )
            assertNull(sessionRepo.session.value)
        }

    @Test
    fun `joined session from deep link has different hostId and localUserId (guest)`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            PlayerViewModel(
                SavedStateHandle(mapOf("roomId" to "room-xyz")),
                FakeMusicRepository(),
                sessionRepo,
            )
            val joined = sessionRepo.session.value
            assertNotNull(joined)
            assertEquals(PlayerViewModel.DEEP_LINK_HOST_ID_PLACEHOLDER, joined!!.hostId)
            assertNotEquals(joined.hostId, joined.localUserId)
        }

    // --- Fake repositories ---

    private class FakeMusicRepository(
        initialTrack: Track? = null,
    ) : MusicRepository {
        private val trackFlow = MutableStateFlow(initialTrack)

        override val currentTrack: Flow<Track?> = trackFlow

        override val queue: Flow<List<Track>> = MutableStateFlow(emptyList())

        override fun updateQueue(tracks: List<Track>) = Unit

        override fun addToQueue(track: Track) = Unit
    }

    private class FakeSessionRepository : SessionRepository {
        private val _session = MutableStateFlow<Session?>(null)
        override val session: StateFlow<Session?> = _session
        override val events: SharedFlow<SyncEvent> = MutableSharedFlow()

        override fun onPlayerStateChanged(state: PlayerState) = Unit

        override fun joinSession(session: Session) {
            _session.value = session
        }

        override fun leaveSession() {
            _session.value = null
        }
    }
}
