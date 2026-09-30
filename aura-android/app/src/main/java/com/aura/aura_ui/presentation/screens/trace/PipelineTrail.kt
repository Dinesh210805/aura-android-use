package com.aura.aura_ui.presentation.screens.trace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.mcp.log.PipelineStep
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme

/**
 * A tool call's journey, in order: the hooks that judged it, the server's gates, the dispatch,
 * what the server did after, and the hooks that ran on the result.
 *
 * Folded, it is one line of chips — enough to see at a glance that everything passed, or which
 * one said no. Open, every step is a line with its verdict, its time and its reason.
 * Colour is spent only where something refused, threw or needed the user.
 */
@Composable
internal fun PipelineTrailSection(trail: List<PipelineStep>, scheme: MonoScheme) {
    if (trail.isEmpty()) return
    var open by remember(trail) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            (if (open) "▾ " else "▸ ") + "journey · ${trail.size} steps" + summary(trail),
            modifier = Modifier
                .fillMaxWidth()
                .clip(Mono.ShapeChip)
                .clickable { open = !open }
                .padding(vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (trail.any { verdictColor(it.verdict, scheme) == FailRed }) FailRed else scheme.textSecondary,
        )
        if (!open) {
            FlowRowChips {
                trail.filter { it.stage != "target" }.forEach { step ->
                    TraceChip("${mark(step.verdict)} ${step.name}", verdictColor(step.verdict, scheme), scheme, outlined = true)
                }
            }
        } else {
            trail.forEach { step ->
                MonoLine(
                    "${STAGE_LABEL[step.stage] ?: step.stage}  ${mark(step.verdict)} ${step.name} · ${step.verdict}" +
                        (step.ms?.let { " · ${it}ms" } ?: "") +
                        (step.detail?.takeIf { it.isNotBlank() }?.let { "\n      $it" } ?: ""),
                    scheme,
                    color = verdictColor(step.verdict, scheme).takeIf { it != scheme.textSecondary } ?: scheme.textPrimary,
                )
            }
        }
    }
}

private fun summary(trail: List<PipelineStep>): String {
    val stopped = trail.firstOrNull { it.verdict in STOPPERS }
    return stopped?.let { " · stopped by ${it.name}" } ?: ""
}

private val STOPPERS = setOf("deny", "refuse", "no")

private fun mark(verdict: String): String = when (verdict) {
    "proceed", "pass", "ran", "yes" -> "✓"
    "deny", "refuse", "no" -> "✕"
    "threw", "error" -> "!"
    "confirm" -> "?"
    "rewrite" -> "✎"
    else -> "·"
}

private fun verdictColor(verdict: String, scheme: MonoScheme): Color = when (verdict) {
    "deny", "refuse", "threw", "error" -> FailRed
    "confirm", "rewrite", "no" -> WarnAmber
    else -> scheme.textSecondary
}

private val STAGE_LABEL = mapOf(
    "pre-hook" to "hook  ",
    "confirm" to "user  ",
    "gate" to "gate  ",
    "target" to "target",
    "dispatch" to "run   ",
    "server-post" to "after ",
    "post-hook" to "hook  ",
    "failure-hook" to "fail  ",
)
