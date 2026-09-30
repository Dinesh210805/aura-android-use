package com.aura.aura_ui.agent.memory

import android.content.Context
import com.aura.aura_ui.agent.conversation.PersonaOwner

/**
 * The two things AURA must know about its user before it says a single word: what to call them,
 * and which languages it is allowed to speak.
 *
 * Deliberately **not** a [MemoryEntry]. Memory is model-writable, evictable and decayable — all
 * three are wrong for a profile the human typed into Settings. Copying the name into memory would
 * also fork the truth: edit it in Settings and the memory row would keep the stale one. So this is
 * a store of its own, rendered into the prompt instead, and [PersonaOwner] stays the single source
 * of truth for the name (it already has six call sites, incl. onboarding and DeviceRegistry).
 *
 * Languages live in the same `aura_settings` file. [DEFAULT_LANGUAGES] is a default *on read*,
 * never a write at install: writing it would make "the user cleared the list" indistinguishable
 * from "the user never set one".
 */
data class UserProfile(
    /** What they asked to be called, or null when they never said. */
    val name: String? = null,
    /** Languages AURA may speak, most-preferred first. */
    val languages: List<String> = DEFAULT_LANGUAGES,
) {
    companion object {
        /** Pre-filled until the user edits Settings → Memory. Order is preference order. */
        val DEFAULT_LANGUAGES = listOf("English", "Tamil")

        /** Guard rails: a language list is a short prompt line, not free-form storage. */
        const val MAX_LANGUAGES = 6
        const val MAX_LANGUAGE_LEN = 24
    }
}

/**
 * Parse a user-typed language list ("English, Tamil ,  tamil") into a clean, ordered, de-duplicated
 * list. Pure — unit-tested in `UserProfileTest`. Case-insensitive de-dup keeps the first spelling,
 * so the user's own capitalisation survives.
 */
fun parseLanguages(raw: String): List<String> {
    val seen = mutableSetOf<String>()
    return raw.split(',', '\n', '/', ';')
        .map { it.trim().take(UserProfile.MAX_LANGUAGE_LEN) }
        .filter { it.isNotEmpty() }
        .filter { seen.add(it.lowercase()) }
        .take(UserProfile.MAX_LANGUAGES)
}

/** Read/write the profile. Name delegates to [PersonaOwner]; languages live beside it. */
class UserProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): UserProfile = UserProfile(
        name = PersonaOwner.get(appContext),
        languages = prefs.getString(KEY_LANGUAGES, null)
            ?.let { parseLanguages(it) }
            ?: UserProfile.DEFAULT_LANGUAGES,
    )

    /** Pass null or blank to go back to the unnamed persona (see [PersonaOwner.set]). */
    fun setName(name: String?) = PersonaOwner.set(appContext, name)

    /**
     * Persist the language list. An empty list is stored as an empty string, not removed — that is
     * how "the user cleared it" stays distinguishable from "never set", which would silently
     * resurrect [UserProfile.DEFAULT_LANGUAGES].
     */
    fun setLanguages(languages: List<String>) {
        prefs.edit().putString(KEY_LANGUAGES, languages.joinToString(", ")).apply()
    }

    private companion object {
        /** Same file PersonaOwner uses — one profile, one place on disk. */
        const val PREFS = "aura_settings"
        const val KEY_LANGUAGES = "known_languages"
    }
}

/**
 * Renders the profile into the SYSTEM prompt. Safe there — unlike memory and learned hints, every
 * field is human-authored in Settings and no tool can write it, so it carries no injection surface
 * (M1: only model-/screen-derived text is confined to the user channel).
 *
 * Pure — unit-tested in `UserProfileTest`.
 */
object ProfileBlock {
    fun render(profile: UserProfile): String {
        val name = profile.name?.trim()?.ifBlank { null }
        val languages = profile.languages.filter { it.isNotBlank() }
        if (name == null && languages.isEmpty()) return ""

        return buildString {
            append("# Who you work for\n")
            if (name != null) {
                append("Their name is $name. Use it when addressing them; never invent a different name.\n")
            }
            if (languages.isNotEmpty()) {
                append("Languages they know: ${languages.joinToString(", ")}. ")
                append("Speak and write ONLY in these. Default to ${languages.first()}; ")
                if (languages.size > 1) {
                    append("if they use another language from that list, answer in that one. ")
                }
                append("Never switch to a language that is not on the list unless they explicitly ask.\n")
            }
        }.trimEnd()
    }
}
