package com.musync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlParserTest {
    @Test
    fun `bare 11-character id is returned as-is`() {
        assertEquals("jNQXAC9IVRw", YouTubeUrlParser.extractVideoId("jNQXAC9IVRw"))
    }

    @Test
    fun `watch URL is parsed`() {
        assertEquals(
            "jNQXAC9IVRw",
            YouTubeUrlParser.extractVideoId("https://www.youtube.com/watch?v=jNQXAC9IVRw"),
        )
    }

    @Test
    fun `short youtu_be URL is parsed`() {
        assertEquals(
            "jNQXAC9IVRw",
            YouTubeUrlParser.extractVideoId("https://youtu.be/jNQXAC9IVRw?si=abc"),
        )
    }

    @Test
    fun `embed URL is parsed`() {
        assertEquals(
            "jNQXAC9IVRw",
            YouTubeUrlParser.extractVideoId("https://www.youtube.com/embed/jNQXAC9IVRw"),
        )
    }

    @Test
    fun `shorts URL is parsed`() {
        assertEquals(
            "jNQXAC9IVRw",
            YouTubeUrlParser.extractVideoId("https://www.youtube.com/shorts/jNQXAC9IVRw"),
        )
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals("jNQXAC9IVRw", YouTubeUrlParser.extractVideoId("   jNQXAC9IVRw  "))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(YouTubeUrlParser.extractVideoId(""))
    }

    @Test
    fun `garbage input returns null`() {
        assertNull(YouTubeUrlParser.extractVideoId("not a youtube link"))
    }

    @Test
    fun `wrong-length id returns null`() {
        assertNull(YouTubeUrlParser.extractVideoId("abc123"))
    }

    @Test
    fun `thumbnailUrl returns expected format`() {
        assertEquals(
            "https://img.youtube.com/vi/jNQXAC9IVRw/hqdefault.jpg",
            YouTubeUrlParser.thumbnailUrl("jNQXAC9IVRw"),
        )
    }
}
