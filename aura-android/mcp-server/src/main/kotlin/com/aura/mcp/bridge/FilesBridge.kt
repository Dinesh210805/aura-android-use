package com.aura.mcp.bridge

/**
 * Port into the host app's file-discovery facilities (MediaStore-backed).
 *
 * Lets the agent answer "open my resume", "show the last photo", "play that
 * download" deterministically: `find_files` queries the on-device media index
 * (name / kind / recency), `open_file` fires the returned `content://media/…`
 * URI through a guarded VIEW intent. Without this plane the only route to a
 * file was gesture-navigating the Files app.
 *
 * Scope contract: only `content://media/` URIs may be opened — implementations
 * must refuse anything else (arbitrary content providers are an exfiltration
 * surface; `file:` URIs are blocked platform-wide anyway).
 */
interface FilesBridge {

    /**
     * Search indexed files. [query] filters by display-name substring (null =
     * any), [kind] is one of `image|video|audio|document|any` (null = any),
     * newest first, at most [limit] results. Never throws.
     */
    suspend fun findFiles(query: String?, kind: String?, limit: Int): FileSearchResult

    /** Open a `content://media/…` URI from [findFiles] in its default viewer app. */
    fun openFile(uri: String): OpenUriResult
}

/**
 * Outcome of a [FilesBridge.findFiles] call.
 *
 * @param status `ok`, `permission_denied` (media permissions not granted), or
 *   `invalid_kind` (unsupported [kind] argument)
 * @param files matches, newest first
 * @param note optional caveat for the agent (e.g. media-only coverage)
 */
data class FileSearchResult(
    val status: String,
    val files: List<FileEntry>,
    val note: String? = null,
) {
    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_PERMISSION_DENIED = "permission_denied"
        const val STATUS_INVALID_KIND = "invalid_kind"
    }
}

/**
 * One indexed file.
 *
 * @param uri `content://media/…` URI — pass verbatim to `open_file`
 * @param displayName file name with extension
 * @param mimeType MIME type when known
 * @param sizeBytes file size, -1 when unknown
 * @param modifiedEpochMs last-modified time (epoch millis), 0 when unknown
 */
data class FileEntry(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
)
