package com.aura.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The refusal for "this tool needs something nobody has given it yet".
 *
 * ### Where this sits
 *
 * [scopedTool] already owns a family of refusal codes, each a JSON object carrying `success`, an
 * `error` code, a `message`, and a `hint` naming the next action:
 *
 * | code | means |
 * |---|---|
 * | `scope_denied` / `policy_blocked` / `foreground_blocked` | this will never work |
 * | `paused_by_user` | this works fine in a minute |
 * | **`resource_required`** | this works once someone supplies something |
 *
 * ### Why [Fixer] is the load-bearing field
 *
 * Before this existed, three tools hand-rolled `{permission_required: true}` — and that one flag
 * covered two opposite situations. `get_screenshot` is missing a permission the agent can go and
 * get, by calling `request_screen_capture_permission`, which is right there in the same action
 * space. `web_search` is missing a Tavily API key, which **no tool can supply**: only the person
 * holding the phone can, in Settings.
 *
 * Collapsing those is the same defect the control lock had, and
 * [pausedByUserResult]'s KDoc already spells out the cost: a client that cannot separate the
 * recoverable from the permanent either gives up on something it could have fixed, or hammers
 * away at something it cannot. On device that was five identical calls into a held lock.
 *
 * So the model is told **who** can fix it, not merely that something is wrong.
 *
 * ### Why the hint is mandatory
 *
 * The default reaction to an error is a retry. For [Fixer.USER] a retry is always wrong, and the
 * only useful move is to say a specific sentence to a specific person. A refusal that does not
 * carry that sentence leaves the user with a failed task and no reason — which is the state this
 * whole change exists to end. [resourceRequiredResult] refuses to build without one.
 */
internal object ResourceRequired {

    /** Who can supply the missing resource. Exactly two answers, and they imply opposite actions. */
    enum class Fixer(val id: String) {
        /**
         * A tool in this same action space acquires it — the hint names that tool. Retrying the
         * original call **after** that tool succeeds is the correct behaviour.
         */
        AGENT("agent"),

        /**
         * No in-run path exists. The hint is a sentence to say to the user. Retrying is always
         * wrong, and so is looking for a workaround.
         */
        USER("user"),
    }

    /**
     * Stable ids for the things a tool can be missing.
     *
     * Ids rather than prose so the app side can branch without pattern-matching sentences, and so
     * a reworded hint never silently changes behaviour.
     */
    object Resource {
        /** Tavily API key for `web_search`. [Fixer.USER] — no tool can mint one. */
        const val TAVILY_API_KEY = "tavily_api_key"

        /** MediaProjection consent. [Fixer.AGENT] — `request_screen_capture_permission` gets it. */
        const val SCREEN_CAPTURE = "screen_capture"

        /** AURA's own IME, for apps with no editable accessibility node. [Fixer.USER] — a system toggle. */
        const val AURA_KEYBOARD = "aura_keyboard"
    }

    /** The error code that marks this family, on the wire and in the app-side reader. */
    const val ERROR_CODE = "resource_required"
}

/**
 * Build the `resource_required` refusal.
 *
 * @param toolName the tool that could not run.
 * @param resource stable id from [ResourceRequired.Resource].
 * @param fixableBy who can supply it — see [ResourceRequired.Fixer].
 * @param message one plain sentence naming what is missing. No jargon: for
 *   [ResourceRequired.Fixer.USER] this is read out to a person.
 * @param hint the next action. Must be non-blank — see the KDoc on [ResourceRequired].
 */
internal fun resourceRequiredResult(
    toolName: String,
    resource: String,
    fixableBy: ResourceRequired.Fixer,
    message: String,
    hint: String,
): CallToolResult {
    require(hint.isNotBlank()) {
        "resource_required for $toolName has no hint — an error with no next action is the bug " +
            "this shape exists to prevent"
    }
    return CallToolResult(
        content = listOf(
            TextContent(
                buildJsonObject {
                    put("success", false)
                    put("error", ResourceRequired.ERROR_CODE)
                    put("tool", toolName)
                    put("resource", resource)
                    put("fixable_by", fixableBy.id)
                    put("message", message)
                    put("hint", hint)
                    // Kept for one release: `permission_required` was the flag three tools used
                    // before this shape existed. Emitted only for the agent-fixable case, which
                    // is what it always actually meant — a user-fixable miss was never a
                    // permission the agent could request, and any reader treating it as one
                    // would retry forever.
                    if (fixableBy == ResourceRequired.Fixer.AGENT) {
                        put("permission_required", true)
                    }
                }.toString(),
            ),
        ),
        isError = true,
    )
}
