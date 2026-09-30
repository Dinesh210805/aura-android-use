package com.aura.aura_ui.agent.conversation

import android.content.Context

/**
 * What this device's user asked AURA to call them.
 *
 * Null until they say so — the persona then speaks in plain second person rather
 * than guessing. Deliberately NOT derived from the Google account or contacts:
 * the display name attached to an account is frequently a legal name, an email
 * handle, or someone else entirely on a shared device, and reading it would also
 * pull a contacts/accounts permission into the first-run path for cosmetics.
 * Asking is both more accurate and cheaper in consent.
 */
object PersonaOwner {

    private const val PREFS = "aura_settings"
    private const val KEY_OWNER_NAME = "owner_name"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OWNER_NAME, null)
            ?.trim()
            ?.ifBlank { null }

    /** Pass null or blank to go back to the unnamed persona. */
    fun set(context: Context, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (name.isNullOrBlank()) remove(KEY_OWNER_NAME) else putString(KEY_OWNER_NAME, name.trim())
            }
            .apply()
    }
}
