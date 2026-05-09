package com.musync.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerScreenShareMessageTest {
    @Test
    fun `buildInviteShareMessage prefixes invite link with friendly message`() {
        val message = buildInviteShareMessage("Join my MuSync room:", "https://example.com/room/abc")

        assertEquals("Join my MuSync room: https://example.com/room/abc", message)
    }
}
