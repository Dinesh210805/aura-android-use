package com.aura.aura_ui.agentcore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A structured result the chat draws as a card instead of text: files, contacts, a result.
 *
 * - Contract: pure Kotlin. A card is stored as one [ItemKind.CARD] row whose text is
 *   [encode]; [decode] returns null for anything it cannot read, and the chat skips that row.
 * - Why cards are built from tool results and not written by the model: the harness already has
 *   the exact rows the tool returned, so a card costs no tokens and cannot show a file or a
 *   number the tool did not return.
 * - Change together: `ChatCards.kt` draws every subclass; `CardsTest`.
 */
@Serializable
sealed class ChatCard {

    /** Files `find_files` returned, newest first. */
    @Serializable
    @SerialName("files")
    data class Files(val files: List<FileItem>) : ChatCard()

    /** Contacts `resolve_contact` returned. [choose] is true when the user has to pick one. */
    @Serializable
    @SerialName("contacts")
    data class Contacts(val people: List<Person>, val choose: Boolean = false) : ChatCard()

    /** The web pages an answer was read from, as tappable chips under it. */
    @Serializable
    @SerialName("sources")
    data class Sources(val links: List<Link>) : ChatCard()

    @Serializable
    data class Link(val title: String, val url: String)

    /** What a scheduled task or a notification digest found. */
    @Serializable
    @SerialName("result")
    data class Result(val title: String, val lines: List<String>, val source: String = "") : ChatCard()

    @Serializable
    data class FileItem(
        /** A `content://media/…` URI; opening it needs no extra permission. */
        val uri: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long = 0,
        val modifiedMs: Long = 0,
    ) {
        val isVisual: Boolean get() = mimeType.startsWith("image/") || mimeType.startsWith("video/")
    }

    @Serializable
    data class Person(val contactId: Long = 0, val name: String, val number: String)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_ITEMS = 12

        fun decode(stored: String): ChatCard? = runCatching { json.decodeFromString(serializer(), stored) }.getOrNull()

        /**
         * The card for one successful tool result, or null when the result has nothing to show.
         * [resultJson] is the tool's own JSON payload, before fencing.
         */
        fun fromToolResult(tool: String, resultJson: String): ChatCard? = runCatching {
            val root = json.parseToJsonElement(resultJson).jsonObject
            when (tool) {
                "find_files" -> root["files"]?.jsonArray.orEmpty().take(MAX_ITEMS).mapNotNull { element ->
                    val file = element.jsonObject
                    FileItem(
                        uri = file.text("uri") ?: return@mapNotNull null,
                        name = file.text("display_name") ?: return@mapNotNull null,
                        mimeType = file.text("mime_type").orEmpty(),
                        sizeBytes = file.long("size_bytes"),
                        modifiedMs = file.long("modified_epoch_ms"),
                    )
                }.takeIf { it.isNotEmpty() }?.let(::Files)

                "resolve_contact" -> root["candidates"]?.jsonArray.orEmpty().take(MAX_ITEMS).mapNotNull { element ->
                    val person = element.jsonObject
                    Person(
                        contactId = person.long("contact_id"),
                        name = person.text("display_name") ?: return@mapNotNull null,
                        number = person.text("phone_number") ?: return@mapNotNull null,
                    )
                }.takeIf { it.isNotEmpty() }?.let { Contacts(it, choose = root.text("status") == "disambiguate") }

                else -> null
            }
        }.getOrNull()

        private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0
    }
}
