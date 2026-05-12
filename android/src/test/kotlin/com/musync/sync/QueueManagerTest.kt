package com.musync.sync

import com.musync.data.model.Track
import com.musync.data.repository.MusicRepository
import io.mockk.mockk
import io.socket.client.Socket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [QueueManager.parseQueue].
 *
 * The socket is mocked so these tests run on the JVM without an Android runtime.
 * A [FakeMusicRepository] is used to capture [MusicRepository.updateQueue] calls.
 */
class QueueManagerTest {
    private lateinit var fakeRepository: FakeMusicRepository
    private lateinit var sut: QueueManager

    @Before
    fun setUp() {
        fakeRepository = FakeMusicRepository()
        sut = QueueManager(socket = mockk<Socket>(relaxed = true), musicRepository = fakeRepository)
    }

    // ── parseQueue ────────────────────────────────────────────────────────────

    @Test
    fun `parseQueue returns empty list for empty array`() {
        val result = sut.parseQueue(JSONArray())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseQueue parses a single valid track`() {
        val json =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("id", "1")
                        put("title", "Me at the zoo")
                        put("artist", "jawed")
                        put("youtubeVideoId", "jNQXAC9IVRw")
                        put("durationMs", 19000L)
                    },
                )
            }

        val result = sut.parseQueue(json)

        assertEquals(1, result.size)
        with(result[0]) {
            assertEquals("1", id)
            assertEquals("Me at the zoo", title)
            assertEquals("jawed", artist)
            assertEquals("jNQXAC9IVRw", youtubeVideoId)
            assertEquals(19000L, durationMs)
        }
    }

    @Test
    fun `parseQueue parses multiple tracks in order`() {
        val json =
            JSONArray().apply {
                put(trackJson("1", "Song A", "Artist A", "vid1", 60_000L))
                put(trackJson("2", "Song B", "Artist B", "vid2", 120_000L))
                put(trackJson("3", "Song C", "Artist C", "vid3", 180_000L))
            }

        val result = sut.parseQueue(json)

        assertEquals(3, result.size)
        assertEquals("1", result[0].id)
        assertEquals("2", result[1].id)
        assertEquals("3", result[2].id)
    }

    @Test
    fun `parseQueue falls back to id when youtubeVideoId is absent (server-normalised queue)`() {
        // The server strips youtubeVideoId and re-broadcasts only {id, title}.
        // The client must treat id as the YouTube video ID in that case.
        val json =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("id", "jNQXAC9IVRw")
                        put("title", "Me at the zoo")
                        // youtubeVideoId deliberately omitted to simulate server-normalised payload
                    },
                )
            }

        val result = sut.parseQueue(json)

        assertEquals(1, result.size)
        with(result[0]) {
            assertEquals("jNQXAC9IVRw", id)
            assertEquals("jNQXAC9IVRw", youtubeVideoId) // falls back to id
            assertEquals("Me at the zoo", title)
        }
    }

    @Test
    fun `parseQueue skips entries with empty id`() {
        val json =
            JSONArray().apply {
                put(trackJson("", "No Id", "Artist", "vid1", 60_000L))
                put(trackJson("2", "Valid", "Artist", "vid2", 60_000L))
            }

        val result = sut.parseQueue(json)

        assertEquals(1, result.size)
        assertEquals("2", result[0].id)
    }

    @Test
    fun `parseQueue uses explicit youtubeVideoId when present even if id differs`() {
        // Full track payload from the host (before server normalisation)
        val json =
            JSONArray().apply {
                put(trackJson("uuid-123", "Song", "Artist", "jNQXAC9IVRw", 60_000L))
            }

        val result = sut.parseQueue(json)

        assertEquals(1, result.size)
        assertEquals("uuid-123", result[0].id)
        assertEquals("jNQXAC9IVRw", result[0].youtubeVideoId)
    }

    @Test
    fun `parseQueue skips non-object array elements`() {
        val json =
            JSONArray().apply {
                put("not-an-object")
                put(trackJson("1", "Valid", "Artist", "vid1", 60_000L))
            }

        val result = sut.parseQueue(json)

        assertEquals(1, result.size)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun trackJson(
        id: String,
        title: String,
        artist: String,
        youtubeVideoId: String,
        durationMs: Long,
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("title", title)
            put("artist", artist)
            put("youtubeVideoId", youtubeVideoId)
            put("durationMs", durationMs)
        }

    private class FakeMusicRepository : MusicRepository {
        var lastQueue: List<Track>? = null

        override val currentTrack: Flow<Track?> = MutableStateFlow(null)

        override val queue: Flow<List<Track>> = MutableStateFlow(emptyList())

        override fun updateQueue(tracks: List<Track>) {
            lastQueue = tracks
        }

        override fun addToQueue(track: Track) = Unit
    }
}
