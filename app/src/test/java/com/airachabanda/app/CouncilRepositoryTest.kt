package com.airachabanda.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.AssistedTaskState
import com.airachabanda.app.core.model.ImportDraft
import com.airachabanda.app.core.model.MessageSource
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.CouncilRepository
import com.airachabanda.app.security.CredentialAccess
import com.airachabanda.app.security.CredentialStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CouncilRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CouncilRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CouncilRepository(database.councilDao(), clock = { 1_700_000_000_000L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun conversationRenameDeleteAndAccountDeclarationDoNotEnableAutomation() = runBlocking {
        repository.ensureSeeded()
        val id = repository.createConversation("First council", ResearchMode.QUICK)
        repository.renameConversation(id, "Renamed council")
        repository.addUserMessage(id, "What is the evidence?")
        repository.saveAccountChoice(ProviderId.CLAUDE, AccountChoice.PAID_SUBSCRIPTION, "pro")

        val conversations = repository.observeConversations().first()
        assertEquals("Renamed council", conversations.single().title)
        val messages = repository.observeMessages(id).first()
        assertEquals(1, messages.size)
        assertEquals(MessageSource.USER, messages.single().source)
        val claude = repository.observeProviderConfigs().first().first { it.providerId == ProviderId.CLAUDE }
        assertEquals(AccountChoice.PAID_SUBSCRIPTION, claude.accountChoice)
        assertEquals("pro", claude.declaredPlanId)
        assertFalse(claude.automatedInferenceEnabled)

        repository.deleteConversation(id)
        assertTrue(repository.observeConversations().first().isEmpty())
    }

    @Test
    fun assistedImportIsUserSuppliedAndSkipDoesNotInventAResponse() = runBlocking {
        val id = repository.createConversation("Claude task", ResearchMode.STANDARD)
        val taskId = repository.prepareAssistedTask(
            conversationId = id,
            providerId = ProviderId.CLAUDE,
            prompt = "Prepared prompt",
            roundNumber = 1,
        )
        assertEquals(AssistedTaskState.AWAITING_USER_RESPONSE, repository.observeTasks(id).first().single().state)

        repository.importResponse(
            ImportDraft(
                conversationId = id,
                providerId = ProviderId.CLAUDE,
                roundNumber = 1,
                body = "A response the user pasted.",
                taskId = taskId,
            ),
        )
        val messages = repository.observeMessages(id).first()
        assertEquals(MessageSource.USER_SUPPLIED_PROVIDER_ATTRIBUTED, messages.single().source)
        assertEquals(AssistedTaskState.COMPLETED, repository.observeTasks(id).first().single().state)

        val second = repository.prepareAssistedTask(id, ProviderId.CHATGPT, "Another prompt", 2)
        repository.updateTaskState(second, AssistedTaskState.SKIPPED)
        assertEquals(1, repository.observeMessages(id).first().size)
        assertTrue(repository.observeEvents().first().any { it.summary.contains("not an authenticated") })
    }

    @Test
    fun notConfiguredAccountDeletesOnlyThatCredential() = runBlocking {
        val store = RecordingCredentialStore()
        val secured = CouncilRepository(
            database.councilDao(),
            clock = { 1_700_000_000_000L },
            credentials = store,
        )
        secured.ensureSeeded()
        secured.saveAccountChoice(ProviderId.GROQ, AccountChoice.NOT_CONFIGURED, null)
        assertEquals(listOf("GROQ"), store.deleted)
        secured.deleteAllLocalContent()
        assertTrue(store.deletedAll)
    }
}

private class RecordingCredentialStore : CredentialStore {
    val deleted = mutableListOf<String>()
    var deletedAll = false

    override suspend fun put(providerId: String, accountKey: String, secret: ByteArray) = Unit

    override suspend fun get(providerId: String, accountKey: String): CredentialAccess = CredentialAccess.Missing

    override suspend fun delete(providerId: String, accountKey: String) {
        deleted += providerId
    }

    override suspend fun deleteAll() {
        deletedAll = true
    }
}
