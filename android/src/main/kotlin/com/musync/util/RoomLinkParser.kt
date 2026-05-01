package com.musync.util

/**
 * Helpers for parsing a MuSync room identifier from either a raw room ID
 * or a full invite link of the form `https://listen.yourdomain.com/room/<id>`.
 */
object RoomLinkParser {
    /** Matches the trailing `/room/<id>` segment of an invite link. */
    private val LINK_PATTERN = Regex("""/room/([A-Za-z0-9_-]+)""")

    /** Allowed characters and shape for a bare room code/UUID. */
    private val BARE_PATTERN = Regex("""^[A-Za-z0-9_-]{4,}$""")

    /**
     * Returns the room ID extracted from [input], or `null` if [input] is empty
     * or doesn't look like a valid invite link/room code.
     */
    fun extractRoomId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        LINK_PATTERN.find(trimmed)?.let { return it.groupValues[1] }
        if (BARE_PATTERN.matches(trimmed)) return trimmed
        return null
    }
}
