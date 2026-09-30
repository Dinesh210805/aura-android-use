package com.aura.aura_ui.mcp.log

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Phase 10B — read-side repository for the session log directory.
 *
 * The active session is being written to by [McpSessionLogger]; this
 * class only reads. Sessions are returned newest-first so the list UI
 * doesn't have to sort.
 */
class McpSessionStore(context: Context) {

    private val rootDir: File = File(context.applicationContext.filesDir, "mcp_logs")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun listSessions(): List<SessionLog> {
        if (!rootDir.exists()) return emptyList()
        return rootDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir -> readMetadata(dir) }
            ?.sortedByDescending { it.startedAtMillis }
            .orEmpty()
    }

    fun readSession(sessionId: String): SessionLog? {
        val dir = File(rootDir, sessionId)
        if (!dir.isDirectory) return null
        return readMetadata(dir)
    }

    fun screenshotFile(sessionId: String, index: Int): File? =
        rawScreenshotFile(File(rootDir, sessionId), index)

    fun sessionDir(sessionId: String): File? =
        File(rootDir, sessionId).takeIf { it.isDirectory }

    /** The SoM-annotated image (blue/red numbered boxes) for a perception step, if captured. */
    fun somImageFile(sessionId: String, index: Int): File? =
        File(rootDir, "$sessionId/som/$index.png").takeIf { it.exists() }

    /** The gesture-annotated screenshot (tap crosshair / swipe arrow), if rendered. */
    fun annotatedFile(sessionId: String, index: Int): File? =
        File(rootDir, "$sessionId/annotated/$index.png").takeIf { it.exists() }

    fun deleteSession(sessionId: String): Boolean =
        File(rootDir, sessionId).deleteRecursively()

    fun deleteOlderThan(cutoffMillis: Long): Int {
        if (!rootDir.exists()) return 0
        var deleted = 0
        rootDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val session = readMetadata(dir) ?: return@forEach
            val ts = session.endedAtMillis ?: session.startedAtMillis
            if (ts < cutoffMillis) {
                if (dir.deleteRecursively()) deleted++
            }
        }
        return deleted
    }

    private fun readMetadata(dir: File): SessionLog? {
        val file = File(dir, "metadata.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<SessionLog>(file.readText()) }
            .onFailure { Log.w(TAG, "Failed to parse ${file.path}", it) }
            .getOrNull()
    }

    private companion object {
        const val TAG = "McpSessionStore"
    }
}
