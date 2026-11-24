package com.airachabanda.app.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airachabanda.app.core.model.AppearanceMode
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.orchestration.DiscussionBudgets
import com.airachabanda.app.provider.ProviderAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    val discussionLimits: Flow<DiscussionBudgets> = context.settingsDataStore.data.map { prefs ->
        DiscussionBudgets(
            rounds = prefs[MAX_ROUNDS] ?: 3,
            aiRequests = prefs[MAX_AI_REQUESTS] ?: 4,
            researchRequests = prefs[MAX_RESEARCH_REQUESTS] ?: 2,
            researchSources = prefs[MAX_RESEARCH_SOURCES] ?: 3,
            concurrency = prefs[CONCURRENCY] ?: 2,
            delegationDepth = prefs[DELEGATION_DEPTH] ?: 2,
            deadlineMinutes = prefs[DEADLINE_MINUTES] ?: 60,
            verificationRequired = prefs[VERIFICATION] == "required",
        ).clamped()
    }

    val enabledParticipants: Flow<Set<ProviderId>> = context.settingsDataStore.data.map { prefs ->
        val stored = prefs[ENABLED_PARTICIPANTS] ?: return@map ProviderAccess.defaultEnabled
        stored.mapNotNull { name -> runCatching { ProviderId.valueOf(name) }.getOrNull() }.toSet()
    }

    val lastAssisted: Flow<AssistedReturn?> = context.settingsDataStore.data.map { prefs ->
        val conversation = prefs[LAST_CONVERSATION] ?: return@map null
        val provider = prefs[LAST_PROVIDER]?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() } ?: return@map null
        AssistedReturn(conversation, prefs[LAST_TASK].orEmpty(), provider)
    }

    suspend fun currentAssisted(): AssistedReturn? = lastAssisted.first()

    suspend fun setAppearance(mode: AppearanceMode) {
        context.settingsDataStore.edit { it[APPEARANCE] = mode.name }
    }

    suspend fun setDefaultResearchMode(mode: ResearchMode) {
        context.settingsDataStore.edit { it[RESEARCH_MODE] = mode.name }
    }

    suspend fun setMaxRounds(rounds: Int) {
        context.settingsDataStore.edit { it[MAX_ROUNDS] = rounds.coerceIn(1, DiscussionBudgets.MAX_ROUNDS) }
    }

    suspend fun setDiscussionLimits(limits: DiscussionBudgets) {
        val saved = limits.clamped()
        context.settingsDataStore.edit { prefs ->
            prefs[MAX_ROUNDS] = saved.rounds
            prefs[MAX_AI_REQUESTS] = saved.aiRequests
            prefs[MAX_RESEARCH_REQUESTS] = saved.researchRequests
            prefs[MAX_RESEARCH_SOURCES] = saved.researchSources
            prefs[CONCURRENCY] = saved.concurrency
            prefs[DELEGATION_DEPTH] = saved.delegationDepth
            prefs[DEADLINE_MINUTES] = saved.deadlineMinutes
            prefs[VERIFICATION] = if (saved.verificationRequired) "required" else "mode"
        }
    }

    suspend fun setParticipantEnabled(providerId: ProviderId, enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[ENABLED_PARTICIPANTS]?.mapNotNull { name ->
                runCatching { ProviderId.valueOf(name) }.getOrNull()
            }?.toSet() ?: ProviderAccess.defaultEnabled
            val next = if (enabled) current + providerId else current - providerId
            prefs[ENABLED_PARTICIPANTS] = next.map { it.name }.toSet()
        }
    }

    suspend fun rememberAssisted(conversationId: String, taskId: String, providerId: ProviderId) {
        context.settingsDataStore.edit { prefs ->
            prefs[LAST_CONVERSATION] = conversationId
            prefs[LAST_TASK] = taskId
            prefs[LAST_PROVIDER] = providerId.name
        }
    }

    private companion object {
        val APPEARANCE = stringPreferencesKey("appearance")
        val RESEARCH_MODE = stringPreferencesKey("default_research_mode")
        val MAX_ROUNDS = intPreferencesKey("max_rounds")
        val MAX_AI_REQUESTS = intPreferencesKey("max_ai_requests")
        val MAX_RESEARCH_REQUESTS = intPreferencesKey("max_research_requests")
        val MAX_RESEARCH_SOURCES = intPreferencesKey("max_research_sources")
        val CONCURRENCY = intPreferencesKey("concurrency_limit")
        val DELEGATION_DEPTH = intPreferencesKey("delegation_depth")
        val DEADLINE_MINUTES = intPreferencesKey("deadline_minutes")
        val VERIFICATION = stringPreferencesKey("verification_requirement")
        val ENABLED_PARTICIPANTS = stringSetPreferencesKey("enabled_participants")
        val LAST_CONVERSATION = stringPreferencesKey("last_assisted_conversation")
        val LAST_TASK = stringPreferencesKey("last_assisted_task")
        val LAST_PROVIDER = stringPreferencesKey("last_assisted_provider")
    }
}

data class AssistedReturn(
    val conversationId: String,
    val taskId: String,
    val providerId: ProviderId,
)
