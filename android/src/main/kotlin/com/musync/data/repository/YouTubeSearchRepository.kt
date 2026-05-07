package com.musync.data.repository

import com.musync.data.model.YouTubeSearchResult

interface YouTubeSearchRepository {
    /**
     * Searches YouTube for videos matching [query] via the server-side proxy.
     *
     * Returns a [Result] wrapping the list of [YouTubeSearchResult] items on
     * success, or a [Result] wrapping the failure cause on error.
     */
    suspend fun search(query: String): Result<List<YouTubeSearchResult>>
}
