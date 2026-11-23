package com.airachabanda.app

import android.app.Application
import androidx.room.Room
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.CouncilRepository
import com.airachabanda.app.persistence.UserPreferences
import com.airachabanda.app.security.AndroidKeystoreGateway
import com.airachabanda.app.security.CredentialStore
import com.airachabanda.app.security.KeystoreCredentialStore

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
    ).build()

    val repository: CouncilRepository = CouncilRepository(database.councilDao())
    val preferences: UserPreferences = UserPreferences(context)
    val credentialStore: CredentialStore = KeystoreCredentialStore(AndroidKeystoreGateway())
}
