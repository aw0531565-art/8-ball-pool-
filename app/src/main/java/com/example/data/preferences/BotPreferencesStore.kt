package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.botPrefsDataStore by preferencesDataStore(name = "neurobot_prefs")

data class BotUserPreferences(
    val activeProfileId: Long = 1L,
    val isUrduLanguage: Boolean = false,
    val isDarkTheme: Boolean = true
)

class BotPreferencesStore(private val context: Context) {

    private object Keys {
        val ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        val IS_URDU = booleanPreferencesKey("is_urdu_language")
        val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
    }

    val preferences: Flow<BotUserPreferences> = context.botPrefsDataStore.data.map { prefs ->
        BotUserPreferences(
            activeProfileId = prefs[Keys.ACTIVE_PROFILE_ID] ?: 1L,
            isUrduLanguage = prefs[Keys.IS_URDU] ?: false,
            isDarkTheme = prefs[Keys.IS_DARK_THEME] ?: true
        )
    }

    suspend fun setActiveProfileId(profileId: Long) {
        context.botPrefsDataStore.edit { it[Keys.ACTIVE_PROFILE_ID] = profileId }
    }

    suspend fun setUrduLanguage(enabled: Boolean) {
        context.botPrefsDataStore.edit { it[Keys.IS_URDU] = enabled }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.botPrefsDataStore.edit { it[Keys.IS_DARK_THEME] = enabled }
    }
}
