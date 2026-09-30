@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aura.aura_ui.presentation.screens.trace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.mcp.log.MemoryWrite
import com.aura.aura_ui.mcp.log.RunAssembly
import com.aura.aura_ui.mcp.log.RunBudgetLog
import com.aura.aura_ui.mcp.log.RunVerdict
import com.aura.aura_ui.mcp.log.SessionLog
import com.aura.aura_ui.mcp.log.summarize
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme
import java.text.DateFormat
import java.util.Date

/** Run-level cards of the trace: the summary, what was assembled, proof, budget, memory. */

// ── Top-level cards ─────────────────────────────────────────────────────────

@Composable
internal fun SummaryStrip(s: SessionLog, scheme: MonoScheme) {
    val summary = remember(s) { summarize(s) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Surface(shape = Mono.ShapeCard, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                s.command?.takeIf { it.isNotBlank() }
                    ?: s.utterances.firstOrNull { it.kind == "speech" || it.kind == "text" }?.text
                    ?: "Agent run",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.textPrimary,
            )
            FlowRowChips {
                s.agentLabel?.let { TraceChip(it, scheme.textSecondary, scheme) }
                s.endReason?.let { TraceChip(it, endReasonColor(it), scheme) }
            }
            Text(
                df.format(Date(s.startedAtMillis)),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.textSecondary,
            )
            // FlowRow, not Row: a Row measures each non-weighted child against the width
            // left over, so the last metric ("cached 100%") was handed ~one character of
            // space and wrapped itself into a vertical string of digits.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Metric("time", fmtMs(summary.wallClockMs), scheme)
                // A conversation's size is its turns; a run's is its tools. Show whichever the
                // session actually has rather than a proud "0 tools" on a spoken exchange.
                if (s.utterances.isNotEmpty()) Metric("turns", "${s.utterances.size}", scheme)
                Metric("tools", "${summary.toolCount}", scheme)
                Metric("llm", "${summary.llmCount}", scheme)
                Metric("tokens", "${summary.totalTokens}", scheme)
                // Only shown when at least one call's provider actually reported cache
                // stats — most providers don't, and an absent field is not a 0% cache rate.
                summary.cacheHitRatio?.let { ratio ->
                    Metric("cached", "${(ratio * 100).toInt()}%", scheme, accent = OkGreen)
                }
            }
            Text(
                "prompt ${summary.promptTokens} · completion ${summary.completionTokens}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.textSecondary,
            )
        }
    }
}

@Composable
internal fun Metric(label: String, value: String, scheme: MonoScheme, accent: Color? = null) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = accent ?: scheme.textPrimary,
            maxLines = 1,
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun AssemblyCard(asm: RunAssembly, scheme: MonoScheme) {
    Surface(shape = Mono.ShapeCardSmall, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Run assembly", scheme)
            FlowRowChips {
                TraceChip("${asm.localToolCount} local tools", scheme.textSecondary, scheme)
                if (asm.remoteServers.isNotEmpty()) TraceChip("${asm.remoteServers.size} remote", scheme.textSecondary, scheme)
                if (asm.skills.isNotEmpty()) TraceChip("${asm.skills.size} skills", scheme.textSecondary, scheme)
            }
            asm.remoteServers.forEach { remote ->
                MonoLine("  • ${remote.name} (${remote.toolCount} tools)", scheme)
            }
            if (asm.skills.isNotEmpty()) {
                MonoLine("  available: ${asm.skills.joinToString(", ")}", scheme)
            }
            asm.injectedHints?.takeIf { it.isNotBlank() }?.let { hints ->
                PayloadSection("Injected hints", hints, scheme)
            }
        }
    }
}

/**
 * What the run spent against what it was allowed to spend.
 *
 * The numbers here are otherwise invisible on a real device: the model's context window is
 * persisted in `EncryptedSharedPreferences` and the meters live only in the run's memory. Blood red
 * is used for one thing only — the ceiling that actually stopped the run — because that is the
 * single "look here now" fact in the card.
 */
/**
 * Did the run do what was asked, and what is that answer based on (spec 2026-09-15)?
 *
 * Each finding shows the screen text the harness matched it against, because a claim and its
 * evidence side by side is the only form in which "it says it read ten posts" can be checked —
 * which is exactly what the user could not do before this existed.
 */
@Composable
internal fun ProofCard(v: RunVerdict, scheme: MonoScheme) {
    Surface(shape = Mono.ShapeCardSmall, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Did it actually do it?", scheme)
            FlowRowChips {
                TraceChip(v.verdict, verdictColor(v.verdict), scheme)
                v.claimed?.takeIf { it != v.verdict }?.let { TraceChip("claimed $it", WarnAmber, scheme) }
                v.target?.let { TraceChip("proof ${v.proven}/$it", scheme.textSecondary, scheme) }
            }
            v.deliverable?.takeIf { it.isNotBlank() }?.let { MonoLine("asked for: $it", scheme) }
            v.why?.takeIf { it.isNotBlank() }?.let {
                MonoLine(it, scheme, color = if (v.verdict == "success") scheme.textSecondary else Mono.Blood)
            }
            if (v.findings.isEmpty()) {
                MonoLine("no findings recorded this run", scheme, color = scheme.textSecondary)
            } else {
                v.findings.forEach { f ->
                    MonoLine("• ${f.item}", scheme)
                    MonoLine("   “${f.quote}”", scheme, color = scheme.textSecondary)
                }
            }
        }
    }
}

internal fun verdictColor(verdict: String): Color = when (verdict) {
    "success" -> OkGreen
    "partial" -> WarnAmber
    "fail" -> Mono.Blood
    else -> Color.Gray
}

@Composable
internal fun BudgetCard(b: RunBudgetLog, scheme: MonoScheme) {
    Surface(shape = Mono.ShapeCardSmall, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Budget & limits", scheme)
            FlowRowChips {
                // "no cap" is the shipped default; a number appears only if the user set one.
                TraceChip("${b.requests}/${b.maxRequests ?: "∞"} calls", scheme.textSecondary, scheme)
                // No denominator on purpose: tokens are an instrument, not a ceiling (RunBudget).
                TraceChip("${b.tokensSpent} tok", scheme.textSecondary, scheme)
                TraceChip("${b.elapsedMs / 1000}s/${b.maxWallClockMs / 1000}s", scheme.textSecondary, scheme)
            }
            // Without this line the token figure is uninterpretable — "the provider told us 12k"
            // and "we guessed 12k" are different facts.
            MonoLine(
                if (b.tokensReported) {
                    "tokens: reported by provider, ${b.tokensCached} of them cache-served and " +
                        "not charged (our estimate was ${b.tokensEstimated})"
                } else {
                    "tokens: ESTIMATED — this provider reported no usage"
                },
                scheme,
                color = if (b.tokensReported) scheme.textSecondary else WarnAmber,
            )
            MonoLine(
                "compact above ${b.compactThresholdTokens} tok" +
                    (b.contextWindow?.let { " (window $it)" } ?: " (window not published)"),
                scheme,
            )
            MonoLine(
                "retry: up to ${b.retryMaxAttempts} attempts, ≤${b.retryWorstCaseSleepMs / 1000}s asleep",
                scheme,
            )
            b.endedByLimit?.let { limit ->
                MonoLine("⚠ run stopped by its $limit ceiling", scheme, color = Mono.Blood)
            }
        }
    }
}

@Composable
internal fun MemoryWriteCard(mw: MemoryWrite, scheme: MonoScheme) {
    Surface(shape = Mono.ShapeCardSmall, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Memory write", scheme)
            MonoLine("goal: ${mw.goalKey}", scheme)
            Text("path", style = MaterialTheme.typography.labelSmall, color = scheme.textSecondary)
            mw.path.forEach { step -> MonoLine("  → $step", scheme) }
        }
    }
}

internal fun endReasonColor(reason: String): Color = when {
    reason.equals("completed", ignoreCase = true) -> OkGreen
    reason.contains("error", ignoreCase = true) || reason.contains("fail", ignoreCase = true) -> FailRed
    else -> WarnAmber
}

internal fun ellipsize(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"

internal fun fmtMs(ms: Long): String {
    val sec = ms / 1000.0
    return if (sec < 60) "%.1fs".format(sec) else "${(sec / 60).toInt()}m ${(sec % 60).toInt()}s"
}

