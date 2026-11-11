package com.aura.aura_ui.agentcore

import android.content.Context
import ai.koog.prompt.message.Message
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Chat threads on disk: what the user sees (items) and what the model sees (context), kept apart.
 *
 * - Contract: `items` is the visible transcript and is never rewritten. `threads.contextJson` is
 *   the model's working history for the thread and is replaced whole after every turn, already
 *   evicted and compacted. The two diverge on purpose: compaction must never delete what the
 *   user can scroll back to.
 * - Reads/Writes: Room database `agentcore.db` in the app's private storage. Nothing here touches
 *   `AuraDatabase`.
 * - Fails: a context that no longer decodes (after a Koog upgrade changes its message format) is
 *   treated as empty, so the thread stays readable and the model simply starts fresh.
 * - Change together: a schema change needs a version bump and a `Migration`.
 */
@Entity(tableName = "threads")
data class ThreadRow(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Koog messages, JSON. Null until the first turn ends. */
    val contextJson: String? = null,
    /** Comma-separated [Origin] names of untrusted content the context holds. */
    val taint: String = "",
)

@Entity(tableName = "items", indices = [Index("threadId", "atMs")])
data class ItemRow(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val threadId: String,
    val atMs: Long,
    /** An [ItemKind] name. */
    val kind: String,
    val text: String,
    /** Tool name, for [ItemKind.TOOL] rows. */
    val tool: String? = null,
    val ok: Boolean? = null,
    /** JSON list of [Attachment], for [ItemKind.USER] rows that have any. */
    val attachmentsJson: String? = null,
)

/** Full-text index over [ItemRow.text]; Room keeps it in step with `items`. */
@Fts4(contentEntity = ItemRow::class)
@Entity(tableName = "items_fts")
data class ItemFts(val text: String)

@Entity(tableName = "memories")
data class MemoryRow(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    /** A [MemoryKind] name. */
    val kind: String,
    val text: String,
    val createdAtMs: Long,
    /** Label of the app this memory is about, or null when it is not about one app. */
    val app: String? = null,
    /** That app's version name when the memory was saved. */
    val appVersion: String? = null,
)

@Dao
interface AgentDao {
    @Upsert
    suspend fun upsertThread(row: ThreadRow)

    @Query("SELECT * FROM threads ORDER BY updatedAtMs DESC")
    fun threads(): Flow<List<ThreadRow>>

    @Query("SELECT * FROM threads WHERE id = :id")
    suspend fun thread(id: String): ThreadRow?

    @Query("DELETE FROM threads WHERE id = :id")
    suspend fun deleteThread(id: String)

    @Query("DELETE FROM items WHERE threadId = :threadId")
    suspend fun deleteItems(threadId: String)

    @Insert
    suspend fun insertItem(row: ItemRow): Long

    @Query("SELECT * FROM items WHERE threadId = :threadId ORDER BY atMs, rowid")
    fun items(threadId: String): Flow<List<ItemRow>>

    @Query(
        "SELECT items.* FROM items JOIN items_fts ON items.rowid = items_fts.rowid " +
            "WHERE items_fts MATCH :query AND items.kind IN ('USER', 'ASSISTANT') " +
            "ORDER BY items.atMs DESC LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int): List<ItemRow>

    @Insert
    suspend fun insertMemory(row: MemoryRow): Long

    @Query("SELECT * FROM memories ORDER BY createdAtMs")
    suspend fun memories(): List<MemoryRow>

    @Query("DELETE FROM memories WHERE rowid = :id")
    suspend fun deleteMemory(id: Long): Int

    @Insert
    suspend fun insertJob(row: JobRow): Long

    @Update
    suspend fun updateJob(row: JobRow)

    @Query("SELECT * FROM jobs ORDER BY rowid")
    suspend fun jobs(): List<JobRow>

    @Query("DELETE FROM jobs WHERE rowid = :id")
    suspend fun deleteJob(id: Long): Int
}

@Database(
    entities = [ThreadRow::class, ItemRow::class, ItemFts::class, MemoryRow::class, JobRow::class],
    version = 1,
    exportSchema = false,
)
abstract class AgentCoreDatabase : RoomDatabase() {
    abstract fun dao(): AgentDao

    companion object {
        @Volatile
        private var instance: AgentCoreDatabase? = null

        fun get(context: Context): AgentCoreDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AgentCoreDatabase::class.java, "agentcore.db")
                .build().also { instance = it }
        }
    }
}

/** Encodes the model's working history. Pure, so the format is covered by a JVM test. */
object ContextCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Message.serializer())

    fun encode(messages: List<Message>): String = json.encodeToString(serializer, messages)

    /** Fails: returns an empty list for null, blank or undecodable input. */
    fun decode(stored: String?): List<Message> =
        stored?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?: emptyList()

    fun encodeTaint(origins: Set<Origin>): String = origins.joinToString(",") { it.name }

    fun decodeTaint(stored: String): Set<Origin> =
        stored.split(',').mapNotNull { name -> Origin.entries.firstOrNull { it.name == name } }.toSet()
}

/** What [ThreadStore.loadContext] returns. */
class StoredContext(val messages: List<Message>, val taint: Set<Origin>)

class ThreadStore(private val dao: AgentDao, private val now: () -> Long = System::currentTimeMillis) {

    fun threads(): Flow<List<ThreadRow>> = dao.threads()

    fun items(threadId: String): Flow<List<ItemRow>> = dao.items(threadId)

    suspend fun create(title: String = DEFAULT_TITLE): ThreadRow {
        val time = now()
        return ThreadRow(UUID.randomUUID().toString(), title, time, time).also { dao.upsertThread(it) }
    }

    suspend fun thread(threadId: String): ThreadRow? = dao.thread(threadId)

    suspend fun rename(threadId: String, title: String) {
        dao.thread(threadId)?.let { dao.upsertThread(it.copy(title = title.take(MAX_TITLE))) }
    }

    suspend fun delete(threadId: String) {
        dao.deleteItems(threadId)
        dao.deleteThread(threadId)
    }

    suspend fun append(
        threadId: String,
        kind: ItemKind,
        text: String,
        tool: String? = null,
        ok: Boolean? = null,
        attachments: List<Attachment> = emptyList(),
    ) {
        val attachmentsJson = attachments.takeIf { it.isNotEmpty() }
            ?.let { Json.encodeToString(ListSerializer(Attachment.serializer()), it) }
        dao.insertItem(
            ItemRow(
                threadId = threadId,
                atMs = now(),
                kind = kind.name,
                text = text,
                tool = tool,
                ok = ok,
                attachmentsJson = attachmentsJson,
            ),
        )
    }

    suspend fun loadContext(threadId: String): StoredContext {
        val row = dao.thread(threadId)
        return StoredContext(ContextCodec.decode(row?.contextJson), ContextCodec.decodeTaint(row?.taint.orEmpty()))
    }

    /** Replace the thread's model history. [messages] must already be [ContextAssembler.forStorage]. */
    suspend fun saveContext(threadId: String, messages: List<Message>, taint: Set<Origin>) {
        val row = dao.thread(threadId) ?: return
        dao.upsertThread(
            row.copy(
                updatedAtMs = now(),
                contextJson = ContextCodec.encode(messages),
                taint = ContextCodec.encodeTaint(taint),
            ),
        )
    }

    /**
     * Search what was said in every thread.
     * - Contract: [query] is plain words. Each word is quoted, so nothing the model writes can be
     *   read as full-text-search syntax.
     */
    suspend fun search(query: String, limit: Int = 5): List<ItemRow> {
        val terms = query.split(Regex("\\s+")).map { it.replace("\"", "") }.filter { it.length > 1 }
        if (terms.isEmpty()) return emptyList()
        return dao.search(terms.joinToString(" ") { "\"$it\"" }, limit)
    }

    companion object {
        const val DEFAULT_TITLE = "New chat"
        private const val MAX_TITLE = 60
    }
}
