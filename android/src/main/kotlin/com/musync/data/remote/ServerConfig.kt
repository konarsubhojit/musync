package com.musync.data.remote

import com.musync.BuildConfig
import com.musync.data.repository.UserPreferencesRepository
import com.musync.logging.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the backend base URL.
 *
 * The value is read once from DataStore (falling back to the compile-time
 * [BuildConfig.SERVER_URL]) and then cached for the lifetime of the process.
 * The Socket.IO client is created once from this value, so changing the URL in
 * Settings only takes effect after the app is restarted.
 */
@Singleton
class ServerConfig
    @Inject
    constructor(
        private val userPreferencesRepository: UserPreferencesRepository,
    ) {
        @Volatile
        private var cached: String? = null

        /** Base URL without a trailing slash, e.g. `http://192.168.1.20:3000`. */
        val baseUrl: String
            get() =
                cached ?: synchronized(this) {
                    cached ?: resolve().also { cached = it }
                }

        private fun resolve(): String {
            val stored =
                runCatching { runBlocking { userPreferencesRepository.serverUrl.first() } }
                    .onFailure { AppLogger.w(TAG, "Failed to read server URL preference", it) }
                    .getOrNull()
            val resolved = normalize(stored) ?: normalize(BuildConfig.SERVER_URL) ?: FALLBACK
            AppLogger.i(TAG, "Using server URL $resolved")
            return resolved
        }

        companion object {
            private const val TAG = "ServerConfig"
            private const val FALLBACK = "http://10.0.2.2:3000"

            /**
             * Validates and canonicalises a user-entered server URL.
             *
             * @return the trimmed URL without a trailing slash, or `null` when it is
             *   not an absolute `http`/`https` URL with a host.
             */
            fun normalize(raw: String?): String? {
                val trimmed = raw?.trim().orEmpty()
                if (trimmed.isEmpty()) return null
                val uri =
                    try {
                        URI(trimmed)
                    } catch (e: URISyntaxException) {
                        AppLogger.w(TAG, "Invalid server URL '$trimmed': ${e.message}")
                        return null
                    }
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return null
                if (uri.host.isNullOrBlank()) return null
                return trimmed.trimEnd('/')
            }
        }
    }
