package com.aura.aura_ui.data.files

/**
 * Maps the `find_files` `kind` argument to MediaStore MIME-type prefixes.
 * Pure — JVM-tested in `FileKindMapperTest`.
 */
object FileKindMapper {

    private val KINDS: Map<String, List<String>> = mapOf(
        "image" to listOf("image/"),
        "video" to listOf("video/"),
        "audio" to listOf("audio/"),
        "document" to listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "text/",
        ),
        "any" to emptyList(),
    )

    /**
     * MIME prefixes for [kind]; empty list = no MIME filter (kind `any` or
     * absent); null = unsupported kind (tool should reject with `invalid_kind`).
     */
    fun mimePrefixesFor(kind: String?): List<String>? {
        val k = kind?.trim()?.lowercase().takeUnless { it.isNullOrEmpty() } ?: "any"
        return KINDS[k]
    }
}
