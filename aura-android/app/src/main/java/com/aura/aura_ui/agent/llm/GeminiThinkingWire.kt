package com.aura.aura_ui.agent.llm

/**
 * The one fact both Gemini thinking shims need: **which thinking field a model speaks.** Gemini's
 * OpenAI-compat surface changed the control between generations, and sending the wrong one 400s
 * ("Thinking level is not supported for this model" — verified live 2026-07-13 against gemini-2.5-flash):
 *
 *  - **Gemini 3.x** → `thinking_config.thinking_level` (`"low"`/`"high"`).
 *  - **Gemini 2.5-era** → `thinking_config.thinking_budget` (integer tokens; `0` disables).
 *
 * Used by both [GeminiThinkingConfig] (the trace shim that enables thought summaries) and
 * [GenerationParams] (the user's thinking level), so they can never disagree on the field.
 */
internal object GeminiThinkingWire {

    private val GENERATION = Regex("""\bgemini-(\d+(?:\.\d+)?)""")

    /** True for Gemini 3.x (uses `thinking_level`). Unknown/absent model → true (legacy default). */
    fun usesThinkingLevel(modelId: String?): Boolean {
        val gen = modelId?.let { GENERATION.find(it.lowercase())?.groupValues?.get(1)?.toDoubleOrNull() }
            ?: return true
        return gen >= 3.0
    }

    /** `thinking_budget` band for a level on 2.5-era models (`0` disables; a small positive enables). */
    fun budgetFor(level: ReasoningLevel): Int = when (level) {
        ReasoningLevel.OFF -> 0
        ReasoningLevel.LOW -> 1024
        ReasoningLevel.MEDIUM -> 8192
        ReasoningLevel.HIGH -> 24576
        ReasoningLevel.DEFAULT -> -1 // dynamic — DEFAULT returns before reaching here
    }
}
