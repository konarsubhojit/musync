package com.musync.data.repository

import com.musync.BuildConfig
import com.musync.data.model.YouTubeSearchResult
import com.musync.data.model.YouTubeVideoInfo
import com.musync.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeSearchRepositoryImpl
    @Inject
    constructor(
        private val okHttpClient: OkHttpClient,
    ) : YouTubeSearchRepository {
        private val videoInfoCache = LinkedHashMap<String, YouTubeVideoInfo>(MAX_VIDEO_INFO_CACHE_SIZE, 0.75f, true)
        private val tag = "YouTubeSearchRepo"

        override suspend fun search(query: String): Result<List<YouTubeSearchResult>> =
            withContext(Dispatchers.IO) {
                try {
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    val url = "${BuildConfig.SERVER_URL}/api/youtube/search?q=$encodedQuery"
                    AppLogger.i(tag, "search request query=\"$query\"")
                    val request = Request.Builder().url(url).get().build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            AppLogger.w(tag, "search request failed status=${response.code} query=\"$query\"")
                            return@withContext Result.failure(
                                Exception("Search request failed with status ${response.code}"),
                            )
                        }
                        val body =
                            response.body?.string()
                                ?: return@withContext Result.failure(Exception("Empty response body"))
                        val json = JSONObject(body)
                        val itemsArray = json.getJSONArray("items")
                        val results =
                            buildList {
                                for (i in 0 until itemsArray.length()) {
                                    val item = itemsArray.optJSONObject(i) ?: continue
                                    val videoId = item.optString("videoId")
                                    if (videoId.isBlank()) continue
                                    add(
                                        YouTubeSearchResult(
                                            videoId = videoId,
                                            title = item.optString("title"),
                                            channelTitle = item.optString("channelTitle"),
                                            thumbnailUrl = item.optString("thumbnailUrl"),
                                        ),
                                    )
                                }
                            }
                        Result.success(results)
                    }
                } catch (e: Exception) {
                    AppLogger.w(tag, "search request threw for query=\"$query\"", e)
                    Result.failure(e)
                }
            }

        override suspend fun fetchVideoInfo(videoId: String): Result<YouTubeVideoInfo> =
            withContext(Dispatchers.IO) {
                val cached = getCachedVideoInfo(videoId)
                if (cached != null) {
                    return@withContext Result.success(cached)
                }
                try {
                    val url = "${BuildConfig.SERVER_URL}/api/youtube/video-info/$videoId"
                    AppLogger.i(tag, "video-info request videoId=$videoId")
                    val request = Request.Builder().url(url).get().build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            AppLogger.w(tag, "video-info request failed status=${response.code} videoId=$videoId")
                            return@withContext Result.failure(
                                Exception("Video info request failed with status ${response.code}"),
                            )
                        }
                        val body =
                            response.body?.string()
                                ?: return@withContext Result.failure(Exception("Empty response body"))
                        val item = JSONObject(body)
                        val info =
                            YouTubeVideoInfo(
                                videoId = item.getString("videoId"),
                                title = item.getString("title"),
                                channelTitle = item.optString("channelTitle"),
                            )
                        putCachedVideoInfo(videoId, info)
                        Result.success(info)
                    }
                } catch (e: Exception) {
                    AppLogger.w(tag, "video-info request threw for videoId=$videoId", e)
                    Result.failure(e)
                }
            }

        private fun getCachedVideoInfo(videoId: String): YouTubeVideoInfo? =
            synchronized(videoInfoCache) {
                videoInfoCache[videoId]
            }

        private fun putCachedVideoInfo(
            videoId: String,
            info: YouTubeVideoInfo,
        ) {
            synchronized(videoInfoCache) {
                videoInfoCache.remove(videoId)
                while (videoInfoCache.size >= MAX_VIDEO_INFO_CACHE_SIZE) {
                    val iterator = videoInfoCache.entries.iterator()
                    if (!iterator.hasNext()) break
                    iterator.next()
                    iterator.remove()
                }
                videoInfoCache[videoId] = info
            }
        }
    }

private const val MAX_VIDEO_INFO_CACHE_SIZE = 500
