package com.airachabanda.app.persistence

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

    @Query("SELECT COUNT(*) FROM provider_configs")
    suspend fun providerConfigCount(): Int

    @Query("SELECT * FROM provider_configs WHERE providerId = :providerId")
    suspend fun findProvider(providerId: String): ProviderConfigEntity?

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
}

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
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun councilDao(): CouncilDao
}
