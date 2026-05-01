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
}
