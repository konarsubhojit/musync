package com.musync.data.repository

import com.musync.data.model.PlayerState
import com.musync.data.model.Session
import com.musync.data.model.SyncEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl @Inject constructor() : SessionRepository {

    private val _session = MutableStateFlow<Session?>(null)
    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 8)

    /**
     * Guards against duplicate [SyncEvent.PlayNext] emissions for the same
     * track.  Set to `true` on the first ENDED callback; reset to `false`
     * when a new track starts (PLAYING state) or when the session changes.
     */
    private val playNextEmitted = AtomicBoolean(false)

    override fun getSession(): Flow<Session?> = _session.asStateFlow()

    override fun getEvents(): Flow<SyncEvent> = _events.asSharedFlow()

    override fun onPlayerStateChanged(state: PlayerState) {
        when (state) {
            PlayerState.ENDED -> handleTrackEnded()
            PlayerState.PLAYING -> playNextEmitted.set(false)
            else -> Unit
        }
    }

    override fun joinSession(session: Session) {
        playNextEmitted.set(false)
        _session.value = session
    }

    override fun leaveSession() {
        playNextEmitted.set(false)
        _session.value = null
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    private fun handleTrackEnded() {
        val session = _session.value ?: return
        if (!isLocalUserHost(session)) return
        // compareAndSet returns true only for the first caller, preventing
        // duplicate PLAY_NEXT emissions even under concurrent callbacks.
        if (!playNextEmitted.compareAndSet(false, true)) return
        _events.tryEmit(SyncEvent.PlayNext(session.sessionId))
    }

    private fun isLocalUserHost(session: Session): Boolean =
        session.localUserId == session.hostId
}
