package com.airachabanda.app.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airachabanda.app.core.model.AppearanceMode
import com.airachabanda.app.core.model.ResearchMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class UserPreferences(private val context: Context) {
    val appearance: Flow<AppearanceMode> = context.settingsDataStore.data.map { prefs ->
        prefs[APPEARANCE]?.let { runCatching { AppearanceMode.valueOf(it) }.getOrNull() }
            ?: AppearanceMode.SYSTEM
    }

    val defaultResearchMode: Flow<ResearchMode> = context.settingsDataStore.data.map { prefs ->
        prefs[RESEARCH_MODE]?.let { runCatching { ResearchMode.valueOf(it) }.getOrNull() }
            ?: ResearchMode.STANDARD
    }

    val maxRounds: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[MAX_ROUNDS] ?: 3
    }

    suspend fun setAppearance(mode: AppearanceMode) {
        context.settingsDataStore.edit { it[APPEARANCE] = mode.name }
    }

    suspend fun setDefaultResearchMode(mode: ResearchMode) {
        context.settingsDataStore.edit { it[RESEARCH_MODE] = mode.name }
    }

    suspend fun setMaxRounds(rounds: Int) {
        context.settingsDataStore.edit { it[MAX_ROUNDS] = rounds.coerceIn(1, 8) }
    }

    private companion object {
        val APPEARANCE = stringPreferencesKey("appearance")
        val RESEARCH_MODE = stringPreferencesKey("default_research_mode")
        val MAX_ROUNDS = intPreferencesKey("max_rounds")
    }
}
