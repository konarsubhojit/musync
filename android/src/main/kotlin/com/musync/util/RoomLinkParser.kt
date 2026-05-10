package com.musync.util

import java.net.URI
import java.net.URISyntaxException

/**
 * Helpers for parsing a MuSync room identifier from either a raw room ID
 * or a full invite link of the form `https://listen.yourdomain.com/room/<id>`.
 */
object RoomLinkParser {
    /** Matches the trailing `/room/<id>` segment of an invite link. */
    private val LINK_PATTERN = Regex("""/room/([A-Za-z0-9_-]+)""")

    /** Allowed characters and shape for a bare room code/UUID. */
    private val BARE_PATTERN = Regex("""^[A-Za-z0-9_-]{4,}$""")

    /** Matches an http(s) URL anywhere in a string. */
    private val URL_PATTERN = Regex("""https?://\S+""")

    /**
     * Returns the room ID extracted from [input], or `null` if [input] is empty
     * or doesn't look like a valid invite link/room code.
     *
     * Handles:
     * - Free-text prefixes (e.g. `"Join my MuSync room: https://…"`) by finding
     *   the first `http(s)://` URL in the input.
     * - Invite links without a `/room/` segment by falling back to the last
     *   non-empty path segment of the URL (e.g. `https://host/<uuid>`).
     * - Bare room codes and `/room/<id>` links (existing behaviour preserved).
     */
    fun extractRoomId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val urlMatch = URL_PATTERN.find(trimmed)
        val target = urlMatch?.value ?: trimmed

        // Try /room/<id> pattern first — preserves existing precedence.
        LINK_PATTERN.find(target)?.let { return it.groupValues[1] }

        if (urlMatch != null) {
            // Fall back to the last non-empty path segment of the URL.
            val lastSegment =
                try {
                    URI(target).path
                        ?.split("/")
                        ?.lastOrNull { it.isNotEmpty() }
                } catch (e: URISyntaxException) {
                    null
                }
            if (lastSegment != null && BARE_PATTERN.matches(lastSegment)) {
                return lastSegment
            }
            return null
        }

        // No URL found — fall through to bare-pattern check on the trimmed string.
        if (BARE_PATTERN.matches(trimmed)) return trimmed
        return null
    }
}
