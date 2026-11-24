package com.airachabanda.app

import android.app.Application
import androidx.room.Room
import com.airachabanda.app.documents.AttachmentService
import com.airachabanda.app.documents.GeneratedDocumentService
import com.airachabanda.app.documents.TemporaryFiles
import com.airachabanda.app.orchestration.DatabaseParticipantSource
import com.airachabanda.app.orchestration.OrchestrationEngine
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.CouncilRepository
import com.airachabanda.app.persistence.Migrations
import com.airachabanda.app.persistence.UserPreferences
import com.airachabanda.app.provider.cloudflare.CloudflareAdapter
import com.airachabanda.app.provider.cloudflare.CloudflareAuthorization
import com.airachabanda.app.provider.dispatch.PreparedProviderDispatch
import com.airachabanda.app.provider.gemini.GeminiAdapter
import com.airachabanda.app.provider.gemini.GeminiAuthorization
import com.airachabanda.app.provider.openrouter.OpenRouterAdapter
import com.airachabanda.app.provider.openrouter.OpenRouterAuthorization
import com.airachabanda.app.provider.openrouter.OpenRouterDispatch
import com.airachabanda.app.provider.openrouter.OpenRouterStatusStore
import com.airachabanda.app.provider.openrouter.UrlConnectionTransport
import com.airachabanda.app.security.AndroidKeystoreGateway
import com.airachabanda.app.security.CredentialStore
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.security.KeystoreCredentialStore
import java.io.File

data class PendingDiscussion(
    val conversationId: String,
    val question: String,
    val mode: ResearchMode,
    val participants: Set<ProviderId>,
)

class AIRachabandaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        TemporaryFiles.sweep(cacheDir, System.currentTimeMillis())
    }
}

class AppContainer(context: Application) {
    private val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "airachabanda.db",
    ).addMigrations(
        Migrations.MIGRATION_1_2,
        Migrations.MIGRATION_2_3,
        Migrations.MIGRATION_3_4,
        Migrations.MIGRATION_4_5,
        Migrations.MIGRATION_5_6,
        Migrations.MIGRATION_6_7,
        Migrations.MIGRATION_7_8,
        Migrations.MIGRATION_8_9,
    )
        .build()

    val credentialStore: CredentialStore = KeystoreCredentialStore(
        AndroidKeystoreGateway(File(context.filesDir, "credentials")),
    )
    val repository: CouncilRepository = CouncilRepository(
        dao = database.councilDao(),
        credentials = credentialStore,
    )
    val filesDir: File = context.filesDir
    val cacheDir: File = context.cacheDir
    val preferences: UserPreferences = UserPreferences(context)
    val openRouterStatus: OpenRouterStatusStore = OpenRouterStatusStore(File(context.filesDir, "openrouter-status.json"))
    val openRouterAdapter: OpenRouterAdapter = OpenRouterAdapter(
        transport = UrlConnectionTransport(),
        authorization = OpenRouterAuthorization.PRODUCTION,
    )

    fun consentedMetadataAdapter(): OpenRouterAdapter = OpenRouterAdapter(
        transport = UrlConnectionTransport(),
        authorization = OpenRouterAuthorization(metadataChecksAuthorized = true, inferenceAuthorized = false),
    )
    val documents = GeneratedDocumentService(
        context = context,
        council = database.councilDao(),
        discussion = database.discussionDao(),
    )
    val attachments = AttachmentService(
        context = context,
        council = database.councilDao(),
        discussion = database.discussionDao(),
    )
    val orchestration: OrchestrationEngine = OrchestrationEngine(
        council = database.councilDao(),
        discussion = database.discussionDao(),
        participants = DatabaseParticipantSource(database.councilDao()),
        dispatcher = OpenRouterDispatch(
            adapter = openRouterAdapter,
            council = database.councilDao(),
            discussion = database.discussionDao(),
            clock = { System.currentTimeMillis() },
            ids = { java.util.UUID.randomUUID().toString() },
        ),
        prepared = PreparedProviderDispatch(
            cloudflare = CloudflareAdapter(UrlConnectionTransport(), CloudflareAuthorization.PRODUCTION),
            gemini = GeminiAdapter(UrlConnectionTransport(), GeminiAuthorization.PRODUCTION),
            council = database.councilDao(),
            discussion = database.discussionDao(),
            clock = { System.currentTimeMillis() },
            ids = { java.util.UUID.randomUUID().toString() },
        ),
        attachmentContext = { conversationId, sessionId -> attachments.prepare(conversationId, sessionId) },
        onDiscussionStopped = { conversationId -> documents.cancelPending(conversationId) },
    )
    val councilDao = database.councilDao()
    val discussionDao = database.discussionDao()

    @Volatile
    var pendingDiscussion: PendingDiscussion? = null
}
