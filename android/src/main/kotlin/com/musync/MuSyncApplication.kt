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
     * Forwards uncaught exceptions to [AppLogger] before delegating to the previously
     * installed handler so the crash is still reported to the system / Logcat.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLogger.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            } catch (_: Throwable) {
                // Defensive: if AppLogger itself throws, ignore so the original
                // crash is still surfaced to the previous handler below.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val TAG = "MuSyncApplication"
    }
}
