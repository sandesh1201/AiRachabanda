package com.airachabanda.app.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DiscussionDao {
    @Query("SELECT * FROM research_sessions WHERE id = :id")
    suspend fun session(id: String): ResearchSessionEntity?

    @Query(
        """
        UPDATE research_sessions
        SET state = :state,
            resumeState = :resumeState,
            awaitState = :awaitState,
            requestsConsumed = :requestsConsumed,
            reviewRound = :reviewRound,
            statusNote = :statusNote,
            updatedAtEpochMs = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateSessionProgress(
        id: String,
        state: String,
        resumeState: String?,
        awaitState: String?,
        requestsConsumed: Int,
        reviewRound: Int,
        statusNote: String,
        updatedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParticipant(entity: ParticipantEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRole(entity: ParticipantRoleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertModelIdentity(entity: ModelIdentityEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRound(entity: DiscussionRoundEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertClaim(entity: ClaimEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvidence(entity: EvidenceReferenceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertObjection(entity: ObjectionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRebuttal(entity: RebuttalEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVerification(entity: VerificationResultEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConclusion(entity: ConclusionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCheckpoint(entity: WorkflowCheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBudget(entity: RequestBudgetEntryEntity)

    @Query("SELECT * FROM participants WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun participants(sessionId: String): List<ParticipantEntity>

    @Query("SELECT * FROM participant_roles WHERE participantId = :participantId")
    suspend fun roles(participantId: String): List<ParticipantRoleEntity>

    @Query("SELECT * FROM model_identities WHERE participantId = :participantId")
    suspend fun modelIdentity(participantId: String): ModelIdentityEntity?

    @Query(
        """
        UPDATE model_identities
        SET userDeclaredModelId = :declared
        WHERE participantId = :participantId
        """,
    )
    suspend fun updateDeclaredModel(participantId: String, declared: String)

    @Query("SELECT * FROM claims WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun claims(sessionId: String): List<ClaimEntity>

    @Query("SELECT * FROM claims WHERE id = :id")
    suspend fun claim(id: String): ClaimEntity?

    @Query("SELECT * FROM objections WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun objections(sessionId: String): List<ObjectionEntity>

    @Query("SELECT * FROM objections WHERE id = :id")
    suspend fun objection(id: String): ObjectionEntity?

    @Query("SELECT * FROM rebuttals WHERE objectionId = :objectionId ORDER BY createdAtEpochMs ASC")
    suspend fun rebuttals(objectionId: String): List<RebuttalEntity>

    @Query("UPDATE objections SET resolution = :resolution WHERE id = :id")
    suspend fun updateObjectionResolution(id: String, resolution: String)

    @Query("SELECT * FROM discussion_rounds WHERE sessionId = :sessionId ORDER BY roundNumber ASC")
    suspend fun rounds(sessionId: String): List<DiscussionRoundEntity>

    @Query("SELECT * FROM verification_results WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun verifications(sessionId: String): List<VerificationResultEntity>

    @Query("SELECT * FROM conclusions WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun conclusions(sessionId: String): List<ConclusionEntity>

    @Query("SELECT * FROM workflow_checkpoints WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun checkpoints(sessionId: String): List<WorkflowCheckpointEntity>

    @Query("SELECT * FROM request_budget_entries WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun budgetEntries(sessionId: String): List<RequestBudgetEntryEntity>

    @Transaction
    suspend fun consumeBudget(entry: RequestBudgetEntryEntity, consumed: Int, updatedAt: Long) {
        insertBudget(entry)
        val current = session(entry.sessionId) ?: return
        updateSessionProgress(
            id = current.id,
            state = current.state,
            resumeState = current.resumeState,
            awaitState = current.awaitState,
            requestsConsumed = consumed,
            reviewRound = current.reviewRound,
            statusNote = current.statusNote,
            updatedAt = updatedAt,
        )
    }
}
