package com.musync.data.repository

import com.musync.BuildConfig
import com.musync.data.model.YouTubeSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeSearchRepositoryImpl
    @Inject
    constructor(
        private val okHttpClient: OkHttpClient,
    ) : YouTubeSearchRepository {
        override suspend fun search(query: String): Result<List<YouTubeSearchResult>> =
            withContext(Dispatchers.IO) {
                try {
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    val url = "${BuildConfig.SERVER_URL}/api/youtube/search?q=$encodedQuery"
                    val request = Request.Builder().url(url).get().build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
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
                                    val item = itemsArray.getJSONObject(i)
                                    add(
                                        YouTubeSearchResult(
                                            videoId = item.getString("videoId"),
                                            title = item.getString("title"),
                                            channelTitle = item.getString("channelTitle"),
                                            thumbnailUrl = item.getString("thumbnailUrl"),
                                        ),
                                    )
                                }
                            }
                        Result.success(results)
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
    }
