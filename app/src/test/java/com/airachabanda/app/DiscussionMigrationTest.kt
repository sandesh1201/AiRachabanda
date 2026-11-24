package com.airachabanda.app

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.airachabanda.app.persistence.AppDatabase
import com.airachabanda.app.persistence.Migrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiscussionMigrationTest {
    @Test
    fun version1RowsSurviveAndModelIdentityStaysUnknown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        VERSION_1.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { database ->
            database.execSQL(
                """
                INSERT INTO conversations (id, title, researchMode, createdAtEpochMs, updatedAtEpochMs)
                VALUES ('c1', 'Kept council', 'STANDARD', 10, 11)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO messages (id, conversationId, providerId, source, body, roundNumber, createdAtEpochMs)
                VALUES ('m1', 'c1', 'CLAUDE', 'USER_SUPPLIED_PROVIDER_ATTRIBUTED', 'Imported answer', 1, 12)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO research_sessions (id, conversationId, researchMode, createdAtEpochMs)
                VALUES ('s1', 'c1', 'STANDARD', 13)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO research_tasks (
                    id, sessionId, conversationId, providerId, roundNumber, state, prompt, createdAtEpochMs, updatedAtEpochMs
                ) VALUES ('t1', 's1', 'c1', 'CLAUDE', 1, 'AWAITING_USER_RESPONSE', 'Prompt', 14, 14)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO imported_responses (
                    id, conversationId, taskId, providerId, roundNumber, body, userSupplied, authenticated, createdAtEpochMs
                ) VALUES ('i1', 'c1', 't1', 'CLAUDE', 1, 'Imported answer', 1, 0, 15)
                """.trimIndent(),
            )
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .addMigrations(
                Migrations.MIGRATION_1_2,
                Migrations.MIGRATION_2_3,
                Migrations.MIGRATION_3_4,
                Migrations.MIGRATION_4_5,
                Migrations.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val conversation = migrated.councilDao().let { dao ->
                kotlinx.coroutines.runBlocking { dao.conversation("c1") }
            }
            assertEquals("Kept council", conversation?.title)
            migrated.openHelper.writableDatabase.query(
                "SELECT body, userDeclaredModelId, verifiedModelId FROM imported_responses WHERE id = 'i1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Imported answer", cursor.getString(0))
                assertEquals("unknown", cursor.getString(1))
                assertEquals("unknown", cursor.getString(2))
            }
            migrated.openHelper.writableDatabase.query(
                "SELECT state FROM research_sessions WHERE id = 's1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("AWAITING_USER_RESPONSE", cursor.getString(0))
            }
            migrated.openHelper.writableDatabase.query(
                "SELECT state FROM research_tasks WHERE id = 't1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("AWAITING_USER_RESPONSE", cursor.getString(0))
            }
            val tables = mutableListOf<String>()
            migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
            ).use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            listOf(
                "participants",
                "model_identities",
                "claims",
                "objections",
                "rebuttals",
                "verification_results",
                "conclusions",
                "workflow_checkpoints",
                "request_budget_entries",
                "protocol_actions",
                "provider_completions",
            ).forEach { name -> assertTrue(name, tables.contains(name)) }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val NAME = "migration-v1.db"
        val VERSION_1 = listOf(
            "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `researchMode` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `providerId` TEXT, `source` TEXT NOT NULL, `body` TEXT NOT NULL, `roundNumber` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)",
            "CREATE TABLE IF NOT EXISTS `provider_configs` (`providerId` TEXT NOT NULL, `accountChoice` TEXT NOT NULL, `declaredPlanId` TEXT, `billingEligibility` TEXT NOT NULL, `participationMode` TEXT NOT NULL, `integrationMethod` TEXT NOT NULL, `automatedInferenceEnabled` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`providerId`))",
            "CREATE TABLE IF NOT EXISTS `research_sessions` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `researchMode` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_research_sessions_conversationId` ON `research_sessions` (`conversationId`)",
            "CREATE TABLE IF NOT EXISTS `research_tasks` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `roundNumber` INTEGER NOT NULL, `state` TEXT NOT NULL, `prompt` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_research_tasks_sessionId` ON `research_tasks` (`sessionId`)",
            "CREATE INDEX IF NOT EXISTS `index_research_tasks_conversationId` ON `research_tasks` (`conversationId`)",
            "CREATE TABLE IF NOT EXISTS `imported_responses` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `taskId` TEXT, `providerId` TEXT NOT NULL, `roundNumber` INTEGER NOT NULL, `body` TEXT NOT NULL, `userSupplied` INTEGER NOT NULL, `authenticated` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_imported_responses_conversationId` ON `imported_responses` (`conversationId`)",
            "CREATE INDEX IF NOT EXISTS `index_imported_responses_taskId` ON `imported_responses` (`taskId`)",
            "CREATE TABLE IF NOT EXISTS `workflow_events` (`id` TEXT NOT NULL, `conversationId` TEXT, `providerId` TEXT, `type` TEXT NOT NULL, `summary` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_workflow_events_conversationId` ON `workflow_events` (`conversationId`)",
            "CREATE TABLE IF NOT EXISTS `attachment_metadata` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `addedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_attachment_metadata_conversationId` ON `attachment_metadata` (`conversationId`)",
        )
    }
}
