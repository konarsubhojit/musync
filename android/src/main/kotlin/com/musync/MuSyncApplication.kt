package com.musync

import android.app.Application
import com.musync.logging.AppLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MuSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i(TAG, "MuSync application starting")
        installCrashHandler()
    }

    /**
     * Installs the crash handler, delegating construction to [buildCrashHandler] so
     * the handler logic can be exercised by unit tests independently of Android lifecycle.
     */
    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(
            buildCrashHandler(Thread.getDefaultUncaughtExceptionHandler()),
        )
    }

    companion object {
        internal const val TAG = "MuSyncApplication"

        /**
         * Builds a [Thread.UncaughtExceptionHandler] that logs crashes via [AppLogger]
         * and then delegates to [previous].
         *
         * Extracted from [installCrashHandler] so tests can exercise the real
         * implementation without duplicating the handler logic.
         *
         * The [AppLogger] call is wrapped in a `try/catch` so that any logger failure
         * (e.g. an IO error) never prevents [previous] from being invoked—the original
         * crash is always surfaced.
         */
        internal fun buildCrashHandler(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
            Thread.UncaughtExceptionHandler { thread, throwable ->
                try {
                    AppLogger.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
                } catch (_: Throwable) {
                    // Defensive: if AppLogger itself throws, ignore so the original
                    // crash is still surfaced to the previous handler below.
                }
                previous?.uncaughtException(thread, throwable)
            }
    }
}
