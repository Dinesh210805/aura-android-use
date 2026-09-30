package com.aura.aura_ui.agent.conversation

import com.aura.mcp.bridge.MediaBridge
import com.aura.mcp.bridge.MediaCommand
import com.aura.mcp.bridge.SpecialAccessState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The FAST lane: device operations Live dispatches itself, with no brain-lane hop.
 *
 * ### What earns a place here
 *
 * One deterministic call, no judgment, no screen reading, answering in milliseconds. Turning on
 * the flashlight or reading the clock should not cost a round-trip to a reasoning model, and a
 * user who says "torch on" expects it before they finish the sentence.
 *
 * Web search, notifications and system intents used to live here too. They were moved to
 * `ask_aura` because none of them meet that bar: searching is knowledge work, `notification_action`
 * can SEND a message, and `system_intent` was the tool whose description had to argue with the
 * model about its own boundaries. All three are still reachable — the action plane mounts the same
 * bridges — they simply go through a model that can reason about whether they were the right
 * choice.
 *
 * Depends only on `:mcp-server` bridge *interfaces*, so it unit-tests with fakes and no Android.
 */
class CompanionDirectTools(
    private val media: MediaBridge,
    private val deviceContext: DeviceContextProvider,
    private val deviceControls: DeviceControls,
) : CompanionToolHandler {

    private val owned = setOf(
        LiveToolDeclarations.DEVICE_CONTEXT,
        LiveToolDeclarations.MEDIA_CONTROL,
        LiveToolDeclarations.DEVICE_SETTINGS,
        LiveToolDeclarations.DEVICE_ACTION,
    )

    fun handles(name: String): Boolean = name in owned

    // The escalation sink is ignored on purpose: every tool here is a single deterministic device
    // call that returns in milliseconds. Nothing in this lane can outlast a conversational turn.
    override suspend fun handle(call: CompanionToolCall, escalate: EscalationSink): CompanionToolResult =
        runCatching {
            when (call.name) {
                LiveToolDeclarations.DEVICE_CONTEXT -> deviceContext()
                LiveToolDeclarations.MEDIA_CONTROL -> mediaControl(call.args)
                LiveToolDeclarations.DEVICE_SETTINGS -> deviceSettings(call.args)
                LiveToolDeclarations.DEVICE_ACTION -> deviceAction(call.args)
                else -> error("not a direct tool: ${call.name}")
            }
        }.getOrElse { errorResult("${call.name} failed: ${it.message}") }

    private suspend fun deviceContext(): CompanionToolResult =
        CompanionToolResult(deviceContext.snapshot().toSpokenSummary())

    private suspend fun mediaControl(args: JsonObject): CompanionToolResult {
        if (media.accessState() == SpecialAccessState.DISABLED) {
            return errorResult("I need Notification access to control media — enable it in Settings first.")
        }
        val command = MediaCommand.parse(str(args, "command"))
            ?: return errorResult("Unknown media command. Use play, pause, play_pause, next, previous, or stop.")
        val result = media.sendCommand(str(args, "package_name"), command)
        return if (result.success) {
            CompanionToolResult("Done.")
        } else {
            errorResult(result.error ?: "Couldn't control media — nothing seems to be playing.")
        }
    }

    private suspend fun deviceSettings(args: JsonObject): CompanionToolResult {
        val setting = str(args, "setting") ?: return errorResult("device_settings needs a 'setting'.")
        val r = deviceControls.applySetting(setting, str(args, "state"))
        return CompanionToolResult(r.spoken, isError = !r.ok)
    }

    private suspend fun deviceAction(args: JsonObject): CompanionToolResult {
        val action = str(args, "action") ?: return errorResult("device_action needs an 'action'.")
        val r = deviceControls.doAction(action)
        return CompanionToolResult(r.spoken, isError = !r.ok)
    }

    private fun errorResult(msg: String) = CompanionToolResult(msg, isError = true)

    private fun str(args: JsonObject, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
