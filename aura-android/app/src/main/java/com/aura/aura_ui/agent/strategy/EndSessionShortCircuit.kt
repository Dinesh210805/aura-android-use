package com.aura.aura_ui.agent.strategy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Ends the Koog loop the moment a successful `end_session` executes.
 *
 * The tool has soft server semantics (the MCP server keeps listening), but for
 * the on-device agent it is the model's explicit "I am done" — yet the stock
 * loop only terminates on a plain-TEXT assistant turn, so every end_session
 * used to cost at least one more LLM round-trip to say "done" in prose. Worse,
 * a small model that saw the loop continue after ending concluded it was being
 * asked for more verification and spent extra perceive/end cycles (the logged
 * 7-call "Open WhatsApp" run: calls 5–7 were all post-end_session).
 *
 * Pure decision, unit-tested in `EndSessionShortCircuitTest`; the vision
 * strategy node consumes it to synthesize the final assistant turn from the
 * tool's own `reason` — which the prompt tells the model to write as its final
 * user-facing answer.
 */
internal object EndSessionShortCircuit {

    private const val END_SESSION_TOOL = "end_session"

    /** Spoken when a successful end_session carried no readable reason. */
    const val DEFAULT_FINAL_ANSWER = "Done — the task is finished."

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The final spoken answer when [toolName]'s result closes the run, or null
     * to keep looping. Only a SUCCESSFUL end_session closes: an ActionGuard 1e
     * denial arrives as `isError=true` and must go back to the model.
     */
    fun finalAnswerFrom(toolName: String, resultJson: JsonObject?): String? {
        if (toolName != END_SESSION_TOOL || resultJson == null) return null
        if (resultJson["isError"]?.jsonPrimitive?.booleanOrNull == true) return null
        val reason = firstTextPayload(resultJson)
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.get("reason")?.jsonPrimitive?.contentOrNull
        return reason?.takeIf { it.isNotBlank() } ?: DEFAULT_FINAL_ANSWER
    }

    /** First `content[type=text].text` block of an encoded CallToolResult. */
    private fun firstTextPayload(resultJson: JsonObject): String? =
        (resultJson["content"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.get("text")?.jsonPrimitive?.contentOrNull
}
