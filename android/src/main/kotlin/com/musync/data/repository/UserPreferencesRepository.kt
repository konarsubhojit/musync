package com.musync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.musync.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and retrieves user preferences (e.g. display name) using
 * Jetpack DataStore.
 */
interface UserPreferencesRepository {
    /** Flow of the user's persisted display name. Emits an empty string when not set. */
    val displayName: Flow<String>

    /** Flow of whether the app should use dark theme. */
    val darkTheme: Flow<Boolean>

    /** Saves [name] as the user's display name. */
    suspend fun saveDisplayName(name: String)

    /** Saves whether dark theme is enabled. */
    suspend fun saveDarkTheme(enabled: Boolean)
}

@Singleton
class UserPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : UserPreferencesRepository {
        companion object {
            private const val TAG = "UserPreferencesRepository"
            private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
            private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
        }

        /**
         * Safely reads from DataStore, recovering from transient I/O errors and
         * preference corruption by emitting empty preferences. Without this,
         * any read failure would propagate and cancel collectors (potentially
         * crashing the UI since the result is observed in MainActivity).
         */
        private val safeData: Flow<Preferences> =
            dataStore.data.catch { error ->
                if (error is IOException) {
                    AppLogger.w(TAG, "Failed to read user preferences; using defaults.", error)
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }

        override val displayName: Flow<String> =
            safeData.map { prefs -> prefs[KEY_DISPLAY_NAME] ?: "" }

        override val darkTheme: Flow<Boolean> =
            safeData.map { prefs -> prefs[KEY_DARK_THEME] ?: true }

        override suspend fun saveDisplayName(name: String) {
            dataStore.edit { prefs -> prefs[KEY_DISPLAY_NAME] = name }
        }

        override suspend fun saveDarkTheme(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[KEY_DARK_THEME] = enabled }
        }
    }
