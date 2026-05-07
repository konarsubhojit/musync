package com.musync.data.remote

import com.musync.BuildConfig
import com.musync.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RoomStatusChecker] implementation that calls
 * `GET <SERVER_URL>/room/:roomId/status` via OkHttp.
 */
@Singleton
class RoomStatusCheckerImpl
    @Inject
    constructor(
        private val okHttpClient: OkHttpClient,
    ) : RoomStatusChecker {
        override suspend fun getStatus(roomId: String): RoomStatus? =
            withContext(Dispatchers.IO) {
                try {
                    val url = "${BuildConfig.SERVER_URL}/room/$roomId/status"
                    val request = Request.Builder().url(url).get().build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        val body = response.body?.string() ?: return@withContext null
                        val json = JSONObject(body)
                        RoomStatus(
                            active = json.getBoolean("active"),
                            listenerCount = json.getInt("listenerCount"),
                        )
                    }
                } catch (e: IOException) {
                    AppLogger.w(TAG, "Network error fetching status for room $roomId: $e")
                    null
                } catch (e: JSONException) {
                    AppLogger.w(TAG, "Unexpected response shape for room $roomId: $e")
                    null
                }
            }

        private companion object {
            const val TAG = "RoomStatusCheckerImpl"
        }
    }
