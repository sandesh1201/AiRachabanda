package com.airachabanda.app.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "participants",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ParticipantEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val providerId: String,
    val participationMode: String,
    val billingEligibility: String,
    val createdAtEpochMs: Long,
    @ColumnInfo(defaultValue = "'ELIGIBLE'")
    val availability: String = "ELIGIBLE",
)

@Entity(
    tableName = "participant_roles",
    foreignKeys = [
        ForeignKey(
            entity = ParticipantEntity::class,
            parentColumns = ["id"],
            childColumns = ["participantId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("participantId")],
)
data class ParticipantRoleEntity(
    @PrimaryKey val id: String,
    val participantId: String,
    val role: String,
    val reason: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "model_identities",
    foreignKeys = [
        ForeignKey(
            entity = ParticipantEntity::class,
            parentColumns = ["id"],
            childColumns = ["participantId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("participantId")],
)
data class ModelIdentityEntity(
    @PrimaryKey val id: String,
    val participantId: String,
    val providerId: String,
    @ColumnInfo(defaultValue = "'unknown'")
    val userDeclaredModelId: String,
    @ColumnInfo(defaultValue = "'unknown'")
    val verifiedModelId: String,
    @ColumnInfo(defaultValue = "0")
    val independentlyVerified: Boolean,
    val createdAtEpochMs: Long,
    @ColumnInfo(defaultValue = "''")
    val capabilityNote: String = "",
)

@Entity(
    tableName = "discussion_rounds",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class DiscussionRoundEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val roundNumber: Int,
    val state: String,
    val openedAtEpochMs: Long,
    val closedAtEpochMs: Long?,
)

@Entity(
    tableName = "claims",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ClaimEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String?,
    val authorParticipantId: String,
    val roundNumber: Int,
    val statement: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "evidence_references",
    foreignKeys = [
        ForeignKey(
            entity = ClaimEntity::class,
            parentColumns = ["id"],
            childColumns = ["claimId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("claimId")],
)
data class EvidenceReferenceEntity(
    @PrimaryKey val id: String,
    val claimId: String,
    val locator: String,
    val note: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "objections",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ClaimEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetClaimId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("targetClaimId")],
)
data class ObjectionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val targetClaimId: String,
    val authorParticipantId: String,
    @ColumnInfo(defaultValue = "'unknown'")
    val userDeclaredModelId: String,
    @ColumnInfo(defaultValue = "'unknown'")
    val verifiedModelId: String,
    val roundNumber: Int,
    val category: String,
    val severity: String,
    val explanation: String,
    val supportingEvidence: String,
    val requestedVerification: String,
    val resolution: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "rebuttals",
    foreignKeys = [
        ForeignKey(
            entity = ObjectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["objectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("objectionId")],
)
data class RebuttalEntity(
    @PrimaryKey val id: String,
    val objectionId: String,
    val authorParticipantId: String,
    val body: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "verification_results",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class VerificationResultEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val targetClaimId: String?,
    val objectionId: String?,
    val verifierParticipantId: String,
    val outcome: String,
    val note: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "conclusions",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ConclusionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val kind: String,
    val body: String,
    val roundNumber: Int,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "workflow_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class WorkflowCheckpointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sessionState: String,
    val note: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "request_budget_entries",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class RequestBudgetEntryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val participantId: String?,
    val amount: Int,
    val reason: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "protocol_actions",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ProtocolActionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val actorParticipantId: String,
    val action: String,
    val targetId: String?,
    val body: String,
    val accepted: Boolean,
    val reason: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "provider_completions",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("taskId")],
)
data class ProviderCompletionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val taskId: String,
    val providerId: String,
    val modelId: String,
    val body: String,
    val complete: Boolean,
    val createdAtEpochMs: Long,
    @androidx.room.ColumnInfo(defaultValue = "'COMPLETED'")
    val disposition: String = "COMPLETED",
)

@Entity(
    tableName = "participant_faults",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ParticipantFaultEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val participantId: String,
    val providerId: String,
    val fault: String,
    val outcome: String,
    val reassignedTo: String,
    val limitation: String,
    val createdAtEpochMs: Long,
)
