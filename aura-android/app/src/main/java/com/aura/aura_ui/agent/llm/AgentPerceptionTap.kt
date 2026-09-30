package com.aura.aura_ui.agent.llm

/**
 * Process-wide tap that lets the on-device agent's session logger capture the
 * **SoM-annotated image the model actually saw** — the two-color (blue=ui_tree /
 * red=omniparser) numbered-box PNG that `perceive_screen` returns and the vision
 * strategy re-injects into the prompt.
 *
 * The logger captures a *raw* screenshot at tool-start, but the annotated image is
 * only assembled downstream and decoded inside the vision strategy
 * ([com.aura.aura_ui.agent.strategy] `sendToolResultsWithVision`). Rather than couple
 * the strategy to the logger, the strategy reports the current screen's bytes here and
 * [com.aura.aura_ui.mcp.log.AgentRunLogger] installs a [sink] for the run.
 *
 * Same shape as [AgentLlmTap]: one on-device run at a time, single nullable sink,
 * fail-open. Bytes only — no PII beyond what the screenshot itself shows, which is
 * already captured as the raw screenshot.
 */
object AgentPerceptionTap {

    @Volatile
    var sink: ((ByteArray) -> Unit)? = null

    val isActive: Boolean get() = sink != null

    /** Report the current screen's SoM-annotated PNG bytes. No-op when no logger is listening. */
    fun report(somPng: ByteArray) {
        sink?.invoke(somPng)
    }
}
