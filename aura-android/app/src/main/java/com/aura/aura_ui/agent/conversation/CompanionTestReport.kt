package com.aura.aura_ui.agent.conversation

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Observation-plane encoder for the debug adb harness. Turns one harness outcome into a stable,
 * always-parseable JSON line that an off-device agent (Claude Code) reads back via
 * `adb run-as ... cat files/companion_test/<id>.json` and asserts on -- so the device becomes a
 * scriptable test target instead of something a human eyeballs. Pure: no IO, no Android, never throws.
 */
object CompanionTestReport {
    fun encode(
        action: String,
        request: String,
        ok: Boolean,
        summary: String,
        toolCalls: List<String>,
        elapsedMs: Long,
        nowMs: Long,
    ): String = buildJsonObject {
        put("action", action)
        put("request", request)
        put("ok", ok)
        put("summary", summary)
        putJsonArray("toolCalls") { toolCalls.forEach { add(it) } }
        put("elapsedMs", elapsedMs)
        put("ts", nowMs)
    }.toString()
}
