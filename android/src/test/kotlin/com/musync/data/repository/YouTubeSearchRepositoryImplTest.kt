package com.musync.data.repository

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSearchRepositoryImplTest {
    @Test
    fun `search skips items without videoId and keeps remaining video items`() =
        runTest {
            val repository =
                buildRepository(
                    """
                    {
                      "items": [
                        {
                          "title": "GothamChess",
                          "channelTitle": "GothamChess",
                          "thumbnailUrl": "https://example.com/channel.jpg"
                        },
                        {
                          "videoId": "jNQXAC9IVRw",
                          "title": "Me at the zoo",
                          "channelTitle": "jawed",
                          "thumbnailUrl": "https://example.com/thumb.jpg"
                        }
                      ]
                    }
                    """.trimIndent(),
                )

            val result = repository.search("gothamchess")
            assertTrue(result.isSuccess)
            val items = result.getOrThrow()
            assertEquals(1, items.size)
            assertEquals("jNQXAC9IVRw", items[0].videoId)
            assertEquals("Me at the zoo", items[0].title)
        }

    @Test
    fun `search tolerates missing optional fields for video entries`() =
        runTest {
            val repository =
                buildRepository(
                    """
                    {
                      "items": [
                        {
                          "videoId": "jeqfNToHk38"
                        }
                      ]
                    }
                    """.trimIndent(),
                )

            val result = repository.search("test")
            assertTrue(result.isSuccess)
            val items = result.getOrThrow()
            assertEquals(1, items.size)
            assertEquals("jeqfNToHk38", items[0].videoId)
            assertEquals("", items[0].title)
            assertEquals("", items[0].channelTitle)
            assertEquals("", items[0].thumbnailUrl)
        }

    private fun buildRepository(responseBody: String): YouTubeSearchRepositoryImpl {
        val client =
            OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(responseBody.toResponseBody())
                            .build()
                    },
                ).build()
        return YouTubeSearchRepositoryImpl(client)
    }
}
