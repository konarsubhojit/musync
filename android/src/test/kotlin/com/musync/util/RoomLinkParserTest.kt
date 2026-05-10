package com.musync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomLinkParserTest {
    @Test
    fun `bare room id is returned as-is`() {
        assertEquals("abcd1234", RoomLinkParser.extractRoomId("abcd1234"))
    }

    @Test
    fun `full invite link is parsed`() {
        assertEquals(
            "deadbeef-1234",
            RoomLinkParser.extractRoomId("https://listen.yourdomain.com/room/deadbeef-1234"),
        )
    }

    @Test
    fun `link with trailing query string is parsed`() {
        assertEquals(
            "abc-123",
            RoomLinkParser.extractRoomId("https://listen.yourdomain.com/room/abc-123?ref=share"),
        )
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals("abcd1234", RoomLinkParser.extractRoomId("  abcd1234  "))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(RoomLinkParser.extractRoomId(""))
    }

    @Test
    fun `too-short bare code returns null`() {
        assertNull(RoomLinkParser.extractRoomId("abc"))
    }

    @Test
    fun `garbage with spaces returns null`() {
        assertNull(RoomLinkParser.extractRoomId("not a link"))
    }

    // --- New cases for resilient parsing ---

    @Test
    fun `free-text prefix with room segment is parsed`() {
        assertEquals(
            "abc-123",
            RoomLinkParser.extractRoomId("Join my MuSync room: https://listen.example.com/room/abc-123"),
        )
    }

    @Test
    fun `free-text prefix with uuid path and port is parsed`() {
        assertEquals(
            "32a96396-305a-4bd1-8228-85ac4d8dcb31",
            RoomLinkParser.extractRoomId(
                "Join my MuSync room: https://musync-5mdf.onrender.com:3000/32a96396-305a-4bd1-8228-85ac4d8dcb31",
            ),
        )
    }

    @Test
    fun `bare uuid url without room segment is parsed`() {
        assertEquals(
            "deadbeef-1234",
            RoomLinkParser.extractRoomId("https://host.example.com/deadbeef-1234"),
        )
    }

    @Test
    fun `uuid url with query string strips query`() {
        assertEquals(
            "deadbeef-1234",
            RoomLinkParser.extractRoomId("https://host.example.com/deadbeef-1234?ref=share"),
        )
    }

    @Test
    fun `uuid url with fragment strips fragment`() {
        assertEquals(
            "deadbeef-1234",
            RoomLinkParser.extractRoomId("https://host.example.com/deadbeef-1234#frag"),
        )
    }

    @Test
    fun `room segment takes precedence over extra path components`() {
        assertEquals(
            "abc-123",
            RoomLinkParser.extractRoomId("https://host.example.com/room/abc-123/extra"),
        )
    }

    @Test
    fun `url with only root path returns null`() {
        assertNull(RoomLinkParser.extractRoomId("https://host.example.com/"))
    }

    @Test
    fun `url with too-short last segment returns null`() {
        assertNull(RoomLinkParser.extractRoomId("https://host.example.com/ab"))
    }

    @Test
    fun `free text without url returns null`() {
        assertNull(RoomLinkParser.extractRoomId("check this out: not a link at all"))
    }
}
