package com.musync.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentRoomsRepositoryImplTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repository: RecentRoomsRepositoryImpl

    /** Simulates in-memory storage backing the mocked SharedPreferences. */
    private val storage = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        editor = mockk(relaxed = true)
        prefs = mockk()

        // Wire editor.putString to write into storage.
        val putKeySlot = slot<String>()
        val putValueSlot = slot<String>()
        every { editor.putString(capture(putKeySlot), capture(putValueSlot)) } answers {
            storage[putKeySlot.captured] = putValueSlot.captured
            editor
        }

        val removeKeySlot = slot<String>()
        every { editor.remove(capture(removeKeySlot)) } answers {
            storage.remove(removeKeySlot.captured)
            editor
        }
        every { editor.apply() } returns Unit

        // Wire prefs.getString to read from storage. Use any() for the nullable default arg.
        val getKeySlot = slot<String>()
        every { prefs.getString(capture(getKeySlot), any()) } answers {
            storage[getKeySlot.captured]
        }
        every { prefs.edit() } returns editor

        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs

        repository = RecentRoomsRepositoryImpl(context)
    }

    @Test
    fun `getRecentRooms returns empty list when no data stored`() {
        val rooms = repository.getRecentRooms()
        assertTrue(rooms.isEmpty())
    }

    @Test
    fun `addOrUpdateRoom persists a new room`() {
        repository.addOrUpdateRoom("room-1", "Room 1")
        val rooms = repository.getRecentRooms()
        assertEquals(1, rooms.size)
        assertEquals("room-1", rooms[0].roomId)
        assertEquals("Room 1", rooms[0].displayName)
    }

    @Test
    fun `addOrUpdateRoom moves existing room to top`() {
        repository.addOrUpdateRoom("room-1", "Room 1")
        repository.addOrUpdateRoom("room-2", "Room 2")
        // room-1 was first; add it again so it should move to top.
        repository.addOrUpdateRoom("room-1", "Room 1 Updated")

        val rooms = repository.getRecentRooms()
        assertEquals(2, rooms.size)
        assertEquals("room-1", rooms[0].roomId)
        assertEquals("Room 1 Updated", rooms[0].displayName)
        assertEquals("room-2", rooms[1].roomId)
    }

    @Test
    fun `addOrUpdateRoom trims list to MAX_ROOMS`() {
        repeat(RecentRoomsRepository.MAX_ROOMS + 5) { i ->
            repository.addOrUpdateRoom("room-$i", "Room $i")
        }
        assertEquals(RecentRoomsRepository.MAX_ROOMS, repository.getRecentRooms().size)
    }

    @Test
    fun `clearHistory removes all rooms`() {
        repository.addOrUpdateRoom("room-1", "Room 1")
        repository.clearHistory()
        assertTrue(repository.getRecentRooms().isEmpty())
    }

    @Test
    fun `getRecentRooms returns rooms in most-recent-first order`() {
        repository.addOrUpdateRoom("room-a", "A")
        repository.addOrUpdateRoom("room-b", "B")
        repository.addOrUpdateRoom("room-c", "C")

        val rooms = repository.getRecentRooms()
        // Each subsequent addOrUpdateRoom inserts at position 0 (newest first).
        assertEquals("room-c", rooms[0].roomId)
        assertEquals("room-b", rooms[1].roomId)
        assertEquals("room-a", rooms[2].roomId)
    }

    @Test
    fun `getRecentRooms returns empty list when stored JSON is malformed`() {
        storage["rooms"] = "not-valid-json"
        assertTrue(repository.getRecentRooms().isEmpty())
    }

    @Test
    fun `clearHistory calls editor remove and apply`() {
        repository.clearHistory()
        verify { editor.remove("rooms") }
        verify { editor.apply() }
    }
}
