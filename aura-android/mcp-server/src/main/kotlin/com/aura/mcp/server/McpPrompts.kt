package com.aura.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * MCP **Prompts** — reusable, parameterised workflow templates a client can
 * surface (typically as slash-commands). They are the anti-wander centerpiece:
 * instead of the agent inventing its own tool sequence, it can invoke a vetted
 * runbook that already encodes the perceive→act→verify, deep-link-first,
 * policy-aware discipline.
 */
internal fun Server.registerPrompts() {
    addPrompt(
        name = "automate_task",
        description = "Plan and run a phone-automation task end-to-end with the right tools, safely.",
        arguments = listOf(
            PromptArgument(
                name = "goal",
                description = "What to accomplish, e.g. 'open Spotify and play Discover Weekly'.",
                required = true,
            ),
        ),
    ) { request ->
        val goal = request.arguments?.get("goal").orEmptyPlaceholder()
        GetPromptResult(
            description = "Guided automation runbook",
            messages = listOf(
                PromptMessage(role = Role.User, content = TextContent(text = automateTaskRunbook(goal))),
            ),
        )
    }

    addPrompt(
        name = "open_app",
        description = "Open an app by name the reliable way: resolve → policy-check → launch/deep-link → wait → perceive.",
        arguments = listOf(
            PromptArgument(
                name = "app_name",
                description = "Human-readable app name, e.g. 'Spotify'.",
                required = true,
            ),
        ),
    ) { request ->
        val app = request.arguments?.get("app_name").orEmptyPlaceholder()
        GetPromptResult(
            description = "Open-app runbook",
            messages = listOf(
                PromptMessage(role = Role.User, content = TextContent(text = openAppRunbook(app))),
            ),
        )
    }
}

/**
 * A device skill exposed as an MCP prompt (spec 2026-07-17 context delivery).
 * The host app supplies these from its skill store at server build; clients
 * surface them as slash-commands (Claude Code: /mcp__aura__<name>).
 */
data class PromptSkill(val name: String, val description: String, val body: String)

/** Register each skill as a zero-argument prompt returning its playbook body. */
internal fun Server.registerSkillPrompts(skills: List<PromptSkill>) {
    skills.forEach { skill ->
        val name = promptSafeName(skill.name) ?: return@forEach
        addPrompt(
            name = name,
            description = skill.description.take(200),
            arguments = emptyList(),
        ) {
            GetPromptResult(
                description = skill.description.take(200),
                messages = listOf(PromptMessage(role = Role.User, content = TextContent(text = skill.body))),
            )
        }
    }
}

/** Prompt names must be simple identifiers; a skill with nothing usable is skipped. */
internal fun promptSafeName(raw: String): String? =
    raw.trim().lowercase()
        .replace(Regex("""[\s/]+"""), "-")
        .replace(Regex("""[^a-z0-9_-]"""), "")
        .take(64)
        .takeIf { it.isNotBlank() }

private fun String?.orEmptyPlaceholder(): String = this?.takeIf { it.isNotBlank() } ?: "<value>"

private fun automateTaskRunbook(goal: String): String = """
    Goal: $goal

    Drive the AURA device with this loop. ONE consequential action per turn; the
    screen is ground truth, so re-read it each step.

    1. Identify the target app. If you only have a name, call lookup_app.
    2. Prefer a DEEP LINK: list_app_deeplinks(package) → if an entry matches the
       goal, resolve_deeplink then open_deeplink(uri, package). This skips fragile
       gesture navigation entirely.
    3. Otherwise launch_app(package), then wait_for, then perceive_screen.
    4. For each UI step: perceive_screen → pick the numbered som_id for your
       target → act with ONE gesture → verify_action(expected=...). Re-perceive
       after every navigation; coordinates from before are stale.
    5. After typing a search query — or into a field with a suggestion/autocomplete
       list (e.g. a Gmail To/Cc recipient) — press_enter to submit / commit the top
       suggestion into a chip. Never re-tap the field; if Enter doesn't take, read_screen
       and tap the suggestion's som_id.
    6. SAFETY (hard-blocked, cannot be overridden): banking/payment apps,
       authenticator/password managers, and typing card numbers / PINs / SSNs /
       passwords. If the goal requires one of these, stop and tell the user to do
       it themselves. Check uncertain targets with validate_action first.
    7. Stop and ask the user if two verifications fail, or before anything
       irreversible (sending money, deleting data) they didn't explicitly request.
""".trimIndent()

private fun openAppRunbook(app: String): String = """
    Open "$app" reliably:
    1. lookup_app(app_name="$app") → resolve the package name.
    2. validate_action(target="$app") → if blocked, stop and tell the user (the
       device refuses banking/payment and authenticator/password apps).
    3. If you need a specific screen inside the app, prefer
       list_app_deeplinks(package) → open_deeplink; otherwise launch_app(package).
    4. wait_for, then perceive_screen to confirm the app is foregrounded and fully
       loaded before the next action.
""".trimIndent()
