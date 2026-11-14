package com.aura.aura_ui.agentcore

import android.content.Context
import ai.koog.prompt.message.Message
import androidx.room.ColumnInfo
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /** 3 to 5 lines on what the thread was about, written when it goes idle; null until then. */
    val summary: String? = null,
    /**
     * How to take back the last turn ([Undo], JSON), or null when it cannot be: it changed something
     * (a send, a tap, a saved memory), so running it again would do that twice.
     */
    val undo: String? = null,
)

/** The thread as it was before its last turn, and the user message that started that turn. */
@kotlinx.serialization.Serializable
data class Undo(val contextJson: String?, val taint: String, val fromItem: Long)

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

/** Where a memory came from. */
enum class MemorySource { SAID, LEARNED, IMPORTED }

/**
 * What one finished run did, kept for the idle review ([Learning]).
 * - Why a table: the review runs later, often after the process died, and the context drops the
 *   ledger on save. [tainted] is recorded here because the review must stage what a tainted run taught.
 */
@Entity(tableName = "digests")
data class DigestRow(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val threadId: String,
    val atMs: Long,
    val request: String,
    /** The run ledger's text, or blank for a short run. */
    val ledger: String,
    /** The final answer, or why the run stopped. */
    val outcome: String,
    val tainted: Boolean,
    /** Phone actions taken (taps, typing, scrolls...). */
    val actions: Int,
    /** The skill the run read with `skill_view`, if any. */
    val skill: String?,
    /** One line per phone action: tool and target label, for a skill's `steps:`. */
    val trace: String,
    @ColumnInfo(defaultValue = "0") val reviewed: Boolean = false,
)

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
    /** A [MemorySource] name: who the fact came from, shown on the memory screen. */
    @ColumnInfo(defaultValue = "SAID") val source: String = MemorySource.SAID.name,
    /** Pinned entries are kept when the user clears learned memories. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Proposed by a review of a run that read untrusted content: waits for the user, never in the prompt. */
    @ColumnInfo(defaultValue = "0") val staged: Boolean = false,
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

    @Query("SELECT * FROM items WHERE threadId = :threadId AND kind IN ('USER', 'ASSISTANT') ORDER BY atMs DESC, rowid DESC LIMIT :limit")
    suspend fun recentMessages(threadId: String, limit: Int): List<ItemRow>

    @Query("UPDATE threads SET summary = :summary WHERE id = :id")
    suspend fun setSummary(id: String, summary: String)

    @Query("SELECT * FROM items WHERE rowid = :id")
    suspend fun item(id: Long): ItemRow?

    @Query(
        "SELECT * FROM items WHERE threadId = :threadId AND kind IN ('USER', 'ASSISTANT') AND atMs BETWEEN :fromMs AND :toMs " +
            "ORDER BY atMs, rowid",
    )
    suspend fun itemsBetween(threadId: String, fromMs: Long, toMs: Long): List<ItemRow>

    @Query("UPDATE threads SET undo = :undo WHERE id = :id")
    suspend fun setUndo(id: String, undo: String?)

    @Query("UPDATE threads SET contextJson = :contextJson, taint = :taint, undo = NULL WHERE id = :id")
    suspend fun restore(id: String, contextJson: String?, taint: String)

    @Query("DELETE FROM items WHERE threadId = :threadId AND rowid >= :fromItem")
    suspend fun deleteItemsFrom(threadId: String, fromItem: Long)

    @Query("SELECT attachmentsJson FROM items WHERE threadId = :threadId AND attachmentsJson IS NOT NULL ORDER BY atMs, rowid")
    suspend fun attachmentRows(threadId: String): List<String>

    @Insert
    suspend fun insertMemory(row: MemoryRow): Long

    @Query("SELECT * FROM memories ORDER BY createdAtMs")
    suspend fun memories(): List<MemoryRow>

    @Query("DELETE FROM memories WHERE rowid = :id")
    suspend fun deleteMemory(id: Long): Int

    @Update
    suspend fun updateMemory(row: MemoryRow)

    @Insert
    suspend fun insertDigest(row: DigestRow): Long

    @Query("SELECT * FROM digests WHERE reviewed = 0 ORDER BY atMs LIMIT :limit")
    suspend fun unreviewedDigests(limit: Int): List<DigestRow>

    @Query("UPDATE digests SET reviewed = 1 WHERE rowid = :id")
    suspend fun markReviewed(id: Long)

    @Query("DELETE FROM digests WHERE threadId = :threadId")
    suspend fun deleteDigests(threadId: String)

    @Query("DELETE FROM digests WHERE atMs < :beforeMs")
    suspend fun pruneDigests(beforeMs: Long)

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
    entities = [ThreadRow::class, ItemRow::class, ItemFts::class, MemoryRow::class, JobRow::class, DigestRow::class],
    version = 4,
    exportSchema = false,
)
abstract class AgentCoreDatabase : RoomDatabase() {
    abstract fun dao(): AgentDao

    companion object {
        @Volatile
        private var instance: AgentCoreDatabase? = null

        fun get(context: Context): AgentCoreDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AgentCoreDatabase::class.java, "agentcore.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }

        /** Memory source, pin and staging; thread summaries; run digests for the idle review. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'SAID'")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `staged` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `threads` ADD COLUMN `summary` TEXT")
                db.execSQL(DIGESTS_SQL)
            }
        }

        /** Heartbeats: the chat a job reports to and when it gives up ([JobRow]). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `threadId` TEXT")
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `untilMs` INTEGER")
            }
        }

        /** Edit and regenerate: how to take back a thread's last turn ([ThreadRow.undo]). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `threads` ADD COLUMN `undo` TEXT")
            }
        }

        internal const val DIGESTS_SQL = "CREATE TABLE IF NOT EXISTS `digests` (`rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`threadId` TEXT NOT NULL, `atMs` INTEGER NOT NULL, `request` TEXT NOT NULL, `ledger` TEXT NOT NULL, " +
            "`outcome` TEXT NOT NULL, `tainted` INTEGER NOT NULL, `actions` INTEGER NOT NULL, `skill` TEXT, " +
            "`trace` TEXT NOT NULL, `reviewed` INTEGER NOT NULL DEFAULT 0)"
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
        dao.deleteDigests(threadId)
        dao.deleteItems(threadId)
        dao.deleteThread(threadId)
    }

    /** Empty the visible transcript and keep the model's context. */
    suspend fun clearItems(threadId: String) = dao.deleteItems(threadId)

    suspend fun append(
        threadId: String,
        kind: ItemKind,
        text: String,
        tool: String? = null,
        ok: Boolean? = null,
        attachments: List<Attachment> = emptyList(),
    ): Long {
        val attachmentsJson = attachments.takeIf { it.isNotEmpty() }
            ?.let { Json.encodeToString(ListSerializer(Attachment.serializer()), it) }
        return dao.insertItem(
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

    /** The last [limit] user and assistant messages of a thread, oldest first, as "user: ..." lines. */
    suspend fun recentTranscript(threadId: String, limit: Int = 40): String =
        dao.recentMessages(threadId, limit).reversed().joinToString("\n") { row ->
            (if (row.kind == ItemKind.USER.name) "user: " else "assistant: ") + row.text.take(1_000)
        }

    suspend fun setSummary(threadId: String, summary: String) = dao.setSummary(threadId, summary)

    suspend fun setUndo(threadId: String, undo: Undo?) = dao.setUndo(threadId, undo?.let { Json.encodeToString(Undo.serializer(), it) })

    /**
     * Take back the last turn: its context and taint as they were, and its messages gone from the
     * chat. Returns what the user had sent (text and files), or null when it cannot be taken back.
     */
    suspend fun undo(threadId: String): Pair<String, List<Attachment>>? {
        val row = dao.thread(threadId) ?: return null
        val undo = row.undo?.let { runCatching { Json.decodeFromString(Undo.serializer(), it) }.getOrNull() } ?: return null
        val asked = dao.item(undo.fromItem)?.takeIf { it.threadId == threadId } ?: return null
        val files = asked.attachmentsJson?.let { runCatching { Json.decodeFromString(ListSerializer(Attachment.serializer()), it) }.getOrNull() }.orEmpty()
        dao.deleteItemsFrom(threadId, undo.fromItem)
        dao.restore(threadId, undo.contextJson, undo.taint)
        return asked.text to files
    }

    /** Every file attached in [threadId], oldest first. A row that does not parse is skipped. */
    suspend fun attachments(threadId: String): List<Attachment> = dao.attachmentRows(threadId).flatMap { json ->
        runCatching { kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Attachment.serializer()), json) }
            .getOrDefault(emptyList())
    }

    /** The user and assistant messages within [AROUND_MS] of item [id] in its thread, oldest first, at most [AROUND_MAX]. */
    suspend fun around(id: Long): List<ItemRow> {
        val hit = dao.item(id) ?: return emptyList()
        val rows = dao.itemsBetween(hit.threadId, hit.atMs - AROUND_MS, hit.atMs + AROUND_MS)
        val at = rows.indexOfFirst { it.rowid == id }.coerceAtLeast(0)
        return rows.drop((at - AROUND_MAX / 2).coerceAtLeast(0)).take(AROUND_MAX)
    }

    companion object {
        private const val AROUND_MS = 6 * 60 * 60 * 1000L
        private const val AROUND_MAX = 8
        const val DEFAULT_TITLE = "New chat"
        private const val MAX_TITLE = 60
    }
}
