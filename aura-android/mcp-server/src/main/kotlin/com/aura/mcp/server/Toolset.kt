package com.aura.mcp.server

/**
 * An optional group of tools a paired computer can be given. The on-device agent always gets [ALL].
 *
 * - Contract: [CORE] is always on. Every tool belongs to exactly one group, by [of]; a group may
 *   depend on core but never on another optional group, so text a tool emits only names tools
 *   that are registered with it.
 * - Why groups at all: some clients cap the tools they pass to the model (Cursor keeps ~40
 *   across every server) and drop the rest silently, so a computer that never browses should not
 *   spend 11 slots on the browser.
 * - Change together: a new notification or media tool goes in [ASSISTANT_TOOLS]; `ToolsetTest`
 *   fails otherwise.
 */
enum class Toolset {
    /** Screen, gestures, apps, deep links, system verbs, contacts, files, search, session. */
    CORE,

    /** Other apps' notifications and media sessions, read and driven without opening them. */
    ASSISTANT,

    /** AURA's own browser, driven at DOM level. */
    BROWSER,
    ;

    companion object {
        val ALL: Set<Toolset> = entries.toSet()

        /** What a newly paired computer gets: everything but the browser. */
        val DEFAULT_REMOTE: Set<Toolset> = setOf(CORE, ASSISTANT)

        private val ASSISTANT_TOOLS = setOf(
            "read_notifications", "notification_action", "dismiss_notification",
            "get_media_sessions", "media_control",
        )

        fun of(toolName: String): Toolset = when {
            toolName.startsWith("browser_") -> BROWSER
            toolName in ASSISTANT_TOOLS -> ASSISTANT
            else -> CORE
        }

        /**
         * Stored group names back to groups: unknown names are ignored and [CORE] is always added.
         * Null (nothing stored) is [DEFAULT_REMOTE].
         */
        fun parse(names: Collection<String>?): Set<Toolset> =
            names?.mapNotNull { name -> entries.firstOrNull { it.name == name } }?.toSet()?.plus(CORE)
                ?: DEFAULT_REMOTE
    }
}
