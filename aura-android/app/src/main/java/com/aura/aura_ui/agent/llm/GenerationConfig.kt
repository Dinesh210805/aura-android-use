package com.aura.aura_ui.agent.llm

import kotlinx.serialization.Serializable

/**
 * User-chosen "how much should the model think" level (D2 piece 2). Mapped to each provider's
 * own wire dialect by [GenerationParams], keyed on the endpoint's [ProviderProfile.reasoningRequestStyle].
 * [DEFAULT] means "don't touch the request" — the provider's own default reasoning applies.
 */
@Serializable
enum class ReasoningLevel { DEFAULT, OFF, LOW, MEDIUM, HIGH }

/**
 * Per-endpoint generation controls the user can set in Settings → Brain (D2 piece 2):
 * thinking level + sampling temperature.
 *
 * **Invariant:** a config at its defaults ([isDefault]) produces **no wire change at all** — the
 * request is byte-identical to the pre-D2-piece-2 behavior. Only an explicit user choice is spliced
 * onto the request. Temperature is deliberately a nullable opt-in (null = provider default) because
 * this is an agent that taps real UI: raising randomness raises mis-tap risk, so the UI defaults it
 * low and only sends it once the user moves the slider.
 */
@Serializable
data class GenerationConfig(
    val reasoning: ReasoningLevel = ReasoningLevel.DEFAULT,
    val temperature: Double? = null,
) {
    /** True when everything is at provider defaults → [GenerationParams] is a no-op. */
    val isDefault: Boolean
        get() = reasoning == ReasoningLevel.DEFAULT && temperature == null
}
