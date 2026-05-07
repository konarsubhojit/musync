package com.musync.ui.player

import androidx.lifecycle.SavedStateHandle
import com.musync.data.model.PlayerState
import com.musync.data.model.RecentRoom
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import com.musync.data.model.Track
import com.musync.data.model.YouTubeSearchResult
import com.musync.data.repository.MusicRepository
import com.musync.data.repository.RecentRoomsRepository
import com.musync.data.repository.SessionRepository
import com.musync.data.repository.YouTubeSearchRepository
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
import kotlinx.coroutines.test.runCurrent
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
            viewModel.onPlaybackStateChanged(isPlaying = false)
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
            assertTrue("inviteLink should contain roomId", link.endsWith("room-deep"))
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
            advanceUntilIdle()
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
            viewModel.onPlaybackStateChanged(isPlaying = false)
        }

    @Test
    fun `host emits PAUSE only after transitioning from PLAYING`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(syncEmitter = emitter)
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
            viewModel.onPlaybackStateChanged(isPlaying = false)
        }

    @Test
    fun `host does not emit PAUSE when video ends without prior PLAYING`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(syncEmitter = emitter)
            viewModel.onPlaybackStateChanged(isPlaying = false)
            verify(exactly = 0) { emitter.emitPause(any(), any()) }
        }

    @Test
    fun `host emits PAUSE exactly once when video ends after PLAYING`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val viewModel = buildHostViewModel(syncEmitter = emitter)
            viewModel.onPlaybackStateChanged(isPlaying = true)
            viewModel.onPlaybackStateChanged(isPlaying = false)
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

    // --- Host transfer ---

    @Test
    fun `HostTransferred(isNowHost=true) promotes guest to host`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildViewModelWithRoomId(roomId = "room-transfer", sessionRepository = sessionRepo)
            assertFalse("Initially a guest", viewModel.isHost)
            assertFalse(viewModel.uiState.value.isHost)

            advanceUntilIdle()
            sessionRepo.emitHostTransferred(isNowHost = true)
            advanceUntilIdle()

            assertTrue("Should now be host", viewModel.isHost)
            assertTrue(viewModel.uiState.value.isHost)
        }

    @Test
    fun `HostTransferred(isNowHost=false) demotes host to guest`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            assertTrue("Initially the host", viewModel.isHost)
            assertTrue(viewModel.uiState.value.isHost)

            advanceUntilIdle()
            sessionRepo.emitHostTransferred(isNowHost = false)
            advanceUntilIdle()

            assertFalse("Should no longer be host", viewModel.isHost)
            assertFalse(viewModel.uiState.value.isHost)
        }

    @Test
    fun `onTransferHost calls transferHost on session repository`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            viewModel.onTransferHost("other-socket-id")
            assertNotNull("transferHost should have been called", sessionRepo.transferHostCalledWith)
            assertEquals("other-socket-id", sessionRepo.transferHostCalledWith?.second)
        }

    @Test
    fun `onTransferHost is a no-op when local user is a guest`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildViewModelWithRoomId(roomId = "room-g", sessionRepository = sessionRepo)
            viewModel.onTransferHost("other-socket-id")
            assertNull("transferHost must not be called for a guest", sessionRepo.transferHostCalledWith)
        }

    // --- Democratic mode ---

    @Test
    fun `DemocraticModeChanged updates isDemocraticMode in uiState`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            sessionRepo.emitDemocraticModeChanged(enabled = true)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isDemocraticMode)
        }

    @Test
    fun `guest can emit PLAY when democratic mode is enabled`() =
        runTest {
            val emitter = mockk<SyncEmitter>(relaxed = true)
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildViewModelWithRoomId(
                roomId = "room-g",
                sessionRepository = sessionRepo,
                syncEmitter = emitter,
            )
            advanceUntilIdle()
            sessionRepo.emitDemocraticModeChanged(enabled = true)
            advanceUntilIdle()
            viewModel.onPlaybackStateChanged(isPlaying = true)
            verify { emitter.emitPlay(any(), any()) }
        }

    @Test
    fun `onSetDemocraticMode calls repo and is host-only`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val host = buildHostViewModel(sessionRepository = sessionRepo)
            host.onSetDemocraticMode(true)
            assertNotNull(sessionRepo.setDemocraticModeCalledWith)
            assertTrue(sessionRepo.setDemocraticModeCalledWith!!.second)

            val guestRepo = FakeSessionRepository()
            val guest = buildViewModelWithRoomId(roomId = "room-g", sessionRepository = guestRepo)
            guest.onSetDemocraticMode(true)
            assertNull("Guest must not call setDemocraticMode", guestRepo.setDemocraticModeCalledWith)
        }

    // --- Auto-approve queue ---

    @Test
    fun `AutoApproveQueueChanged updates autoApproveQueue in uiState`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            sessionRepo.emitAutoApproveQueueChanged(enabled = false)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.autoApproveQueue)
        }

    @Test
    fun `onSetAutoApproveQueue calls repo and is host-only`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val host = buildHostViewModel(sessionRepository = sessionRepo)
            host.onSetAutoApproveQueue(false)
            assertNotNull(sessionRepo.setAutoApproveQueueCalledWith)
            assertFalse(sessionRepo.setAutoApproveQueueCalledWith!!.second)

            val guestRepo = FakeSessionRepository()
            val guest = buildViewModelWithRoomId(roomId = "room-g", sessionRepository = guestRepo)
            guest.onSetAutoApproveQueue(false)
            assertNull("Guest must not call setAutoApproveQueue", guestRepo.setAutoApproveQueueCalledWith)
        }

    // --- Queue add requests ---

    @Test
    fun `QueueAddRequest adds to pendingQueueRequests`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            sessionRepo.emitQueueAddRequest("track-1", "My Song")
            advanceUntilIdle()
            val pending = viewModel.uiState.value.pendingQueueRequests
            assertEquals(1, pending.size)
            assertEquals("track-1", pending[0].first)
            assertEquals("My Song", pending[0].second)
        }

    @Test
    fun `onApproveQueueAdd calls repo and removes from pendingQueueRequests`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            sessionRepo.emitQueueAddRequest("track-1", "My Song")
            advanceUntilIdle()
            viewModel.onApproveQueueAdd("track-1", "My Song")
            assertNotNull(sessionRepo.approveQueueAddCalledWith)
            assertEquals("track-1", sessionRepo.approveQueueAddCalledWith!!.second)
            assertTrue(viewModel.uiState.value.pendingQueueRequests.isEmpty())
        }

    @Test
    fun `onDismissQueueRequest removes from pendingQueueRequests without calling repo`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            sessionRepo.emitQueueAddRequest("track-1", "My Song")
            advanceUntilIdle()
            viewModel.onDismissQueueRequest("track-1")
            assertTrue(viewModel.uiState.value.pendingQueueRequests.isEmpty())
            assertNull(sessionRepo.approveQueueAddCalledWith)
        }

    // --- YouTube search ---

    private val sampleSearchResult =
        YouTubeSearchResult(
            videoId = "abc123",
            title = "A great song",
            channelTitle = "Great Channel",
            thumbnailUrl = "https://example.com/thumb.jpg",
        )

    @Test
    fun `onSearch sets isSearching while pending and clears on success`() =
        runTest {
            val repo = FakeYouTubeSearchRepository(result = Result.success(listOf(sampleSearchResult)))
            val viewModel = buildHostViewModel(youTubeSearchRepository = repo)
            viewModel.onAddToQueueInputChanged("great song")
            viewModel.onSearch()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertFalse("isSearching should be false after search completes", state.isSearching)
            assertFalse("searchError should be false on success", state.searchError)
            assertEquals(1, state.searchResults.size)
            assertEquals("abc123", state.searchResults[0].videoId)
        }

    @Test
    fun `onSearch sets searchError on failure`() =
        runTest {
            val repo = FakeYouTubeSearchRepository(result = Result.failure(Exception("Network error")))
            val viewModel = buildHostViewModel(youTubeSearchRepository = repo)
            viewModel.onAddToQueueInputChanged("some query")
            viewModel.onSearch()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertFalse("isSearching should be false after failure", state.isSearching)
            assertTrue("searchError should be true on failure", state.searchError)
            assertTrue("searchResults should be empty on failure", state.searchResults.isEmpty())
        }

    @Test
    fun `onSearch is a no-op when input is blank`() =
        runTest {
            val viewModel = buildHostViewModel()
            viewModel.onSearch()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSearching)
        }

    @Test
    fun `onSearchResultSelected adds track to queue and closes sheet`() =
        runTest {
            var addedTrack: Track? = null
            val musicRepo =
                object : MusicRepository {
                    override val currentTrack: Flow<Track?> = MutableStateFlow(null)
                    override val queue: Flow<List<Track>> = MutableStateFlow(emptyList())
                    override fun updateQueue(tracks: List<Track>) = Unit
                    override fun addToQueue(track: Track) { addedTrack = track }
                }
            val viewModel = buildHostViewModel(musicRepository = musicRepo)
            viewModel.onAddToQueueClicked()
            viewModel.onSearchResultSelected(sampleSearchResult)
            advanceUntilIdle()
            assertNotNull("A track should have been added to the queue", addedTrack)
            assertEquals("abc123", addedTrack!!.youtubeVideoId)
            assertEquals("A great song", addedTrack!!.title)
            assertEquals("Great Channel", addedTrack!!.artist)
            assertFalse("Sheet should be closed", viewModel.uiState.value.addToQueueSheetVisible)
            assertTrue("searchResults should be cleared", viewModel.uiState.value.searchResults.isEmpty())
        }

    @Test
    fun `onAddToQueueClicked resets search state`() =
        runTest {
            val repo = FakeYouTubeSearchRepository(result = Result.success(listOf(sampleSearchResult)))
            val viewModel = buildHostViewModel(youTubeSearchRepository = repo)
            viewModel.onAddToQueueInputChanged("test")
            viewModel.onSearch()
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.searchResults.size)
            viewModel.onAddToQueueClicked()
            assertTrue(viewModel.uiState.value.searchResults.isEmpty())
            assertFalse(viewModel.uiState.value.isSearching)
            assertFalse(viewModel.uiState.value.searchError)
        }

    @Test
    fun `onAddToQueueDismissed resets search state`() =
        runTest {
            val repo = FakeYouTubeSearchRepository(result = Result.success(listOf(sampleSearchResult)))
            val viewModel = buildHostViewModel(youTubeSearchRepository = repo)
            viewModel.onAddToQueueInputChanged("test")
            viewModel.onSearch()
            advanceUntilIdle()
            viewModel.onAddToQueueDismissed()
            assertTrue(viewModel.uiState.value.searchResults.isEmpty())
            assertFalse(viewModel.uiState.value.isSearching)
        }

    @Test
    fun `onAddToQueueConfirm resets search state on success`() =
        runTest {
            val repo = FakeYouTubeSearchRepository(result = Result.success(listOf(sampleSearchResult)))
            val viewModel = buildHostViewModel(youTubeSearchRepository = repo)
            viewModel.onAddToQueueInputChanged("https://youtu.be/jNQXAC9IVRw")
            viewModel.onSearch()
            advanceUntilIdle()
            viewModel.onAddToQueueConfirm()
            assertTrue(viewModel.uiState.value.searchResults.isEmpty())
            assertFalse(viewModel.uiState.value.addToQueueSheetVisible)
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

    // --- Participant count ---

    @Test
    fun `MembersSnapshot event sets participantCount`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            sessionRepo.emitMembersSnapshot(5)
            advanceUntilIdle()
            assertEquals(5, viewModel.uiState.value.participantCount)
        }

    @Test
    fun `PeerJoined event increments participantCount`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            val initialCount = viewModel.uiState.value.participantCount
            sessionRepo.emitPeerJoined()
            advanceUntilIdle()
            assertEquals(initialCount + 1, viewModel.uiState.value.participantCount)
        }

    @Test
    fun `PeerLeft event decrements participantCount but never below 1`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            sessionRepo.emitPeerLeft()
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.participantCount)
        }

    @Test
    fun `PeerJoined event sets presenceEvent to PeerJoined`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            sessionRepo.emitPeerJoined()
            runCurrent()
            assertEquals(PresenceEvent.PeerJoined, viewModel.uiState.value.presenceEvent)
        }

    @Test
    fun `PeerLeft event sets presenceEvent to PeerLeft`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            sessionRepo.emitPeerLeft()
            runCurrent()
            assertEquals(PresenceEvent.PeerLeft, viewModel.uiState.value.presenceEvent)
        }

    @Test
    fun `presenceEvent resets to null after PRESENCE_EVENT_DURATION_MS`() =
        runTest {
            val sessionRepo = FakeSessionRepository()
            val viewModel = buildHostViewModel(sessionRepository = sessionRepo)
            advanceUntilIdle()
            sessionRepo.emitPeerJoined()
            runCurrent()
            assertNotNull(viewModel.uiState.value.presenceEvent)
            advanceTimeBy(PlayerViewModel.PRESENCE_EVENT_DURATION_MS + 1)
            assertNull(viewModel.uiState.value.presenceEvent)
        }

    // --- Helpers ---

    private fun buildHostViewModel(
        musicRepository: MusicRepository = FakeMusicRepository(),
        sessionRepository: SessionRepository = FakeSessionRepository(),
        syncEmitter: SyncEmitter = mockk(relaxed = true),
        playbackSyncReceiver: PlaybackSyncReceiver = mockk(relaxed = true),
        youTubeSearchRepository: YouTubeSearchRepository = FakeYouTubeSearchRepository(),
        recentRoomsRepository: RecentRoomsRepository = FakeRecentRoomsRepository(),
    ) = PlayerViewModel(
        SavedStateHandle(),
        musicRepository,
        sessionRepository,
        syncEmitter,
        playbackSyncReceiver,
        youTubeSearchRepository,
        recentRoomsRepository,
    )

    private fun buildViewModelWithRoomId(
        roomId: String,
        musicRepository: MusicRepository = FakeMusicRepository(),
        sessionRepository: SessionRepository = FakeSessionRepository(),
        syncEmitter: SyncEmitter = mockk(relaxed = true),
        playbackSyncReceiver: PlaybackSyncReceiver = mockk(relaxed = true),
        youTubeSearchRepository: YouTubeSearchRepository = FakeYouTubeSearchRepository(),
        recentRoomsRepository: RecentRoomsRepository = FakeRecentRoomsRepository(),
    ) = PlayerViewModel(
        SavedStateHandle(mapOf("roomId" to roomId)),
        musicRepository,
        sessionRepository,
        syncEmitter,
        playbackSyncReceiver,
        youTubeSearchRepository,
        recentRoomsRepository,
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
        override fun updateQueue(tracks: List<Track>) { queueFlow.value = tracks }
        override fun addToQueue(track: Track) { queueFlow.value = queueFlow.value + track }
        fun currentQueueSnapshot(): List<Track> = queueFlow.value
    }

    private class FakeSessionRepository : SessionRepository {
        private val _session = MutableStateFlow<Session?>(null)
        private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 8)
        private val _chatMessages = MutableSharedFlow<com.musync.data.model.ChatMessage>(extraBufferCapacity = 64)
        private val _reactions = MutableSharedFlow<String>(extraBufferCapacity = 64)
        private val _typingUsers = MutableStateFlow<Set<String>>(emptySet())
        override val session: StateFlow<Session?> = _session
        override val events: SharedFlow<SyncEvent> = _events
        override val chatMessages: SharedFlow<com.musync.data.model.ChatMessage> = _chatMessages
        override val reactions: SharedFlow<String> = _reactions
        override val typingUsers: StateFlow<Set<String>> = _typingUsers

        var endSessionCalled = false
        var transferHostCalledWith: Pair<String, String>? = null
        var setDemocraticModeCalledWith: Pair<String, Boolean>? = null
        var setAutoApproveQueueCalledWith: Pair<String, Boolean>? = null
        var requestQueueAddCalledWith: Triple<String, String, String>? = null
        var approveQueueAddCalledWith: Triple<String, String, String>? = null

        override fun onPlayerStateChanged(state: PlayerState) = Unit
        override fun joinSession(session: Session) { _session.value = session }
        override fun leaveSession() { _session.value = null }
        override fun endSession() { endSessionCalled = true }
        override fun sendChatMessage(text: String, senderName: String) = Unit
        override fun sendReaction(emoji: String) = Unit
        override fun sendTyping(senderName: String) = Unit

        override fun transferHost(roomId: String, newHostSocketId: String) {
            transferHostCalledWith = Pair(roomId, newHostSocketId)
        }

        override fun setDemocraticMode(roomId: String, enabled: Boolean) {
            setDemocraticModeCalledWith = Pair(roomId, enabled)
        }

        override fun setAutoApproveQueue(roomId: String, enabled: Boolean) {
            setAutoApproveQueueCalledWith = Pair(roomId, enabled)
        }

        override fun requestQueueAdd(roomId: String, trackId: String, trackTitle: String) {
            requestQueueAddCalledWith = Triple(roomId, trackId, trackTitle)
        }

        override fun approveQueueAdd(roomId: String, trackId: String, trackTitle: String) {
            approveQueueAddCalledWith = Triple(roomId, trackId, trackTitle)
        }

        fun emitRoomClosed() { _events.tryEmit(SyncEvent.RoomClosed) }
        fun emitHostTransferred(isNowHost: Boolean) { _events.tryEmit(SyncEvent.HostTransferred(isNowHost)) }
        fun emitDemocraticModeChanged(enabled: Boolean) { _events.tryEmit(SyncEvent.DemocraticModeChanged(enabled)) }
        fun emitAutoApproveQueueChanged(enabled: Boolean) { _events.tryEmit(SyncEvent.AutoApproveQueueChanged(enabled)) }
        fun emitQueueAddRequest(trackId: String, trackTitle: String) { _events.tryEmit(SyncEvent.QueueAddRequest(trackId, trackTitle)) }
        fun emitPlayNext() { _events.tryEmit(SyncEvent.PlayNext("test-session")) }
        fun emitMembersSnapshot(count: Int) { _events.tryEmit(SyncEvent.MembersSnapshot(count)) }
        fun emitPeerJoined() { _events.tryEmit(SyncEvent.PeerJoined) }
        fun emitPeerLeft() { _events.tryEmit(SyncEvent.PeerLeft) }
    }

    private class FakeRecentRoomsRepository : RecentRoomsRepository {
        override fun getRecentRooms() = emptyList<RecentRoom>()
        override fun addOrUpdateRoom(roomId: String, displayName: String) = Unit
        override fun clearHistory() = Unit
    }

    private class FakeYouTubeSearchRepository(
        private val result: Result<List<YouTubeSearchResult>> = Result.success(emptyList()),
    ) : YouTubeSearchRepository {
        override suspend fun search(query: String): Result<List<YouTubeSearchResult>> = result
    }
}
