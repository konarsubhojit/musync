package com.musync.data.repository

import android.content.Context
import com.musync.data.model.RecentRoom
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RecentRoomsRepository] backed by [android.content.SharedPreferences].
 *
 * Rooms are serialised as a JSON array and stored under a single preference
 * key.  The list is always kept sorted by [RecentRoom.lastJoinedAt] descending
 * so the UI can iterate in order without additional sorting.
 */
@Singleton
class RecentRoomsRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : RecentRoomsRepository {
        companion object {
            private const val PREFS_NAME = "musync_recent_rooms"
            private const val KEY_ROOMS = "rooms"
        }

        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        override fun getRecentRooms(): List<RecentRoom> {
            val json = prefs.getString(KEY_ROOMS, null) ?: return emptyList()
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    RecentRoom(
                        roomId = obj.getString("roomId"),
                        displayName = obj.getString("displayName"),
                        lastJoinedAt = obj.getLong("lastJoinedAt"),
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        override fun addOrUpdateRoom(
            roomId: String,
            displayName: String,
        ) {
            val rooms = getRecentRooms().toMutableList()
            rooms.removeAll { it.roomId == roomId }
            rooms.add(0, RecentRoom(roomId, displayName, System.currentTimeMillis()))
            val trimmed = rooms.take(RecentRoomsRepository.MAX_ROOMS)
            val array =
                JSONArray().also { arr ->
                    trimmed.forEach { room ->
                        arr.put(
                            JSONObject().apply {
                                put("roomId", room.roomId)
                                put("displayName", room.displayName)
                                put("lastJoinedAt", room.lastJoinedAt)
                            },
                        )
                    }
                }
            prefs.edit().putString(KEY_ROOMS, array.toString()).apply()
        }

        override fun clearHistory() {
            prefs.edit().remove(KEY_ROOMS).apply()
        }
    }
