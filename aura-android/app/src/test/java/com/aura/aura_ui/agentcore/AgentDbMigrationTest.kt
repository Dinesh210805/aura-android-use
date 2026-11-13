package com.aura.aura_ui.agentcore

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Opening a version 1 `agentcore.db` keeps every thread and memory; Room validates the result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentDbMigrationTest {

    @Test
    fun `version 1 data survives the upgrade to 2`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("agentcore.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            V1.forEach(db::execSQL)
            db.execSQL("INSERT INTO threads VALUES ('t1', 'Cab', 1, 2, NULL, '')")
            db.execSQL("INSERT INTO memories (kind, text, createdAtMs) VALUES ('USER', 'Lives in Chennai', 3)")
            db.version = 1
        }
        val dao = AgentCoreDatabase.get(context).dao()
        assertEquals("Cab", dao.thread("t1")?.title)
        assertNull(dao.thread("t1")?.summary)
        val memory = dao.memories().single()
        assertEquals(MemorySource.SAID.name, memory.source)
        assertFalse(memory.staged)
        assertEquals(emptyList<DigestRow>(), dao.unreviewedDigests(5))
    }

    private companion object {
        /** Room's own CREATE statements for version 1, from the generated `AgentCoreDatabase_Impl`. */
        val V1 = listOf(
            "CREATE TABLE IF NOT EXISTS `threads` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `contextJson` TEXT, `taint` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `items` (`rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `threadId` TEXT NOT NULL, `atMs` INTEGER NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `tool` TEXT, `ok` INTEGER, `attachmentsJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_items_threadId_atMs` ON `items` (`threadId`, `atMs`)",
            "CREATE VIRTUAL TABLE IF NOT EXISTS `items_fts` USING FTS4(`text` TEXT NOT NULL, content=`items`)",
            "CREATE TABLE IF NOT EXISTS `memories` (`rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `app` TEXT, `appVersion` TEXT)",
            "CREATE TABLE IF NOT EXISTS `jobs` (`rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `prompt` TEXT NOT NULL, `everyMinutes` INTEGER, `dailyAtMinute` INTEGER, `nextRunMs` INTEGER NOT NULL, `lastResult` TEXT)",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_items_fts_AFTER_INSERT AFTER INSERT ON `items` BEGIN INSERT INTO `items_fts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END",
        )
    }
}
