package com.aura.aura_ui.mcp.bridge

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.aura.aura_ui.data.files.FileKindMapper
import com.aura.mcp.bridge.FileEntry
import com.aura.mcp.bridge.FileSearchResult
import com.aura.mcp.bridge.FilesBridge
import com.aura.mcp.bridge.OpenUriResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aura.aura_ui.services.startActivityAsAura

/**
 * `:app` binding for [FilesBridge] — MediaStore-backed search + guarded open.
 *
 * Search runs against `MediaStore.Files` (external volume): images, videos,
 * audio, and documents other apps have indexed (Downloads included). App-private
 * storage is invisible by design. On API 33+ any one granted `READ_MEDIA_*`
 * permission unlocks the query for those types; pre-33 it's
 * `READ_EXTERNAL_STORAGE` (already in the manifest).
 *
 * [openFile] enforces the port's scope contract: only `content://media/…` URIs
 * are fireable — an agent-constructed URI to some other content provider
 * (contacts, SMS, another app's FileProvider) is refused before any intent is
 * built. The viewer gets a one-off read grant, nothing more.
 */
class AppFilesBridge(context: Context) : FilesBridge {

    private val appContext = context.applicationContext

    override suspend fun findFiles(query: String?, kind: String?, limit: Int): FileSearchResult =
        withContext(Dispatchers.IO) {
            val prefixes = FileKindMapper.mimePrefixesFor(kind)
                ?: return@withContext FileSearchResult(
                    FileSearchResult.STATUS_INVALID_KIND,
                    emptyList(),
                    "kind '$kind' is not one of image|video|audio|document|any",
                )
            if (!hasReadPermission()) {
                return@withContext FileSearchResult(FileSearchResult.STATUS_PERMISSION_DENIED, emptyList())
            }

            runCatching { queryMediaStore(query, prefixes, limit) }
                .map { FileSearchResult(FileSearchResult.STATUS_OK, it, MEDIA_ONLY_NOTE) }
                .getOrElse {
                    Log.w(TAG, "find_files query failed: ${it.message}")
                    FileSearchResult(FileSearchResult.STATUS_OK, emptyList(), "query failed: ${it.message}")
                }
        }

    override fun openFile(uri: String): OpenUriResult {
        val parsed = runCatching { Uri.parse(uri.trim()) }.getOrNull()
            ?: return OpenUriResult(false, null, "malformed uri")
        // Port contract: media-store URIs only. Anything else could point the
        // VIEW intent at another app's provider (contacts, SMS, FileProvider).
        if (parsed.scheme != "content" || parsed.authority != MediaStore.AUTHORITY) {
            return OpenUriResult(false, null, "only content://media/ URIs from find_files can be opened")
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = parsed
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val target = intent.resolveActivity(appContext.packageManager)
            ?: return OpenUriResult(false, null, "no app can open this file type")

        return runCatching {
            appContext.startActivityAsAura(intent)
            Log.i(TAG, "Opened file in ${target.packageName}")
            OpenUriResult(true, target.packageName, null)
        }.getOrElse { t ->
            OpenUriResult(false, null, (t.message ?: "open failed").take(120))
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun hasReadPermission(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.any {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun queryMediaStore(query: String?, mimePrefixes: List<String>, limit: Int): List<FileEntry> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )

        val selection = StringBuilder()
        val args = mutableListOf<String>()
        if (!query.isNullOrBlank()) {
            selection.append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            args.add("%${query.trim()}%")
        }
        if (mimePrefixes.isNotEmpty()) {
            if (selection.isNotEmpty()) selection.append(" AND ")
            selection.append(
                mimePrefixes.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?"
                },
            )
            mimePrefixes.forEach { args.add("$it%") }
        } else {
            // No kind filter: still require a MIME type so directories and
            // pending/trashed placeholder rows don't pollute the results.
            if (selection.isNotEmpty()) selection.append(" AND ")
            selection.append("${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL")
        }

        val out = mutableListOf<FileEntry>()
        appContext.contentResolver.query(
            collection,
            projection,
            selection.toString().ifEmpty { null },
            args.toTypedArray().takeIf { it.isNotEmpty() },
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (cursor.moveToNext() && out.size < limit) {
                val name = cursor.getString(nameCol) ?: continue
                out.add(
                    FileEntry(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)).toString(),
                        displayName = name,
                        mimeType = cursor.getString(mimeCol),
                        sizeBytes = if (cursor.isNull(sizeCol)) -1 else cursor.getLong(sizeCol),
                        modifiedEpochMs = cursor.getLong(dateCol) * 1000L,
                    ),
                )
            }
        }
        return out
    }

    private companion object {
        const val TAG = "AppFilesBridge"
        const val MEDIA_ONLY_NOTE =
            "Coverage: media index only (images/video/audio + indexed documents " +
                "and downloads). App-private files are not searchable."
    }
}
