package com.musync.playback

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit-level coverage for [MediaPlaybackController] focused on the parts
 * that don't require a real Android [android.content.Context] (those need
 * an instrumented test).  This locks in the dispatch + state-publishing
 * contract relied on by [MediaPlaybackService] and the player surface.
 */
class MediaPlaybackControllerTest {
    @Test
    fun `dispatch routes to listener and clear stops further routing`() {
        val controller = MediaPlaybackController(appContext = mockk(relaxed = true))
        val recorded = mutableListOf<String>()
        val listener =
            object : MediaPlaybackController.Listener {
                override fun onPlay() {
                    recorded += "play"
                }

                override fun onPause() {
                    recorded += "pause"
                }

                override fun onSkipNext() {
                    recorded += "skip"
                }

                override fun onStop() {
                    recorded += "stop"
                }
            }
        controller.setListener(listener)

        controller.dispatchPlay()
        controller.dispatchPause()
        controller.dispatchSkipNext()
        controller.dispatchStop()
        assertEquals(listOf("play", "pause", "skip", "stop"), recorded)

        controller.clearListener(listener)
        controller.dispatchPlay()
        // No new entries after clear.
        assertEquals(listOf("play", "pause", "skip", "stop"), recorded)
    }

    @Test
    fun `clearListener is identity-scoped so a stale tear-down cannot evict the new listener`() {
        val controller = MediaPlaybackController(appContext = mockk(relaxed = true))
        val older =
            object : MediaPlaybackController.Listener {
                override fun onPlay() = Unit

                override fun onPause() = Unit

                override fun onSkipNext() = Unit

                override fun onStop() = Unit
            }
        var newerCalls = 0
        val newer =
            object : MediaPlaybackController.Listener {
                override fun onPlay() {
                    newerCalls++
                }

                override fun onPause() = Unit

                override fun onSkipNext() = Unit

                override fun onStop() = Unit
            }
        controller.setListener(older)
        controller.setListener(newer)

        // A delayed dispose from the older surface must not detach the newer one.
        controller.clearListener(older)
        controller.dispatchPlay()
        assertEquals(1, newerCalls)
    }

    @Test
    fun `stop clears all published state`() {
        val controller = MediaPlaybackController(appContext = mockk(relaxed = true))
        controller.updateTrack(
            MediaPlaybackController.TrackInfo(
                id = "abc",
                title = "Hello",
                artist = "World",
                durationMs = 1_000L,
            ),
        )
        controller.updatePlaybackState(isPlaying = true, positionMs = 500L)
        controller.updateHasNext(true)

        controller.stop()

        assertNull(controller.track.value)
        assertEquals(false, controller.isPlaying.value)
        assertEquals(0L, controller.positionMs.value)
        assertEquals(false, controller.hasNext.value)
    }
}
