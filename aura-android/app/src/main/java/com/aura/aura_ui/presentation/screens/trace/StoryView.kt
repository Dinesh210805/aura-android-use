package com.aura.aura_ui.presentation.screens.trace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aura.aura_ui.mcp.log.NarrationBeat
import com.aura.aura_ui.mcp.log.SessionLog
import com.aura.aura_ui.mcp.log.Utterance
import com.aura.aura_ui.presentation.overlay.BeatIconView
import com.aura.aura_ui.services.AgentStatusRegistry.Kind
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme
import java.io.File

/**
 * The run as the person who asked for it would tell it: what they asked, what AURA looked up,
 * each thing it did in the words the overlay used, the moments it needed them, and how it ended.
 *
 * Built from the session's narration — the same lines the status strip showed — so the log and
 * the screen never tell two different stories. No tokens, hooks, JSON or tool names.
 */
internal fun LazyListScope.storyItems(
    s: SessionLog,
    crops: Map<Int, ImageBitmap>,
    images: Map<Int, File>,
    scheme: MonoScheme,
    onZoom: (File) -> Unit,
) {
    item(key = "asked") { AskedCard(s, scheme) }
    s.research?.let { r ->
        item(key = "research") {
            StoryCard(scheme) {
                Label("LOOKED IT UP FIRST", scheme)
                if (r.note != null) {
                    Text("From ${r.source}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = scheme.textPrimary)
                    Text(firstSentences(r.note, 2), style = MaterialTheme.typography.bodyMedium, color = scheme.textSecondary)
                } else {
                    Text("Looked for official help for this — nothing useful came up, so AURA worked it out on screen.", style = MaterialTheme.typography.bodyMedium, color = scheme.textSecondary)
                }
            }
        }
    }

    val events = storyEvents(s)
    itemsIndexed(events, key = { i, _ -> "story-$i" }) { _, e ->
        when (e) {
            is StoryEvent.Headline -> Text(
                e.text,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 0.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.textSecondary,
            )
            is StoryEvent.Beat -> BeatRow(
                e.beat, scheme,
                crop = e.beat.invocationIndex?.let { crops[it] },
                onOpen = e.beat.invocationIndex?.let { images[it] }?.let { f -> { onZoom(f) } },
            )
            is StoryEvent.Said -> SaidRow(e.utterance, scheme)
        }
    }

    finalWords(s)?.let { words ->
        item(key = "final") {
            StoryCard(scheme) {
                Label("AURA SAID", scheme)
                Text(words, style = MaterialTheme.typography.bodyLarge, color = scheme.textPrimary)
            }
        }
    }
}

internal sealed interface StoryEvent {
    val at: Long
    data class Headline(val text: String, override val at: Long) : StoryEvent
    data class Beat(val beat: NarrationBeat) : StoryEvent { override val at get() = beat.atMillis }
    data class Said(val utterance: Utterance) : StoryEvent { override val at get() = utterance.atMillis }
}

/**
 * Beats and speech on one clock, with a headline inserted wherever the plan step changes — so the
 * story reads in chapters ("Step 2 of 4 · Open the chat") rather than as one long list.
 * A session logged before narration existed tells what it can from the tool calls.
 */
internal fun storyEvents(s: SessionLog): List<StoryEvent> {
    val beats: List<NarrationBeat> = s.narration.ifEmpty {
        s.invocations.map { inv ->
            NarrationBeat(inv.timestampMillis, if (inv.success) "work" else "recover", plainToolLine(inv.toolName, inv.success), invocationIndex = inv.index)
        }
    }
    val merged = (beats.map { StoryEvent.Beat(it) } + s.utterances.filter { it.speaker != "system" }.map { StoryEvent.Said(it) })
        .sortedBy { it.at }
    val out = mutableListOf<StoryEvent>()
    var headline: String? = null
    merged.forEach { e ->
        val h = (e as? StoryEvent.Beat)?.beat?.headline
        if (h != null && h != headline) {
            out += StoryEvent.Headline(h, e.at)
            headline = h
        }
        out += e
    }
    return out
}

private fun plainToolLine(tool: String, ok: Boolean): String {
    val words = tool.replace('_', ' ')
    return if (ok) "Did: $words" else "Tried: $words — it didn't work"
}

/** The last thing the model told the user — the agent's final turn text. */
private fun finalWords(s: SessionLog): String? =
    s.llmCalls.lastOrNull { it.purpose == null && it.response.isNotBlank() }?.response?.trim()
        ?.takeIf { !it.startsWith("{") && !it.startsWith("[") }

private fun firstSentences(text: String, n: Int): String =
    text.trim().split(Regex("(?<=[.!?])\\s+")).take(n).joinToString(" ").take(300)

@Composable
private fun AskedCard(s: SessionLog, scheme: MonoScheme) {
    StoryCard(scheme) {
        Label("YOU ASKED", scheme)
        Text(
            s.command?.takeIf { it.isNotBlank() } ?: "A spoken conversation",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.textPrimary,
        )
        val (word, held) = resultWord(s)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(Mono.ShapePill).background(if (held) Mono.Blood else scheme.textPrimary))
            Spacer(Modifier.width(8.dp))
            Text(word, style = MaterialTheme.typography.bodyMedium, color = scheme.textPrimary)
        }
    }
}

/** The outcome in words, and whether it is the kind that needs the user's attention. */
internal fun resultWord(s: SessionLog): Pair<String, Boolean> = when (s.outcome?.verdict) {
    "success" -> "Done" to false
    "partial" -> "Partly done — some of it is left" to true
    "fail" -> "Didn't get there" to true
    else -> when (s.endReason) {
        null -> "Still going" to false
        "completed" -> "Finished" to false
        "cancelled" -> "You stopped it" to false
        else -> "Stopped with a problem" to true
    }
}

@Composable
private fun BeatRow(beat: NarrationBeat, scheme: MonoScheme, crop: ImageBitmap?, onOpen: (() -> Unit)?) {
    val kind = runCatching { Kind.valueOf(beat.kind.uppercase()) }.getOrDefault(Kind.WORK)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val ink = scheme.textPrimary.toArgb()
        AndroidView(
            factory = { ctx -> BeatIconView(ctx, ink, Mono.Blood.toArgb()) },
            update = { it.kind = kind },
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                beat.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (kind == Kind.HOLD) Mono.Blood else scheme.textPrimary,
            )
            crop?.let { TargetCropView(it, "What it touched", scheme, onOpen) }
        }
    }
}

@Composable
private fun SaidRow(u: Utterance, scheme: MonoScheme) {
    val you = u.speaker == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (you) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = Mono.ShapeCardSmall,
            color = if (you) scheme.chip else Mono.Ink,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(if (you) "You" else "AURA", style = MaterialTheme.typography.labelSmall, color = if (you) scheme.textSecondary else Mono.TextOnInk.copy(alpha = 0.7f))
                Text(u.text, style = MaterialTheme.typography.bodyMedium, color = if (you) scheme.textPrimary else Mono.TextOnInk)
            }
        }
    }
}

@Composable
private fun StoryCard(scheme: MonoScheme, content: @Composable () -> Unit) {
    Surface(shape = Mono.ShapeCard, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Label(text: String, scheme: MonoScheme) {
    Text(text, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = scheme.textSecondary)
}
