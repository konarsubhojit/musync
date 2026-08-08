package com.musync.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [AppLogger]. We bypass [AppLogger.init] (which needs an Android
 * [android.content.Context]) by pointing the logger at a temp directory directly.
 */
class AppLoggerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var logsDir: File

    @Before
    fun setUp() {
        logsDir = tempFolder.newFolder("logs")
        AppLogger.setLogsDirectoryForTesting(logsDir)
    }

    @After
    fun tearDown() {
        AppLogger.setLogsDirectoryForTesting(null)
    }

    @Test
    fun `info entries are written to the app log only`() {
        AppLogger.i("TestTag", "hello")

        val appLog = File(logsDir, "musync.log")
        val errorLog = File(logsDir, "musync-errors.log")
        assertTrue(appLog.exists())
        assertFalse(errorLog.exists())
        val content = appLog.readText()
        assertTrue(content.contains("I/TestTag"))
        assertTrue(content.contains(": hello"))
    }

    @Test
    fun `warn and error entries are also written to the error log`() {
        AppLogger.w("WTag", "watch out")
        AppLogger.e("ETag", "boom", IllegalStateException("kaboom"))

        val errorLog = File(logsDir, "musync-errors.log")
        assertTrue(errorLog.exists())
        val content = errorLog.readText()
        assertTrue(content.contains("W/WTag"))
        assertTrue(content.contains(": watch out"))
        assertTrue(content.contains("E/ETag"))
        assertTrue(content.contains(": boom"))
        assertTrue(content.contains("IllegalStateException"))
        assertTrue(content.contains("kaboom"))
    }

    @Test
    fun `appLogFile and errorLogFile expose paths inside the configured directory`() {
        val appLog = AppLogger.appLogFile()
        val errorLog = AppLogger.errorLogFile()
        assertNotNull(appLog)
        assertNotNull(errorLog)
        assertEquals(logsDir.absolutePath, appLog!!.parentFile!!.absolutePath)
        assertEquals("musync.log", appLog.name)
        assertEquals("musync-errors.log", errorLog!!.name)
    }

    @Test
    fun `verbose entries are only recorded when verbose logging is enabled`() {
        AppLogger.setVerboseEnabled(false)
        AppLogger.v("VTag") { "suppressed" }

        val appLog = File(logsDir, "musync.log")
        assertFalse(appLog.exists() && appLog.readText().contains("suppressed"))

        AppLogger.setVerboseEnabled(true)
        AppLogger.v("VTag") { "recorded" }

        assertTrue(appLog.readText().contains("recorded"))
        AppLogger.setVerboseEnabled(false)
    }
}
