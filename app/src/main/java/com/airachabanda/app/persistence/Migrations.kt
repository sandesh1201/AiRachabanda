package com.airachabanda.app.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 stored conversations, assisted tasks, and imported responses.
 * Version 2 adds the discussion record. Existing rows stay in place.
 * There is no destructive migration fallback.
 */
object Migrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'AWAITING_USER_RESPONSE'")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `question` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `roundLimit` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `requestBudget` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `requestsConsumed` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `deadlineEpochMs` INTEGER")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `updatedAtEpochMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `resumeState` TEXT")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `awaitState` TEXT")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `complexity` TEXT NOT NULL DEFAULT 'MODERATE'")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `statusNote` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `reviewRound` INTEGER NOT NULL DEFAULT 0")

            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `role` TEXT NOT NULL DEFAULT 'RESEARCHER'")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'ASSISTED_RESEARCH'")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `participantId` TEXT")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `automated` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `targetId` TEXT")

            db.execSQL("ALTER TABLE `imported_responses` ADD COLUMN `userDeclaredModelId` TEXT NOT NULL DEFAULT 'unknown'")
            db.execSQL("ALTER TABLE `imported_responses` ADD COLUMN `verifiedModelId` TEXT NOT NULL DEFAULT 'unknown'")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `participants` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `providerId` TEXT NOT NULL,
                `participationMode` TEXT NOT NULL,
                `billingEligibility` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_participants_sessionId` ON `participants` (`sessionId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `participant_roles` (
                `id` TEXT NOT NULL,
                `participantId` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`participantId`) REFERENCES `participants`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_participant_roles_participantId` ON `participant_roles` (`participantId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `model_identities` (
                `id` TEXT NOT NULL,
                `participantId` TEXT NOT NULL,
                `providerId` TEXT NOT NULL,
                `userDeclaredModelId` TEXT NOT NULL DEFAULT 'unknown',
                `verifiedModelId` TEXT NOT NULL DEFAULT 'unknown',
                `independentlyVerified` INTEGER NOT NULL DEFAULT 0,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`participantId`) REFERENCES `participants`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_model_identities_participantId` ON `model_identities` (`participantId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `discussion_rounds` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `roundNumber` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `openedAtEpochMs` INTEGER NOT NULL,
                `closedAtEpochMs` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_discussion_rounds_sessionId` ON `discussion_rounds` (`sessionId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `claims` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `messageId` TEXT,
                `authorParticipantId` TEXT NOT NULL,
                `roundNumber` INTEGER NOT NULL,
                `statement` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_claims_sessionId` ON `claims` (`sessionId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `evidence_references` (
                `id` TEXT NOT NULL,
                `claimId` TEXT NOT NULL,
                `locator` TEXT NOT NULL,
                `note` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`claimId`) REFERENCES `claims`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_evidence_references_claimId` ON `evidence_references` (`claimId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `objections` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `targetClaimId` TEXT NOT NULL,
                `authorParticipantId` TEXT NOT NULL,
                `userDeclaredModelId` TEXT NOT NULL DEFAULT 'unknown',
                `verifiedModelId` TEXT NOT NULL DEFAULT 'unknown',
                `roundNumber` INTEGER NOT NULL,
                `category` TEXT NOT NULL,
                `severity` TEXT NOT NULL,
                `explanation` TEXT NOT NULL,
                `supportingEvidence` TEXT NOT NULL,
                `requestedVerification` TEXT NOT NULL,
                `resolution` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`targetClaimId`) REFERENCES `claims`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_objections_sessionId` ON `objections` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_objections_targetClaimId` ON `objections` (`targetClaimId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `rebuttals` (
                `id` TEXT NOT NULL,
                `objectionId` TEXT NOT NULL,
                `authorParticipantId` TEXT NOT NULL,
                `body` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`objectionId`) REFERENCES `objections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_rebuttals_objectionId` ON `rebuttals` (`objectionId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `verification_results` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `targetClaimId` TEXT,
                `objectionId` TEXT,
                `verifierParticipantId` TEXT NOT NULL,
                `outcome` TEXT NOT NULL,
                `note` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_verification_results_sessionId` ON `verification_results` (`sessionId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conclusions` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `body` TEXT NOT NULL,
                `roundNumber` INTEGER NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conclusions_sessionId` ON `conclusions` (`sessionId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `workflow_checkpoints` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `sessionState` TEXT NOT NULL,
                `note` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_checkpoints_sessionId` ON `workflow_checkpoints` (`sessionId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `request_budget_entries` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `participantId` TEXT,
                `amount` INTEGER NOT NULL,
                `reason` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_budget_entries_sessionId` ON `request_budget_entries` (`sessionId`)")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `protocol_actions` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `actorParticipantId` TEXT NOT NULL,
                `action` TEXT NOT NULL,
                `targetId` TEXT,
                `body` TEXT NOT NULL,
                `accepted` INTEGER NOT NULL,
                `reason` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_protocol_actions_sessionId` ON `protocol_actions` (`sessionId`)")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `model_identities` ADD COLUMN `capabilityNote` TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `provider_completions` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `taskId` TEXT NOT NULL,
                `providerId` TEXT NOT NULL,
                `modelId` TEXT NOT NULL,
                `body` TEXT NOT NULL,
                `complete` INTEGER NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `research_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_completions_sessionId` ON `provider_completions` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_completions_taskId` ON `provider_completions` (`taskId`)")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `provider_completions` ADD COLUMN `disposition` TEXT NOT NULL DEFAULT 'COMPLETED'")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `planSource` TEXT NOT NULL DEFAULT 'DETERMINISTIC'")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `concurrencyLimit` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `delegationDepthLimit` INTEGER NOT NULL DEFAULT 2")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `fanOutLimit` INTEGER NOT NULL DEFAULT 2")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `stoppingReason` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `planNote` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `research_sessions` ADD COLUMN `verificationRequired` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `objective` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `parentTaskId` TEXT")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `requiredCapability` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `expectedOutput` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `deadlineEpochMs` INTEGER")
            db.execSQL("ALTER TABLE `research_tasks` ADD COLUMN `taskBudget` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `task_dependencies` (
                `taskId` TEXT NOT NULL,
                `dependsOnTaskId` TEXT NOT NULL,
                PRIMARY KEY(`taskId`, `dependsOnTaskId`),
                FOREIGN KEY(`taskId`) REFERENCES `research_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`dependsOnTaskId`) REFERENCES `research_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_dependencies_dependsOnTaskId` ON `task_dependencies` (`dependsOnTaskId`)")
        }
    }
}
