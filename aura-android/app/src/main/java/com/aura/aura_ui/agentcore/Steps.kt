package com.aura.aura_ui.agentcore

import com.aura.aura_ui.agent.memory.PathStepSanitizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One phone action anchored on what it acts on, not where: `tap "Where to?"`, never `tap #12`.
 *
 * - Contract: the shape of a run's trace ([TurnState.trace]), of a skill's `steps:` block, and of
 *   `do_steps` input, so a successful run becomes a replayable skill without translation.
 * - [target] is the element's label, found again in a fresh screen each time; [text] is what to
 *   type; [args] holds the action's other plain arguments (`direction`, `app`, `key`).
 */
@Serializable
data class Step(
    val action: String,
    val target: String? = null,
    val text: String? = null,
    /** Words that must be on the screen after this step, or the chain stops. */
    val expect: String? = null,
    val args: Map<String, String> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val list = ListSerializer(serializer())

        /** Arguments a step keeps besides target and text: plain values that mean the same next run. */
        private val KEPT = setOf("app", "direction", "key", "url", "package", "action", "command", "value_kind")

        /** The step for a successful phone call; [label] is the tapped element's label, if any. */
        fun of(tool: String, args: JsonObject, label: String?): Step {
            fun s(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            return Step(
                action = tool,
                target = label?.takeIf { it.isNotBlank() },
                text = s("text") ?: s("value"),
                args = KEPT.mapNotNull { k -> s(k)?.let { k to it } }.toMap(),
            )
        }

        fun encode(steps: List<Step>): String = json.encodeToString(list, steps)

        /** Fails: an empty list for text that is not a step list. */
        fun decode(text: String?): List<Step> =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() }.orEmpty()

        /**
         * A trace made safe to keep in a skill: labels reduced to short plain tokens with personal
         * details scrubbed, typed text replaced by `<text>` for the model to fill in next time, and
         * links dropped. Steps whose label does not survive are kept without one.
         */
        fun forSkill(trace: List<Step>): List<Step> = trace.map { s ->
            s.copy(
                target = PathStepSanitizer.neutralizeLabel(s.target),
                text = s.text?.let { TEXT_SLOT },
                args = s.args.filterKeys { it != "url" }.mapValues { (_, v) -> PathStepSanitizer.neutralizeLabel(v).orEmpty() }
                    .filterValues { it.isNotEmpty() },
            )
        }

        /** Stands for whatever the user wants typed this time. */
        const val TEXT_SLOT = "<text>"
    }
}
