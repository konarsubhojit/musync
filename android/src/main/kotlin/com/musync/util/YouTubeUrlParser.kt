package com.musync.util

/**
 * Helpers for extracting a YouTube video ID from various accepted inputs:
 *  - Bare 11-character video IDs (e.g. `jNQXAC9IVRw`)
 *  - Full `https://www.youtube.com/watch?v=...` URLs
 *  - `youtu.be/<id>` short URLs
 *  - `youtube.com/embed/<id>` and `/shorts/<id>` URLs
 */
object YouTubeUrlParser {
    /** A valid YouTube video ID is 11 characters long, drawing from this alphabet. */
    private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

    /** Patterns that capture the ID portion of a typical YouTube URL. */
    private val URL_PATTERNS =
        listOf(
            Regex("""(?:v=|/v/|/embed/|/shorts/|youtu\.be/)([A-Za-z0-9_-]{11})"""),
        )

    /**
     * Returns a normalised 11-character YouTube video ID extracted from [input], or
     * `null` if [input] does not contain a recognisable video ID.
     */
    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed
        URL_PATTERNS.forEach { pattern ->
            pattern.find(trimmed)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** Returns the standard `https://img.youtube.com/vi/<id>/hqdefault.jpg` thumbnail URL. */
    fun thumbnailUrl(videoId: String): String = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
}
