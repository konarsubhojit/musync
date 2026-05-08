package com.musync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and retrieves user preferences (e.g. display name) using
 * Jetpack DataStore.
 */
interface UserPreferencesRepository {
    /** Flow of the user's persisted display name. Emits an empty string when not set. */
    val displayName: Flow<String>

    /** Saves [name] as the user's display name. */
    suspend fun saveDisplayName(name: String)
}

@Singleton
class UserPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : UserPreferencesRepository {
        companion object {
            private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        }

        override val displayName: Flow<String> =
            dataStore.data.map { prefs -> prefs[KEY_DISPLAY_NAME] ?: "" }

        override suspend fun saveDisplayName(name: String) {
            dataStore.edit { prefs -> prefs[KEY_DISPLAY_NAME] = name }
        }
    }
