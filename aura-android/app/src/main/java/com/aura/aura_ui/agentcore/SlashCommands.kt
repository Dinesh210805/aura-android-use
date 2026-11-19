package com.aura.aura_ui.agentcore

/** What an action command does in the chat screen; text commands have none. */
enum class SlashAction { NEW, FIND, MODEL, FILES, STOP }

/**
 * One entry in the chat's `/` menu.
 *
 * - Contract: [takesText] commands are filled into the composer for the user to finish
 *   (`/goal …`); the others run [action] at once and clear the composer.
 */
data class SlashCommand(val name: String, val hint: String, val takesText: Boolean, val action: SlashAction?)

/**
 * The commands the chat's `/` menu offers, and which ones match what is typed.
 *
 * - Contract: pure; [matching] is empty unless the text is a slash and letters with no space, so
 *   the menu closes once the user types past the command.
 * - Change together: `Goals.kt` and `Btw.kt` parse the two text commands; their names come from
 *   `Goals.PREFIX` and `Btw.PREFIX` so the menu cannot offer a spelling the parsers miss.
 */
object SlashCommands {
    val ALL = listOf(
        SlashCommand("/new", "Start a new chat", takesText = false, action = SlashAction.NEW),
        SlashCommand("/find", "Search this chat", takesText = false, action = SlashAction.FIND),
        SlashCommand("/model", "Change the model", takesText = false, action = SlashAction.MODEL),
        SlashCommand("/files", "Files in this chat", takesText = false, action = SlashAction.FILES),
        SlashCommand("/stop", "Stop the running task", takesText = false, action = SlashAction.STOP),
        SlashCommand(Goals.PREFIX.trimEnd(), "Keep working until a goal is done", takesText = true, action = null),
        SlashCommand(Btw.PREFIX.trimEnd(), "Quick side question; doesn't interrupt the task", takesText = true, action = null),
    )

    fun matching(text: String, available: (SlashCommand) -> Boolean): List<SlashCommand> {
        if (!text.startsWith("/") || text.any { it.isWhitespace() }) return emptyList()
        val typed = text.drop(1).lowercase()
        return ALL.filter { it.name.drop(1).startsWith(typed) && available(it) }
    }
}
