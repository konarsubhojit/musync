package com.musync.ui.createroom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateRoomViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty input and no parsed video`() =
        runTest {
            val viewModel = CreateRoomViewModel()
            val state = viewModel.uiState.value
            assertEquals("", state.urlInput)
            assertNull(state.videoId)
            assertFalse(state.urlError)
            assertFalse(state.started)
            assertTrue(state.displayNamePlaceholder.startsWith("Listener #"))
        }

    @Test
    fun `entering a valid YouTube URL parses the video id`() =
        runTest {
            val viewModel = CreateRoomViewModel()
            viewModel.onUrlChanged("https://www.youtube.com/watch?v=jNQXAC9IVRw")
            val state = viewModel.uiState.value
            assertEquals("jNQXAC9IVRw", state.videoId)
            assertFalse(state.urlError)
        }

    @Test
    fun `entering an invalid URL flags an error`() =
        runTest {
            val viewModel = CreateRoomViewModel()
            viewModel.onUrlChanged("not a youtube link")
            val state = viewModel.uiState.value
            assertNull(state.videoId)
            assertTrue(state.urlError)
        }

    @Test
    fun `clearing the URL field clears the error`() =
        runTest {
            val viewModel = CreateRoomViewModel()
            viewModel.onUrlChanged("garbage")
            assertTrue(viewModel.uiState.value.urlError)
            viewModel.onUrlChanged("")
            assertFalse(viewModel.uiState.value.urlError)
        }

    @Test
    fun `onStartRoom is a no-op when no valid videoId is present`() =
        runTest {
            val viewModel = CreateRoomViewModel()
            viewModel.onStartRoom()
            assertFalse(viewModel.uiState.value.started)
            assertNull(viewModel.uiState.value.sessionId)
        }

    @Test
    fun `onStartRoom generates a sessionId when videoId is valid`() =
        runTest {
            val viewModel = CreateRoomViewModel()
            viewModel.onUrlChanged("jNQXAC9IVRw")
            viewModel.onStartRoom()
            val state = viewModel.uiState.value
            assertTrue(state.started)
            assertNotNull(state.sessionId)
            assertTrue(state.sessionId!!.isNotBlank())
        }

    @Test
    fun `onNavigationConsumed clears started flag`() =
        runTest {
            val viewModel = CreateRoomViewModel()
            viewModel.onUrlChanged("jNQXAC9IVRw")
            viewModel.onStartRoom()
            assertTrue(viewModel.uiState.value.started)
            viewModel.onNavigationConsumed()
            assertFalse(viewModel.uiState.value.started)
        }

    @Test
    fun `onDisplayNameChanged updates the display name`() =
        runTest {
            val viewModel = CreateRoomViewModel()
            viewModel.onDisplayNameChanged("Alice")
            assertEquals("Alice", viewModel.uiState.value.displayName)
        }
}
