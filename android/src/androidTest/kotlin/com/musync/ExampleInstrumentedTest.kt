package com.musync

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import javax.inject.Inject
import com.musync.data.repository.MusicRepository

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var musicRepository: MusicRepository

    @Test
    fun useAppContext() {
        hiltRule.inject()
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.musync", appContext.packageName)
    }

    @Test
    fun hiltGraphResolvesAtStartup() {
        hiltRule.inject()
        assertNotNull(musicRepository)
    }
}

