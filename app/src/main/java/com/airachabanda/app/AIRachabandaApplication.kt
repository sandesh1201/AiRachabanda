package com.airachabanda.app

import android.app.Application
import androidx.room.Room
import com.airachabanda.app.orchestration.DatabaseParticipantSource
import com.airachabanda.app.orchestration.OrchestrationEngine
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.CouncilRepository
import com.airachabanda.app.persistence.Migrations
import com.airachabanda.app.persistence.UserPreferences
import com.airachabanda.app.provider.openrouter.OpenRouterAdapter
import com.airachabanda.app.provider.openrouter.OpenRouterAuthorization
import com.airachabanda.app.provider.openrouter.OpenRouterDispatch
import com.airachabanda.app.provider.openrouter.UrlConnectionTransport
import com.airachabanda.app.security.AndroidKeystoreGateway
import com.airachabanda.app.security.CredentialStore
import com.airachabanda.app.security.KeystoreCredentialStore
import java.io.File

class AIRachabandaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Application) {
    private val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "airachabanda.db",
    ).addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4)
        .build()

    val credentialStore: CredentialStore = KeystoreCredentialStore(
        AndroidKeystoreGateway(File(context.filesDir, "credentials")),
    )
    val repository: CouncilRepository = CouncilRepository(
        dao = database.councilDao(),
        credentials = credentialStore,
    )
    val preferences: UserPreferences = UserPreferences(context)
    val openRouterAdapter: OpenRouterAdapter = OpenRouterAdapter(
        transport = UrlConnectionTransport(),
        authorization = OpenRouterAuthorization.PRODUCTION,
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
    )
    val councilDao = database.councilDao()
    val discussionDao = database.discussionDao()
}
