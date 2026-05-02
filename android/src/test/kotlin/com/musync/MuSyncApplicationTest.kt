package com.musync

import com.musync.logging.AppLogger
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies the crash-handler logic built by [MuSyncApplication.buildCrashHandler].
 *
 * Tests use the real handler implementation (no duplication) and assert that:
 *  1. The exception is written to AppLogger (and therefore to the on-disk log files).
 *  2. The previous handler is still invoked afterwards.
 *  3. If AppLogger itself throws, the previous handler is still invoked (defensive path).
 */
class MuSyncApplicationTest {
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
    fun `crash handler logs the exception and delegates to the previous handler`() {
        var previousHandlerCalled = false
        val previousHandler =
            Thread.UncaughtExceptionHandler { _, _ ->
                previousHandlerCalled = true
            }

        val handler = MuSyncApplication.buildCrashHandler(previousHandler)

        val exception = RuntimeException("test crash")
        handler.uncaughtException(Thread.currentThread(), exception)

        // Previous handler must have been called.
        assertTrue("Previous handler should have been called", previousHandlerCalled)

        // Exception details must appear in the error log.
        val errorLog = File(logsDir, "errors.log")
        assertTrue("errors.log should exist after a crash", errorLog.exists())
        val content = errorLog.readText()
        assertTrue("Log should contain the thread name prefix", content.contains("Uncaught exception on thread"))
        assertTrue("Log should contain the exception message", content.contains("test crash"))
    }

    @Test
    fun `crash handler still delegates to previous handler even when AppLogger throws`() {
        // Simulate AppLogger being unusable (no logs directory configured).
        AppLogger.setLogsDirectoryForTesting(null)

        var previousHandlerCalled = false
        val previousHandler =
            Thread.UncaughtExceptionHandler { _, _ ->
                previousHandlerCalled = true
            }

        val handler = MuSyncApplication.buildCrashHandler(previousHandler)

        handler.uncaughtException(Thread.currentThread(), RuntimeException("silent crash"))

        assertTrue("Previous handler should still be called even if AppLogger fails", previousHandlerCalled)
    }
}
