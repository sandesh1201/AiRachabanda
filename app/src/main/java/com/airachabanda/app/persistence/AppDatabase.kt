package com.airachabanda.app.persistence

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val researchMode: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val providerId: String?,
    val source: String,
    val body: String,
    val roundNumber: Int?,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val providerId: String,
    val accountChoice: String,
    val declaredPlanId: String?,
    val billingEligibility: String,
    val participationMode: String,
    val integrationMethod: String,
    val automatedInferenceEnabled: Boolean,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "research_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class ResearchSessionEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val researchMode: String,
    val createdAtEpochMs: Long,
    @ColumnInfo(defaultValue = "'AWAITING_USER_RESPONSE'")
    val state: String = "AWAITING_USER_RESPONSE",
    @ColumnInfo(defaultValue = "''")
    val question: String = "",
    @ColumnInfo(defaultValue = "1")
    val roundLimit: Int = 1,
    @ColumnInfo(defaultValue = "0")
    val requestBudget: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val requestsConsumed: Int = 0,
    val deadlineEpochMs: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val updatedAtEpochMs: Long = 0,
    val resumeState: String? = null,
    val awaitState: String? = null,
    @ColumnInfo(defaultValue = "'MODERATE'")
    val complexity: String = "MODERATE",
    @ColumnInfo(defaultValue = "''")
    val statusNote: String = "",
    @ColumnInfo(defaultValue = "0")
    val reviewRound: Int = 0,
    @ColumnInfo(defaultValue = "'DETERMINISTIC'")
    val planSource: String = "DETERMINISTIC",
    @ColumnInfo(defaultValue = "1")
    val concurrencyLimit: Int = 1,
    @ColumnInfo(defaultValue = "2")
    val delegationDepthLimit: Int = 2,
    @ColumnInfo(defaultValue = "2")
    val fanOutLimit: Int = 2,
    @ColumnInfo(defaultValue = "''")
    val stoppingReason: String = "",
    @ColumnInfo(defaultValue = "''")
    val planNote: String = "",
    @ColumnInfo(defaultValue = "0")
    val verificationRequired: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    val controlGeneration: Int = 1,
    @ColumnInfo(defaultValue = "'NONE'")
    val dispatchHold: String = "NONE",
)

@Entity(
    tableName = "research_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ResearchSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("conversationId")],
)
data class ResearchTaskEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val conversationId: String,
    val providerId: String,
    val roundNumber: Int,
    val state: String,
    val prompt: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "'RESEARCHER'")
    val role: String = "RESEARCHER",
    @ColumnInfo(defaultValue = "'ASSISTED_RESEARCH'")
    val kind: String = "ASSISTED_RESEARCH",
    val participantId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val automated: Boolean = false,
    val targetId: String? = null,
    @ColumnInfo(defaultValue = "''")
    val objective: String = "",
    val parentTaskId: String? = null,
    @ColumnInfo(defaultValue = "''")
    val requiredCapability: String = "",
    @ColumnInfo(defaultValue = "''")
    val expectedOutput: String = "",
    @ColumnInfo(defaultValue = "0")
    val priority: Int = 0,
    val deadlineEpochMs: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val taskBudget: Int = 0,
    @ColumnInfo(defaultValue = "1")
    val generation: Int = 1,
    @ColumnInfo(defaultValue = "'NOT_SENT'")
    val dispatchOutcome: String = "NOT_SENT",
)

@Entity(
    tableName = "task_dependencies",
    primaryKeys = ["taskId", "dependsOnTaskId"],
    foreignKeys = [
        ForeignKey(
            entity = ResearchTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ResearchTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["dependsOnTaskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dependsOnTaskId")],
)
data class TaskDependencyEntity(
    val taskId: String,
    val dependsOnTaskId: String,
)

@Entity(
    tableName = "imported_responses",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("taskId")],
)
data class ImportedResponseEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val taskId: String?,
    val providerId: String,
    val roundNumber: Int,
    val body: String,
    val userSupplied: Boolean,
    val authenticated: Boolean,
    val createdAtEpochMs: Long,
    @ColumnInfo(defaultValue = "'unknown'")
    val userDeclaredModelId: String = "unknown",
    @ColumnInfo(defaultValue = "'unknown'")
    val verifiedModelId: String = "unknown",
)

@Entity(
    tableName = "workflow_events",
    indices = [Index("conversationId")],
)
data class WorkflowEventEntity(
    @PrimaryKey val id: String,
    val conversationId: String?,
    val providerId: String?,
    val type: String,
    val summary: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "attachment_metadata",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class AttachmentMetadataEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "''")
    val originalFilename: String = "",
    @ColumnInfo(defaultValue = "''")
    val fingerprint: String = "",
    @ColumnInfo(defaultValue = "'PENDING'")
    val processingStatus: String = "PENDING",
    @ColumnInfo(defaultValue = "''")
    val detectedFormat: String = "",
    @ColumnInfo(defaultValue = "''")
    val limitation: String = "",
    @ColumnInfo(defaultValue = "0")
    val includeInPrompt: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val widthPixels: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val heightPixels: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val orientation: Int = 0,
)

@Entity(
    tableName = "attachment_chunks",
    foreignKeys = [
        ForeignKey(
            entity = AttachmentMetadataEntity::class,
            parentColumns = ["id"],
            childColumns = ["attachmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("attachmentId")],
)
data class AttachmentChunkEntity(
    @PrimaryKey val id: String,
    val attachmentId: String,
    val ordinal: Int,
    val reference: String,
    val kind: String,
    val method: String,
    val text: String,
    val processed: Boolean,
    val limitation: String,
)

data class ConversationDiscussionState(
    val conversationId: String,
    val state: String,
)

@Dao
interface CouncilDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAtEpochMs DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM provider_configs")
    fun observeProviderConfigs(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM research_tasks WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    fun observeTasks(conversationId: String): Flow<List<ResearchTaskEntity>>

    @Query("SELECT * FROM workflow_events ORDER BY createdAtEpochMs DESC")
    fun observeEvents(): Flow<List<WorkflowEventEntity>>

    @Query("SELECT * FROM workflow_events WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    suspend fun eventsForConversation(conversationId: String): List<WorkflowEventEntity>

    @Query("SELECT COUNT(*) FROM provider_configs")
    suspend fun providerConfigCount(): Int

    @Query("SELECT * FROM provider_configs WHERE providerId = :providerId")
    suspend fun findProvider(providerId: String): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs")
    suspend fun providerConfigs(): List<ProviderConfigEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    suspend fun messages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM research_tasks WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun tasksForSession(sessionId: String): List<ResearchTaskEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(entity: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun renameConversation(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET researchMode = :mode, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun updateResearchMode(id: String, mode: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(entity: ProviderConfigEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(entity: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(entity: ResearchSessionEntity)

    @Query("SELECT * FROM research_sessions WHERE conversationId = :conversationId ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun latestSession(conversationId: String): ResearchSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(entity: ResearchTaskEntity)

    @Query("SELECT * FROM research_tasks WHERE id = :id")
    suspend fun task(id: String): ResearchTaskEntity?

    @Query("UPDATE research_tasks SET state = :state, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun updateTaskState(id: String, state: String, updatedAt: Long)

    @Query("UPDATE research_tasks SET dispatchOutcome = :outcome, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun updateTaskOutcome(id: String, outcome: String, updatedAt: Long)

    @Query("SELECT * FROM research_sessions WHERE conversationId = :conversationId ORDER BY createdAtEpochMs DESC LIMIT 1")
    fun observeLatestSession(conversationId: String): Flow<List<ResearchSessionEntity>>

    @Query(
        "SELECT rs.conversationId AS conversationId, rs.state AS state FROM research_sessions AS rs " +
            "INNER JOIN (SELECT conversationId, MAX(createdAtEpochMs) AS latest FROM research_sessions GROUP BY conversationId) AS picked " +
            "ON rs.conversationId = picked.conversationId AND rs.createdAtEpochMs = picked.latest",
    )
    fun observeDiscussionStates(): Flow<List<ConversationDiscussionState>>

    @Query("UPDATE research_tasks SET prompt = :prompt, state = :state, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun updateTaskContent(id: String, prompt: String, state: String, updatedAt: Long)

    @Query("UPDATE research_tasks SET parentTaskId = :parentId WHERE id = :id")
    suspend fun updateTaskParent(id: String, parentId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImport(entity: ImportedResponseEntity)

    @Transaction
    suspend fun importAssistedResponse(
        imported: ImportedResponseEntity,
        message: MessageEntity,
        taskId: String?,
        completedState: String,
        updatedAt: Long,
        event: WorkflowEventEntity,
    ) {
        insertImport(imported)
        insertMessage(message)
        if (taskId != null) updateTaskState(taskId, completedState, updatedAt)
        insertEvent(event)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(entity: WorkflowEventEntity)

    @Query("DELETE FROM workflow_events")
    suspend fun deleteEvents()

    @Query("DELETE FROM provider_configs")
    suspend fun deleteProviderConfigs()

    @Query("DELETE FROM conversations")
    suspend fun deleteConversations()

    @Query("SELECT * FROM attachment_metadata WHERE conversationId = :conversationId ORDER BY addedAtEpochMs ASC")
    fun observeAttachments(conversationId: String): Flow<List<AttachmentMetadataEntity>>

    @Query("SELECT * FROM attachment_metadata WHERE conversationId = :conversationId ORDER BY addedAtEpochMs ASC")
    suspend fun attachments(conversationId: String): List<AttachmentMetadataEntity>

    @Query("SELECT * FROM attachment_metadata WHERE id = :id")
    suspend fun attachment(id: String): AttachmentMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachment(entity: AttachmentMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChunks(entities: List<AttachmentChunkEntity>)

    @Query("SELECT * FROM attachment_chunks WHERE attachmentId = :attachmentId ORDER BY ordinal ASC")
    suspend fun chunks(attachmentId: String): List<AttachmentChunkEntity>

    @Query("DELETE FROM attachment_chunks WHERE attachmentId = :attachmentId")
    suspend fun deleteChunks(attachmentId: String)

    @Query("DELETE FROM attachment_metadata WHERE id = :id")
    suspend fun deleteAttachment(id: String)

    @Query("UPDATE attachment_metadata SET displayName = :label WHERE id = :id")
    suspend fun renameAttachment(id: String, label: String)

    @Query("UPDATE attachment_metadata SET includeInPrompt = :consent WHERE conversationId = :conversationId")
    suspend fun setAttachmentConsent(conversationId: String, consent: Boolean)

    @Query(
        """
        UPDATE attachment_metadata
        SET mimeType = :mimeType,
            processingStatus = :status,
            detectedFormat = :format,
            limitation = :limitation,
            widthPixels = :width,
            heightPixels = :height,
            orientation = :orientation
        WHERE id = :id
        """,
    )
    suspend fun updateAttachmentResult(
        id: String,
        mimeType: String,
        status: String,
        format: String,
        limitation: String,
        width: Int,
        height: Int,
        orientation: Int,
    )

    @Query("SELECT * FROM generated_documents WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    fun observeGeneratedDocuments(conversationId: String): Flow<List<GeneratedDocumentEntity>>

    @Query("SELECT * FROM generated_documents WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    suspend fun generatedDocuments(conversationId: String): List<GeneratedDocumentEntity>

    @Query("SELECT * FROM generated_documents WHERE id = :id")
    suspend fun generatedDocument(id: String): GeneratedDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGeneratedDocument(entity: GeneratedDocumentEntity)

    @Query(
        """
        UPDATE generated_documents
        SET status = :status, sizeBytes = :size, fingerprint = :fingerprint, limitation = :limitation
        WHERE id = :id
        """,
    )
    suspend fun updateGeneratedDocument(id: String, status: String, size: Long, fingerprint: String, limitation: String)

    @Query("DELETE FROM generated_documents WHERE id = :id")
    suspend fun deleteGeneratedDocument(id: String)
}

@Entity(
    tableName = "generated_documents",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class GeneratedDocumentEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val title: String,
    val templateId: String,
    val format: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val fingerprint: String,
    val status: String,
    val limitation: String,
    val versionNumber: Int,
    val sourceBody: String,
    val includeRecords: Boolean,
    val createdAtEpochMs: Long,
)

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ProviderConfigEntity::class,
        ResearchSessionEntity::class,
        ResearchTaskEntity::class,
        ImportedResponseEntity::class,
        WorkflowEventEntity::class,
        AttachmentMetadataEntity::class,
        AttachmentChunkEntity::class,
        ParticipantEntity::class,
        ParticipantRoleEntity::class,
        ModelIdentityEntity::class,
        DiscussionRoundEntity::class,
        ClaimEntity::class,
        EvidenceReferenceEntity::class,
        ObjectionEntity::class,
        RebuttalEntity::class,
        VerificationResultEntity::class,
        ConclusionEntity::class,
        WorkflowCheckpointEntity::class,
        RequestBudgetEntryEntity::class,
        ProtocolActionEntity::class,
        ProviderCompletionEntity::class,
        TaskDependencyEntity::class,
        GeneratedDocumentEntity::class,
        ParticipantFaultEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun councilDao(): CouncilDao
    abstract fun discussionDao(): DiscussionDao
}
