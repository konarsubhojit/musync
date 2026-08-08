package com.musync

import android.app.Application
import com.musync.data.repository.UserPreferencesRepository
import com.musync.logging.AppLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MuSyncApplication : Application() {
    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i(TAG, "MuSync application starting")
        applyVerboseLoggingPreference()
        installCrashHandler()
    }

    /**
     * Applies the persisted verbose-logging preference. Collected for the process
     * lifetime so a change made in Settings takes effect immediately, including for
     * components that are not themselves preference-aware.
     */
    private fun applyVerboseLoggingPreference() {
        applicationScope.launch {
            userPreferencesRepository.verboseLogging
                .catch { error -> AppLogger.w(TAG, "Failed to read verbose logging preference", error) }
                .collect { enabled -> AppLogger.setVerboseEnabled(enabled) }
        }
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
