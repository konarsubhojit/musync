package com.musync.ui.player

import androidx.lifecycle.SavedStateHandle
import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import com.musync.data.model.Track
import com.musync.data.repository.MusicRepository
import com.musync.data.repository.SessionRepository
import com.musync.sync.PlaybackSyncReceiver
import com.musync.sync.SyncEmitter
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
            val viewModel = buildHostViewModel()
            val state = viewModel.uiState.value
            assertEquals("", state.videoId)
            assertFalse(state.isPlaying)
        }

    @Test
    fun `init collects current track from repository and updates videoId`() =
        runTest {
            val repo = FakeMusicRepository(initialTrack = sampleTrack)
            val viewModel = buildHostViewModel(musicRepository = repo)
            advanceUntilIdle()
            assertEquals("testVideoId", viewModel.uiState.value.videoId)
            assertEquals("Test Song", viewModel.uiState.value.trackTitle)
        }

    @Test
    fun `onPlaybackStateChanged sets isPlaying to true`() =
        runTest {
            val viewModel = buildHostViewModel()
            viewModel.onPlaybackStateChanged(isPlaying = true)
            assertTrue(viewModel.uiState.value.isPlaying)
            viewModel.onPlaybackStateChanged(isPlaying = false) // stop heartbeat before runTest finishes
        }

    @Test
    fun `onPlaybackStateChanged sets isPlaying to false`() =
        runTest {
            val viewModel = buildHostViewModel()
            viewModel.onPlaybackStateChanged(isPlaying = true)
            viewModel.onPlaybackStateChanged(isPlaying = false)
            assertFalse(viewModel.uiState.value.isPlaying)
        }

    @Test
    fun `onPlaybackStateChanged sets isBuffering`() =
        runTest {
            val viewModel = buildHostViewModel()
            viewModel.onPlaybackStateChanged(isPlaying = false, isBuffering = true)
            assertTrue(viewModel.uiState.value.isBuffering)
        }

    @Test
    fun `onCurrentSecond updates currentSecond state`() =
        runTest {
            val viewModel = buildHostViewModel()
            viewModel.onCurrentSecond(42.5f)
            assertEquals(42.5f, viewModel.uiState.value.currentSecond)
        }

    @Test
    fun `onDurationReceived updates duration state`() =
        runTest {
            val viewModel = buildHostViewModel()
            viewModel.onDurationReceived(180f)
            assertEquals(180f, viewModel.uiState.value.duration)
        }

    @Test
    fun `inviteLink is set to a non-empty URL when no roomId is provided`() =
        runTest {
            val viewModel = buildHostViewModel()
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
            val viewModel = buildViewModelWithRoomId(roomId = "room-deep")
            val link = viewModel.uiState.value.inviteLink
            assertTrue(
                "inviteLink should contain roomId",
                link.endsWith("room-deep"),
            )
        }

    @Test
    fun `onInviteLinkCopied sets inviteLinkCopied to true`() =
        runTest {
            val viewModel = buildHostViewModel()
            viewModel.onInviteLinkCopied()
            assertTrue(viewModel.uiState.value.inviteLinkCopied)
        }

    @Test
    fun `inviteLinkCopied resets to false after feedback duration`() =
        runTest {
            val viewModel = buildHostViewModel()
            viewModel.onInviteLinkCopied()
            assertTrue(viewModel.uiState.value.inviteLinkCopied)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.inviteLinkCopied)
        }

    @Test
    fun `joins session when roomId is provided via SavedStateHandle`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            buildViewModelWithRoomId(roomId = "room-abc", sessionRepository = sessionRepo)
            val joined = sessionRepo.session.value
            assertNotNull(joined)
            assertEquals("room-abc", joined!!.sessionId)
        }

    @Test
    fun `joins session as host when roomId is absent`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            buildHostViewModel(sessionRepository = sessionRepo)
            val joined = sessionRepo.session.value
            assertNotNull(joined)
            // Host: localUserId == hostId
            assertEquals(joined!!.localUserId, joined.hostId)
        }

    @Test
    fun `host mode sets isHost to true in uiState`() =
        runTest {
            val viewModel = buildHostViewModel()
            assertTrue(viewModel.uiState.value.isHost)
        }

    @Test
    fun `guest mode sets isHost to false in uiState`() =
        runTest {
            val viewModel = buildViewModelWithRoomId(roomId = "room-abc")
            assertFalse(viewModel.uiState.value.isHost)
        }

    @Test
    fun `joins session as host when roomId is blank`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            buildViewModelWithRoomId(roomId = "   ", sessionRepository = sessionRepo)
            // Blank roomId → treated as host mode; session is still joined
            val joined = sessionRepo.session.value
            assertNotNull(joined)
            assertEquals(joined!!.localUserId, joined.hostId)
        }

    @Test
    fun `joined session from deep link has different hostId and localUserId (guest)`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            buildViewModelWithRoomId(roomId = "room-xyz", sessionRepository = sessionRepo)
            val joined = sessionRepo.session.value
            assertNotNull(joined)
            assertEquals(PlayerViewModel.DEEP_LINK_HOST_ID_PLACEHOLDER, joined!!.hostId)
            assertNotEquals(joined.hostId, joined.localUserId)
        }

    @Test
    fun `onBackPressed shows leave confirm dialog`() =
        runTest {
            val viewModel = buildHostViewModel()
            assertFalse(viewModel.uiState.value.showLeaveConfirmDialog)
            viewModel.onBackPressed()
            assertTrue(viewModel.uiState.value.showLeaveConfirmDialog)
        }

    @Test
    fun `onLeaveRoomDismissed hides dialog`() =
        runTest {
            val viewModel = buildHostViewModel()
            viewModel.onBackPressed()
            viewModel.onLeaveRoomDismissed()
            assertFalse(viewModel.uiState.value.showLeaveConfirmDialog)
        }

    @Test
    fun `onLeaveRoomConfirmed calls leaveSession and triggers navigation`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            viewModel.onBackPressed()
            viewModel.onLeaveRoomConfirmed()
            assertFalse("Dialog should be dismissed", viewModel.uiState.value.showLeaveConfirmDialog)
            assertTrue("navigateBack should be true", viewModel.uiState.value.navigateBack)
            assertNull("Session should be cleared", sessionRepo.session.value)
        }

    @Test
    fun `onEndSessionForAllConfirmed calls endSession and triggers navigation`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            viewModel.onBackPressed()
            viewModel.onEndSessionForAllConfirmed()
            assertFalse("Dialog should be dismissed", viewModel.uiState.value.showLeaveConfirmDialog)
            assertTrue("navigateBack should be true", viewModel.uiState.value.navigateBack)
            assertTrue("endSession should have been called", sessionRepo.endSessionCalled)
        }

    @Test
    fun `onNavigatedBack resets navigateBack and roomClosedByHost flags`() =
        runTest {
            val viewModel = buildHostViewModel()
            viewModel.onLeaveRoomConfirmed()
            assertTrue(viewModel.uiState.value.navigateBack)
            viewModel.onNavigatedBack()
            assertFalse(viewModel.uiState.value.navigateBack)
            assertFalse(viewModel.uiState.value.roomClosedByHost)
        }

    @Test
    fun `RoomClosed event sets roomClosedByHost and navigateBack`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle() // Ensure the ViewModel's event-collection coroutine has started
            sessionRepo.emitRoomClosed()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.roomClosedByHost)
            assertTrue(viewModel.uiState.value.navigateBack)
        }

    // --- Host sync emission ---

    @Test
    fun `host emits PLAY when playback state changes to playing`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(syncEmitter = emitter)
            viewModel.onPlaybackStateChanged(isPlaying = true)
            verify { emitter.emitPlay(any(), any()) }
            viewModel.onPlaybackStateChanged(isPlaying = false) // stop heartbeat before runTest finishes
        }

    @Test
    fun `host emits PAUSE only after transitioning from PLAYING`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(syncEmitter = emitter)
            // Transition: PLAYING → PAUSED
            viewModel.onPlaybackStateChanged(isPlaying = true)
            viewModel.onPlaybackStateChanged(isPlaying = false)
            verify { emitter.emitPause(any(), any()) }
        }

    @Test
    fun `host does not emit PAUSE when buffering`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(syncEmitter = emitter)
            viewModel.onPlaybackStateChanged(isPlaying = true)
            viewModel.onPlaybackStateChanged(isPlaying = false, isBuffering = true)
            verify(exactly = 0) { emitter.emitPause(any(), any()) }
            viewModel.onPlaybackStateChanged(isPlaying = false) // cleanup
        }

    @Test
    fun `host does not emit PAUSE when video ends without prior PLAYING`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(syncEmitter = emitter)
            // Simulate ENDED/CUED/UNSTARTED arriving without a prior PLAYING callback
            // (e.g. player initialises in a non-playing state at startup).
            viewModel.onPlaybackStateChanged(isPlaying = false)
            verify(exactly = 0) { emitter.emitPause(any(), any()) }
        }

    @Test
    fun `host emits PAUSE exactly once when video ends after PLAYING`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(syncEmitter = emitter)
            // Simulate track playing then reaching ENDED.
            viewModel.onPlaybackStateChanged(isPlaying = true)
            viewModel.onPlaybackStateChanged(isPlaying = false) // ENDED or PAUSED
            // A repeat non-playing callback (e.g. CUED after ENDED) must not emit again.
            viewModel.onPlaybackStateChanged(isPlaying = false)
            verify(exactly = 1) { emitter.emitPause(any(), any()) }
        }

    @Test
    fun `host emits SEEK when onUserSeeked is called`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(syncEmitter = emitter)
            viewModel.onUserSeeked(positionMs = 15_000L)
            verify { emitter.emitSeek(any(), eq(15_000L)) }
        }

    @Test
    fun `host emits heartbeat after delay when playing`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(syncEmitter = emitter)
            viewModel.onPlaybackStateChanged(isPlaying = true)
            // Advance past one heartbeat interval, then stop the heartbeat loop.
            advanceTimeBy(PlayerViewModel.HEARTBEAT_INTERVAL_MS + 1)
            viewModel.onPlaybackStateChanged(isPlaying = false)
            verify(atLeast = 1) { emitter.emitHeartbeat(any(), any()) }
        }

    // --- Guest sync emission ---

    @Test
    fun `guest does not emit PLAY when playback state changes to playing`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildViewModelWithRoomId(roomId = "room-g", syncEmitter = emitter)
            viewModel.onPlaybackStateChanged(isPlaying = true)
            verify(exactly = 0) { emitter.emitPlay(any(), any()) }
        }

    @Test
    fun `guest does not emit PAUSE when playback state changes to paused`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildViewModelWithRoomId(roomId = "room-g", syncEmitter = emitter)
            viewModel.onPlaybackStateChanged(isPlaying = true)
            viewModel.onPlaybackStateChanged(isPlaying = false)
            verify(exactly = 0) { emitter.emitPause(any(), any()) }
        }

    @Test
    fun `guest does not emit SEEK when onUserSeeked is called`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildViewModelWithRoomId(roomId = "room-g", syncEmitter = emitter)
            viewModel.onUserSeeked(positionMs = 5_000L)
            verify(exactly = 0) { emitter.emitSeek(any(), any()) }
        }

    @Test
    fun `isHost is true when no deep-link roomId is provided`() =
        runTest {
            val viewModel = buildHostViewModel()
            assertTrue(viewModel.isHost)
        }

    @Test
    fun `isHost is false when a deep-link roomId is provided`() =
        runTest {
            val viewModel = buildViewModelWithRoomId(roomId = "room-xyz")
            assertFalse(viewModel.isHost)
        }

    @Test
    fun `guest starts PlaybackSyncReceiver listening on init`() =
        runTest {
            val receiver = mockk<PlaybackSyncReceiver>(relaxed = true)
            buildViewModelWithRoomId(roomId = "room-g", playbackSyncReceiver = receiver)
            verify { receiver.startListening() }
        }

    @Test
    fun `host does not start PlaybackSyncReceiver listening`() =
        runTest {
            val receiver = mockk<PlaybackSyncReceiver>(relaxed = true)
            buildHostViewModel(playbackSyncReceiver = receiver)
            verify(exactly = 0) { receiver.startListening() }
        }

    @Test
    fun `attachRemotePlayer registers callbacks on PlaybackSyncReceiver for guest`() =
        runTest {
            val receiver = mockk<PlaybackSyncReceiver>(relaxed = true)
            val viewModel = buildViewModelWithRoomId(roomId = "room-g", playbackSyncReceiver = receiver)
            viewModel.attachRemotePlayer(onPlay = {}, onPause = {}, onSeek = {})
            verify { receiver.attachPlayer(any(), any(), any()) }
        }

    @Test
    fun `attachRemotePlayer is a no-op for host`() =
        runTest {
            val receiver = mockk<PlaybackSyncReceiver>(relaxed = true)
            val viewModel = buildHostViewModel(playbackSyncReceiver = receiver)
            viewModel.attachRemotePlayer(onPlay = {}, onPause = {}, onSeek = {})
            verify(exactly = 0) { receiver.attachPlayer(any(), any(), any()) }
        }

    // --- Queue management ---

    @Test
    fun `onRemoveFromQueue removes track from queue and emits QUEUE_UPDATED`() =
        runTest {
            val track1 = sampleTrack
            val track2 = sampleTrack.copy(id = "2", title = "Track 2", youtubeVideoId = "vid2")
            val repo = FakeMusicRepository(initialQueue = listOf(track1, track2))
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(musicRepository = repo, syncEmitter = emitter)
            advanceUntilIdle()

            viewModel.onRemoveFromQueue(track1.id)
            advanceUntilIdle()

            assertEquals(listOf(track2), repo.currentQueueSnapshot())
            verify { emitter.emitQueueUpdated(any(), eq(listOf(track2))) }
        }

    @Test
    fun `onRemoveFromQueue is a no-op for guest`() =
        runTest {
            val track = sampleTrack
            val repo = FakeMusicRepository(initialQueue = listOf(track))
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildViewModelWithRoomId(roomId = "room-g", musicRepository = repo, syncEmitter = emitter)
            advanceUntilIdle()

            viewModel.onRemoveFromQueue(track.id)
            advanceUntilIdle()

            verify(exactly = 0) { emitter.emitQueueUpdated(any(), any()) }
        }

    @Test
    fun `onMoveQueueItem reorders queue and emits QUEUE_UPDATED`() =
        runTest {
            val track1 = sampleTrack
            val track2 = sampleTrack.copy(id = "2", title = "Track 2", youtubeVideoId = "vid2")
            val repo = FakeMusicRepository(initialQueue = listOf(track1, track2))
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(musicRepository = repo, syncEmitter = emitter)
            advanceUntilIdle()

            viewModel.onMoveQueueItem(fromIndex = 0, toIndex = 1)
            advanceUntilIdle()

            assertEquals(listOf(track2, track1), repo.currentQueueSnapshot())
            verify { emitter.emitQueueUpdated(any(), eq(listOf(track2, track1))) }
        }

    @Test
    fun `onMoveQueueItem is a no-op for out-of-bounds indices`() =
        runTest {
            val track = sampleTrack
            val repo = FakeMusicRepository(initialQueue = listOf(track))
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(musicRepository = repo, syncEmitter = emitter)
            advanceUntilIdle()

            viewModel.onMoveQueueItem(fromIndex = 0, toIndex = 5)
            advanceUntilIdle()

            verify(exactly = 0) { emitter.emitQueueUpdated(any(), any()) }
        }

    @Test
    fun `onSkipToNext loads next track from queue and emits QUEUE_UPDATED`() =
        runTest {
            val track1 = sampleTrack
            val track2 = sampleTrack.copy(id = "2", title = "Track 2", youtubeVideoId = "vid2")
            val repo = FakeMusicRepository(initialQueue = listOf(track1, track2))
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(musicRepository = repo, syncEmitter = emitter)
            advanceUntilIdle()

            viewModel.onSkipToNext()
            advanceUntilIdle()

            assertEquals(track1.youtubeVideoId, viewModel.uiState.value.videoId)
            assertEquals(listOf(track2), repo.currentQueueSnapshot())
            verify { emitter.emitQueueUpdated(any(), eq(listOf(track2))) }
        }

    @Test
    fun `onSkipToNext is a no-op when queue is empty`() =
        runTest {
            val repo = FakeMusicRepository()
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(musicRepository = repo, syncEmitter = emitter)
            advanceUntilIdle()

            viewModel.onSkipToNext()
            advanceUntilIdle()

            verify(exactly = 0) { emitter.emitQueueUpdated(any(), any()) }
        }

    @Test
    fun `PlayNext event triggers auto-advance to next queued track`() =
        runTest {
            val track1 = sampleTrack
            val track2 = sampleTrack.copy(id = "2", title = "Track 2", youtubeVideoId = "vid2")
            val repo = FakeMusicRepository(initialQueue = listOf(track1, track2))
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val sessionRepo = FakeSessionRepository()
            val viewModel =
                buildHostViewModel(musicRepository = repo, sessionRepository = sessionRepo, syncEmitter = emitter)
            advanceUntilIdle()

            sessionRepo.emitPlayNext()
            advanceUntilIdle()

            assertEquals(track1.youtubeVideoId, viewModel.uiState.value.videoId)
            assertEquals(listOf(track2), repo.currentQueueSnapshot())
        }

    @Test
    fun `PlayNext event is no-op when queue is empty`() =
        runTest {
            val repo = FakeMusicRepository()
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val sessionRepo = FakeSessionRepository()
            val viewModel =
                buildHostViewModel(musicRepository = repo, sessionRepository = sessionRepo, syncEmitter = emitter)
            advanceUntilIdle()

            sessionRepo.emitPlayNext()
            advanceUntilIdle()

            verify(exactly = 0) { emitter.emitQueueUpdated(any(), any()) }
        }

    // --- Helpers ---

    private fun buildHostViewModel(
        musicRepository: MusicRepository = FakeMusicRepository(),
        sessionRepository: SessionRepository = FakeSessionRepository(),
        syncEmitter: SyncEmitter = mockk(relaxed = true),
        playbackSyncReceiver: PlaybackSyncReceiver = mockk(relaxed = true),
    ) = PlayerViewModel(SavedStateHandle(), musicRepository, sessionRepository, syncEmitter, playbackSyncReceiver)

    /**
     * Builds a [PlayerViewModel] with the given [roomId] injected via [SavedStateHandle].
     * Note: if [roomId] is blank or empty, the ViewModel treats this as **host mode**.
     */
    private fun buildViewModelWithRoomId(
        roomId: String,
        musicRepository: MusicRepository = FakeMusicRepository(),
        sessionRepository: SessionRepository = FakeSessionRepository(),
        syncEmitter: SyncEmitter = mockk(relaxed = true),
        playbackSyncReceiver: PlaybackSyncReceiver = mockk(relaxed = true),
    ) = PlayerViewModel(
        SavedStateHandle(mapOf("roomId" to roomId)),
        musicRepository,
        sessionRepository,
        syncEmitter,
        playbackSyncReceiver,
    )

    // --- Fake repositories ---

    private class FakeMusicRepository(
        initialTrack: Track? = null,
        initialQueue: List<Track> = emptyList(),
    ) : MusicRepository {
        private val trackFlow = MutableStateFlow(initialTrack)
        private val queueFlow = MutableStateFlow(initialQueue)

        override val currentTrack: Flow<Track?> = trackFlow

        override val queue: Flow<List<Track>> = queueFlow

        override fun updateQueue(tracks: List<Track>) {
            queueFlow.value = tracks
        }

        override fun addToQueue(track: Track) {
            queueFlow.value = queueFlow.value + track
        }

        fun currentQueueSnapshot(): List<Track> = queueFlow.value
    }

    private class FakeSessionRepository : SessionRepository {
        private val _session = MutableStateFlow<Session?>(null)
        private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 8)
        override val session: StateFlow<Session?> = _session
        override val events: SharedFlow<SyncEvent> = _events

        var endSessionCalled = false

        override fun onPlayerStateChanged(state: PlayerState) = Unit

        override fun joinSession(session: Session) {
            _session.value = session
        }

        override fun leaveSession() {
            _session.value = null
        }

        override fun endSession() {
            endSessionCalled = true
        }

        fun emitRoomClosed() {
            _events.tryEmit(SyncEvent.RoomClosed)
        }

        fun emitPlayNext(sessionId: String = "test-session") {
            _events.tryEmit(SyncEvent.PlayNext(sessionId))
        }
    }
}
