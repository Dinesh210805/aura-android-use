package com.aura.aura_ui.agent.proof

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import com.aura.aura_ui.agent.ledger.Finding
import com.aura.aura_ui.agent.ledger.RunLedgerCaps
import com.aura.aura_ui.agent.ledger.RunLedgerController
import com.aura.aura_ui.agent.mcpbridge.client.CallToolResultKoogTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `record_finding` — the agent's own claim, saved only when the screen backs it.
 *
 * Client-side bookkeeping like the ledger plan tools: no device access, no capability, and it
 * never reaches the MCP server. The whole point is the check in [ProofToolLogic.recordFinding] —
 * a quote the run never read is refused, so what accumulates in the ledger is evidence rather
 * than recollection.
 */
class ProofToolLogic(
    private val controller: RunLedgerController,
    private val observed: ObservedText,
) {

    suspend fun recordFinding(args: kotlinx.serialization.json.JsonObject): CallToolResult {
        val item = args["item"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val quote = args["quote"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (item.isEmpty()) return err("'item' is required — one line saying what you found.")
        if (quote.length < ObservedText.MIN_QUOTE_CHARS) {
            return err(
                "'quote' must be at least ${ObservedText.MIN_QUOTE_CHARS} characters of text " +
                    "copied exactly from the screen or page this finding came from.",
            )
        }
        if (!observed.contains(quote)) {
            // Names the one recovery, because the model's instinct here is to reword the quote
            // until something sticks — which is the fabrication this gate exists to stop.
            return err(
                "That quote is not in anything you have read this run, so it cannot count as " +
                    "proof. Read the screen (or the page) that shows it, then copy the text " +
                    "across EXACTLY as it appears — do not retype it from memory or summarize it.",
            )
        }
        controller.addFinding(Finding(item = item, quote = quote, afterReads = observed.reads))
        val ledger = controller.snapshot()
        val target = ledger.targetCount
        val progress = if (target != null) {
            "${ledger.findings.size} of $target proven" +
                if (ledger.findings.size < target) " — keep going, do not finish yet." else " — you can finish."
        } else {
            "${ledger.findings.size} recorded."
        }
        return ok("Finding saved: $progress")
    }

    private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = false)

    private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)
}

@OptIn(InternalAgentsApi::class)
internal class RecordFindingKoogTool(private val logic: ProofToolLogic) : CallToolResultKoogTool(
    ToolDescriptor(
        name = "record_finding",
        description = "Save ONE thing you found, with the screen text proving it. Call it as you " +
            "read each item, not in a batch at the end. You write your final answer from these, " +
            "and success is refused while you have fewer than the task asked for.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "item",
                "The finding in one line, your own words (max ${RunLedgerCaps.MAX_FINDING_CHARS} chars).",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                "quote",
                "${ObservedText.MIN_QUOTE_CHARS}+ characters copied EXACTLY off the screen or page " +
                    "it came from — a headline or one sentence. Reworded quotes are rejected.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {
    override suspend fun execute(args: JSONObject): CallToolResult =
        logic.recordFinding(args.toKotlinxJsonObject())
}

/** Adds `record_finding` to a run's registry, beside the ledger's plan tools. */
@OptIn(InternalAgentsApi::class)
internal fun attachProofTools(
    controller: RunLedgerController,
    observed: ObservedText,
    baseRegistry: ToolRegistry,
): ToolRegistry = baseRegistry + ToolRegistry {
    tool(RecordFindingKoogTool(ProofToolLogic(controller, observed)))
}
