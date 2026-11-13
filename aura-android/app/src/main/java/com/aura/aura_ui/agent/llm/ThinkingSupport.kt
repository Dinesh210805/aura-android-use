package com.aura.aura_ui.agent.llm

import com.aura.aura_ui.agent.llm.ReasoningLevel.DEFAULT
import com.aura.aura_ui.agent.llm.ReasoningLevel.HIGH
import com.aura.aura_ui.agent.llm.ReasoningLevel.LOW
import com.aura.aura_ui.agent.llm.ReasoningLevel.MEDIUM
import com.aura.aura_ui.agent.llm.ReasoningLevel.OFF
import kotlin.math.abs

/**
 * Which thinking levels a model accepts: the one rule that the chat's thinking menu, Settings →
 * Model and the request wire all follow, so no control ever offers a level the provider rejects.
 *
 * - Contract: pure. [levelsFor] never returns an empty list; exactly `[DEFAULT]` means the model
 *   has no thinking control and the menu hides. The list is in display order.
 *   - Gemini 3.x: Auto, Low, High. Its `thinking_level` has no Off and no Medium.
 *   - Gemini 2.5 flash/lite and OpenRouter: Off is offered (budget 0 / `enabled:false`).
 *   - OpenAI o-series / GPT-5 and Groq reasoning models: no Off; they always reason.
 *   - Claude (native wire): Off, Low, Medium, High. The API default is no thinking, so there is
 *     no separate Auto.
 * - Change together: [GenerationParams] (each level on the OpenAI-compatible wire),
 *   [AnthropicNative.params] (each level for Claude), `ModelCatalog.reasoningFor` / `canDisableFor`.
 */
object ThinkingSupport {

    /**
     * @param reasoning whether the model thinks, when its listing said so (OpenRouter's
     *   `supported_parameters` is exact). Null falls back to the id rules in [ModelCatalog].
     * @param canDisable the same, for turning thinking off.
     */
    fun levelsFor(endpoint: LlmEndpoint, modelId: String, reasoning: Boolean? = null, canDisable: Boolean? = null): List<ReasoningLevel> {
        if (!controllable(endpoint)) return listOf(DEFAULT)
        val thinks = reasoning ?: ModelCatalog.reasoningCapableForWire(endpoint, modelId)
        if (!thinks) return listOf(DEFAULT)
        val off = canDisable ?: ModelCatalog.canDisableFor(endpoint, modelId)
        return when {
            endpoint.wire == ChatWire.ANTHROPIC_NATIVE -> listOf(OFF, LOW, MEDIUM, HIGH)
            endpoint.profile.reasoningRequestStyle == ReasoningRequestStyle.GEMINI_THINKING_CONFIG &&
                GeminiThinkingWire.usesThinkingLevel(modelId) -> listOf(DEFAULT, LOW, HIGH)
            else -> listOfNotNull(DEFAULT, OFF.takeIf { off }, LOW, MEDIUM, HIGH)
        }
    }

    /**
     * [level] when [levels] has it, otherwise the nearest level that is allowed (ties go to the
     * cheaper one). Used when the model changes under a saved choice.
     */
    fun clamp(level: ReasoningLevel, levels: List<ReasoningLevel>): ReasoningLevel {
        if (level in levels) return level
        if (level == DEFAULT) return levels.first()
        val graded = levels.filter { it != DEFAULT }
        if (graded.isEmpty()) return DEFAULT
        return graded.minWith(compareBy<ReasoningLevel>({ abs(rank(it) - rank(level)) }, { rank(it) }))
    }

    /** The cheapest level [levels] allows, for side jobs such as chat titles. */
    fun lightest(levels: List<ReasoningLevel>): ReasoningLevel = when {
        OFF in levels -> OFF
        LOW in levels -> LOW
        else -> levels.first()
    }

    /**
     * Whether a custom temperature may go out with [level]. OpenAI's reasoning models reject any
     * temperature but the default, and Claude requires the default while thinking.
     */
    fun temperatureAllowed(endpoint: LlmEndpoint, modelId: String, level: ReasoningLevel): Boolean = when {
        endpoint.wire == ChatWire.ANTHROPIC_NATIVE -> level == OFF || level == DEFAULT
        endpoint.reasoningInference == ReasoningInference.OPENAI_OSERIES -> !ModelCatalog.openAiReasoning(modelId)
        else -> true
    }

    fun label(level: ReasoningLevel): String = when (level) {
        DEFAULT -> "Auto"
        OFF -> "Off"
        LOW -> "Low"
        MEDIUM -> "Medium"
        HIGH -> "High"
    }

    private fun controllable(endpoint: LlmEndpoint): Boolean =
        endpoint.wire == ChatWire.ANTHROPIC_NATIVE || endpoint.profile.reasoningRequestStyle != ReasoningRequestStyle.NONE

    private fun rank(level: ReasoningLevel): Int = when (level) {
        OFF -> 0
        LOW -> 1
        DEFAULT, MEDIUM -> 2
        HIGH -> 3
    }
}
