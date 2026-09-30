package com.aura.aura_ui.agent.mcpbridge.hooks

import com.aura.aura_ui.agent.mcpbridge.ToolMeta
import com.aura.aura_ui.agent.mcpbridge.ToolMetaTable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Declarative "confirm before destructive actions" seam, driven by a Settings toggle
 * ([enabled]). When on, a tool flagged destructive in [ToolMetaTable] yields a
 * [PreToolDecision.Confirm] so the chain asks the user before dispatch.
 *
 * Scope: tool-level destructiveness only. Today that is `notification_action`, whose reply is
 * sent the instant it fires. Contextual commits (tapping "Send" or "Place order" inside an app)
 * are invisible from a tap's arguments and are governed by the doctrine's safety section.
 *
 * The question is shown and spoken to the USER, so it says what will happen in their terms —
 * never a tool name.
 */
class ConfirmDestructiveHook(
    private val enabled: () -> Boolean,
    private val metaFor: (String) -> ToolMeta = ToolMetaTable::metaFor,
) : PreToolHook {
    override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext): PreToolDecision =
        if (enabled() && metaFor(toolName).destructive) {
            PreToolDecision.Confirm(questionFor(args))
        } else {
            PreToolDecision.Proceed
        }

    internal fun questionFor(args: JsonObject): String {
        val reply = args["reply_text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return if (reply != null) "Send this reply now: \"${reply.take(REPLY_PREVIEW)}\"?" else GENERIC
    }

    private companion object {
        const val REPLY_PREVIEW = 120
        const val GENERIC = "This can't be undone — go ahead?"
    }
}
