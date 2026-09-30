@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aura.aura_ui.presentation.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.aura.aura_ui.presentation.screens.trace.TraceVisibility
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.aura.aura_ui.mcp.log.AgentTurn
import com.aura.aura_ui.mcp.log.LiveConversationLogger
import com.aura.aura_ui.mcp.log.McpSessionStore
import com.aura.aura_ui.mcp.log.MemoryWrite
import com.aura.aura_ui.mcp.log.RunAssembly
import com.aura.aura_ui.mcp.log.RunBudgetLog
import com.aura.aura_ui.mcp.log.RunVerdict
import com.aura.aura_ui.mcp.log.SessionJsonExporter
import com.aura.aura_ui.mcp.log.SessionLog
import com.aura.aura_ui.mcp.log.SessionScreenshotAnnotator
import com.aura.aura_ui.mcp.log.ToolInvocation
import com.aura.aura_ui.mcp.log.TraceEntry
import com.aura.aura_ui.mcp.log.Utterance
import com.aura.aura_ui.mcp.log.buildTraceEntries
import com.aura.aura_ui.mcp.log.summarize
import com.aura.aura_ui.presentation.components.MonoTopBar
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.presentation.screens.trace.FlowRowChips
import com.aura.aura_ui.presentation.screens.trace.GridPayloadSection
import com.aura.aura_ui.presentation.screens.trace.INLINE_LIMIT
import com.aura.aura_ui.presentation.screens.trace.ImageZoomDialog
import com.aura.aura_ui.presentation.screens.trace.LlmIndigo
import com.aura.aura_ui.presentation.screens.trace.ScreenCanvasSection
import com.aura.aura_ui.presentation.screens.trace.MonoLine
import com.aura.aura_ui.presentation.screens.trace.OkGreen
import com.aura.aura_ui.presentation.screens.trace.FailRed
import com.aura.aura_ui.presentation.screens.trace.PayloadSection
import com.aura.aura_ui.presentation.screens.trace.SectionTitle
import com.aura.aura_ui.presentation.screens.trace.StatusGlyph
import com.aura.aura_ui.presentation.screens.trace.TraceChip
import com.aura.aura_ui.presentation.screens.trace.TraceImage
import com.aura.aura_ui.presentation.screens.trace.WarnAmber
import com.aura.aura_ui.presentation.screens.trace.AssemblyCard
import com.aura.aura_ui.presentation.screens.trace.BudgetCard
import com.aura.aura_ui.presentation.screens.trace.MemoryWriteCard
import com.aura.aura_ui.presentation.screens.trace.PipelineTrailSection
import com.aura.aura_ui.presentation.screens.trace.ProofCard
import com.aura.aura_ui.presentation.screens.trace.RawBodyDialog
import com.aura.aura_ui.presentation.screens.trace.SummaryStrip
import com.aura.aura_ui.presentation.screens.trace.TargetCrop
import com.aura.aura_ui.presentation.screens.trace.TargetCropView
import com.aura.aura_ui.presentation.screens.trace.ellipsize
import com.aura.aura_ui.presentation.screens.trace.fmtMs
import com.aura.aura_ui.presentation.screens.trace.storyItems
import com.aura.aura_ui.mcp.log.NarrationBeat
import com.aura.aura_ui.mcp.log.ResearchLog
import androidx.compose.ui.graphics.ImageBitmap
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Native (Trace-v2) detail screen for an **on-device agent** run — the developer
 * trace. Renders the session as a numbered turn timeline on the app's Mono theme:
 * a summary strip, run assembly, the system prompt (collapsed), then per-turn
 * thinking → decision → input delta → tool results → the SoM image the model saw.
 *
 * Density rule (developer-first, nothing cut): the scannable spine is always
 * visible — turn number, LLM meta, the DECISION, each tool's status/name/timing,
 * hook/skill/origin tags, and screenshots. Heavy blocks (thinking, per-turn input,
 * system prompt, hints, long args/output) start COLLAPSED with a char-count label
 * and expand on tap, so the full record is one tap away without walls of text.
 *
 * Three layout rules, all of them reactions to the previous version:
 *  - **Nothing scrolls sideways.** Payloads wrap; chips flow onto a second line. A
 *    metrics `Row` was what squeezed the last `cached` value into one character per
 *    line — non-weighted `Row` children get measured against whatever width is left,
 *    and by the sixth there is none.
 *  - **The timeline is a header, not a rail.** A 30dp gutter down the left cost ~15%
 *    of the width of every card on a phone to draw a line. The number now rides in
 *    each card's own header band, and the cards themselves are the spine.
 *  - **Payloads are shown formatted, copied raw.** See `humanizePayload`.
 *
 * Colour is spent in four places only: status glyphs (✓/✗), the indigo LLM accent,
 * muted JSON syntax tokens, and blood red strictly for failure.
 */
@Composable
fun AgentTraceScreen(
    sessionId: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    // One read for the whole screen. False in every release build by construction, so the blocks
    // below cannot reach a user however this composable is reached.
    // Bumped by the debug toggle below so the whole list recomposes against the new answer.
    var internalsToggle by remember { mutableIntStateOf(0) }
    val showInternals = remember(internalsToggle) { TraceVisibility.debugMode(context) }
    val canToggle = remember { TraceVisibility.isToggleAvailable(context) }
    val scheme = rememberMonoScheme()
    val store = remember { McpSessionStore(context) }
    val scope = rememberCoroutineScope()
    var session by remember(sessionId) { mutableStateOf<SessionLog?>(null) }
    // Speech and model turns on one clock — a voice-started task is one story, not two logs.
    var entries by remember(sessionId) { mutableStateOf<List<TraceEntry>>(emptyList()) }
    // Pre-resolved best image per tool-invocation index (som > annotated > raw).
    var images by remember(sessionId) { mutableStateOf<Map<Int, File>>(emptyMap()) }
    var zoom by remember { mutableStateOf<File?>(null) }
    // The element each som_id gesture touched, cut out of the screen it was chosen from.
    var crops by remember(sessionId) { mutableStateOf<Map<Int, ImageBitmap>>(emptyMap()) }
    var cropSources by remember(sessionId) { mutableStateOf<Map<Int, File>>(emptyMap()) }
    var sessionDir by remember(sessionId) { mutableStateOf<File?>(null) }
    // A raw body open full-screen: the file and its title.
    var raw by remember { mutableStateOf<Pair<File, String>?>(null) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        withContext(Dispatchers.IO) {
            val s = store.readSession(sessionId) ?: return@withContext
            val dir = store.sessionDir(sessionId)
            val resolved = buildMap {
                s.invocations.forEach { inv ->
                    resolveImage(store, dir, sessionId, inv)?.let { put(inv.index, it) }
                }
            }
            val sources = mutableMapOf<Int, File>()
            val cut = buildMap {
                s.invocations.filter { it.targetBounds != null }.forEach { inv ->
                    val source = TargetCrop.sourceFor(
                        s.invocations, inv.index,
                        somFile = { store.somImageFile(sessionId, it) },
                        shotFile = { store.screenshotFile(sessionId, it) },
                    )
                    TargetCrop.render(s, inv, source)?.let {
                        put(inv.index, it)
                        source?.let { f -> sources[inv.index] = f }
                    }
                }
            }
            cropSources = sources
            session = s
            entries = buildTraceEntries(s)
            images = resolved
            crops = cut
            sessionDir = dir
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.canvas),
    ) {
        // Back is owned by the global capsule nav bar — no redundant top-bar back.
        MonoTopBar(
            title = if (session?.source == LiveConversationLogger.SOURCE_LIVE) "Conversation" else "Agent trace",
            subtitle = session?.command?.takeIf { it.isNotBlank() }?.let { ellipsize(it, 48) }
                ?: if (session?.utterances?.isNotEmpty() == true) "Spoken session" else "On-device run",
            scheme = scheme,
            actions = {
                if (session != null) {
                    ExportAction(
                        exporting = exporting,
                        scheme = scheme,
                        onClick = {
                            exporting = true
                            scope.launch {
                                // Debug exports the whole folder, raw bodies included; the story
                                // view exports the redacted JSON a user can safely send.
                                val file = withContext(Dispatchers.IO) {
                                    if (showInternals) SessionJsonExporter(context).exportFolder(sessionId)
                                    else SessionJsonExporter(context).export(sessionId)
                                }
                                exporting = false
                                if (file == null) {
                                    Toast.makeText(context, "Export failed", Toast.LENGTH_LONG).show()
                                } else {
                                    // The size is worth saying out loud: base64 inflates every
                                    // embedded PNG by ~a third, so a long run is a big file and
                                    // the share target may refuse it.
                                    Toast.makeText(
                                        context,
                                        "${file.name} · ${file.length() / 1024}KB",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    shareJson(context, file, if (file.extension == "zip") "application/zip" else "application/json")
                                }
                            }
                        },
                    )
                }
            },
        )

        val s = session
        if (s == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Mono.Ink) }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Debug builds only — TraceVisibility.isToggleAvailable is false in release, so a
            // release build has no switch at all rather than a switch that defaults to off.
            if (canToggle) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(Mono.ShapeChip)
                            .background(scheme.chip)
                            .clickable {
                                TraceVisibility.setDebugMode(context, !showInternals)
                                internalsToggle++
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (showInternals) {
                                "Debug — every prompt, reply, hook, gate and byte"
                            } else {
                                "Story — what AURA did, in plain words"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.textSecondary,
                        )
                        Text(
                            if (showInternals) "DEBUG" else "STORY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (showInternals) Mono.Blood else scheme.textPrimary,
                        )
                    }
                }
            }
            if (!showInternals) {
                storyItems(s, crops, cropSources, scheme, onZoom = { zoom = it })
                s.outcome?.let { verdict -> item { ProofCard(verdict, scheme) } }
                return@LazyColumn
            }
            item { SummaryStrip(s, scheme) }
            // The proof card is NOT behind showInternals: "did it actually do what I asked, and
            // what is that based on" is the user's question about the run, not a developer's
            // question about the harness.
            s.outcome?.let { verdict -> item { ProofCard(verdict, scheme) } }
            // Everything below that is about how AURA is BUILT rather than what it DID is asked
            // for by facet — see TraceVisibility for why the rule lives in one place.
            if (showInternals) {
                s.assembly?.let { asm -> item { AssemblyCard(asm, scheme) } }
                s.budget?.let { b -> item { BudgetCard(b, scheme) } }
                s.systemPrompt?.takeIf { it.isNotBlank() }?.let { sp ->
                    item {
                        Surface(shape = Mono.ShapeCardSmall, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                                PayloadSection("System prompt", sp, scheme)
                                RawButtons(
                                    listOfNotNull(s.systemPromptFile?.let { "Full system prompt" to it }),
                                    sessionDir, scheme,
                                ) { f, t -> raw = f to t }
                            }
                        }
                    }
                }
                s.research?.let { r ->
                    item {
                        ResearchDebugCard(r, s.llmCalls.filter { it.purpose != null }, sessionDir, scheme) { f, t -> raw = f to t }
                    }
                }
            }
            val beats = s.narration.filter { it.invocationIndex != null }.groupBy { it.invocationIndex!! }
            itemsIndexed(entries, key = { i, e -> "$i-${e.atMillis}" }) { _, entry ->
                when (entry) {
                    is TraceEntry.Spoken -> SpokenBlock(entry.utterance, scheme)
                    is TraceEntry.Turn -> TurnBlock(
                        turn = entry.turn,
                        images = images,
                        scheme = scheme,
                        showInternals = showInternals,
                        onZoom = { zoom = it },
                        crops = crops,
                        beats = beats,
                        dir = sessionDir,
                        onRaw = { f, t -> raw = f to t },
                    )
                }
            }
            s.memoryWrite?.let { mw -> item { MemoryWriteCard(mw, scheme) } }
        }
    }

    zoom?.let { file -> ImageZoomDialog(file) { zoom = null } }
    raw?.let { (file, title) -> RawBodyDialog(file, title) { raw = null } }
}

/** Buttons that open a raw file full-screen. Missing files are simply not offered. */
@Composable
private fun RawButtons(
    files: List<Pair<String, String>>,
    dir: File?,
    scheme: MonoScheme,
    onRaw: (File, String) -> Unit,
) {
    val present = files.mapNotNull { (title, rel) -> dir?.let { File(it, rel) }?.takeIf { it.exists() }?.let { title to it } }
    if (present.isEmpty()) return
    FlowRowChips {
        present.forEach { (title, file) ->
            Text(
                "$title · ${file.length() / 1024} KB ›",
                modifier = Modifier
                    .clip(Mono.ShapePill)
                    .background(scheme.chip)
                    .clickable { onRaw(file, title) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.textPrimary,
            )
        }
    }
}

/** The pre-task research, every step: what it matched, the query it sent, each route, the result. */
@Composable
private fun ResearchDebugCard(
    r: ResearchLog,
    sideCalls: List<com.aura.aura_ui.mcp.log.LlmCall>,
    dir: File?,
    scheme: MonoScheme,
    onRaw: (File, String) -> Unit,
) {
    Surface(shape = Mono.ShapeCardSmall, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Pre-task research", scheme)
            FlowRowChips {
                TraceChip(r.outcome, if (r.note != null) OkGreen else WarnAmber, scheme)
                TraceChip(fmtMs(r.durationMs), scheme.textSecondary, scheme, outlined = true)
                r.app?.let { TraceChip("app: $it", scheme.textSecondary, scheme, outlined = true) }
            }
            r.phrase?.let { MonoLine("phrase: $it", scheme) }
            r.query?.let { MonoLine("query: $it", scheme, color = scheme.textPrimary) }
            r.steps.forEach { MonoLine("  → $it", scheme) }
            PipelineTrailSection(r.serverTrail, scheme)
            sideCalls.forEach { c ->
                RawButtons(
                    listOfNotNull(
                        c.rawRequestFile?.let { "Phrase call request" to it },
                        c.rawResponseFile?.let { "Phrase call response" to it },
                    ),
                    dir, scheme, onRaw,
                )
            }
            r.note?.let { PayloadSection("Note given to the agent (from ${r.source})", it, scheme) }
        }
    }
}

@Composable
private fun ExportAction(exporting: Boolean, scheme: MonoScheme, onClick: () -> Unit) {
    if (exporting) {
        CircularProgressIndicator(
            color = scheme.textSecondary,
            strokeWidth = 2.dp,
            modifier = Modifier.padding(12.dp).size(20.dp),
        )
    } else {
        Icon(
            Icons.Filled.IosShare,
            contentDescription = "Export trace as JSON",
            tint = scheme.textPrimary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onClick() }
                .padding(10.dp)
                .size(22.dp),
        )
    }
}

/** Hand the export to a share sheet. Cache-dir path is already declared in `file_paths.xml`. */
private fun shareJson(context: android.content.Context, file: File, mime: String = "application/json") {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share trace JSON"))
}

// ── Turn timeline ───────────────────────────────────────────────────────────

/**
 * One turn: a full-width card whose own header band carries the step number.
 *
 * The number used to live in a 30dp gutter to the left of the card, with a connector line
 * drawn between nodes. On a phone that gutter cost every payload ~15% of its width to draw
 * a vertical line, and the whole point of this pass is width. The sequence is still explicit
 * (`03` in the header band), it just no longer charges rent.
 */
@Composable
private fun TurnBlock(
    turn: AgentTurn,
    images: Map<Int, File>,
    scheme: MonoScheme,
    onZoom: (File) -> Unit,
    showInternals: Boolean,
    crops: Map<Int, ImageBitmap> = emptyMap(),
    beats: Map<Int, List<NarrationBeat>> = emptyMap(),
    dir: File? = null,
    onRaw: (File, String) -> Unit = { _, _ -> },
) {
    Surface(shape = Mono.ShapeCardSmall, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Column {
            // Header band — the timeline itself: step number, then what the turn cost.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.chip)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StepNumber(turn.number)
                Text(
                    "TURN",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.textSecondary,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.weight(1f))
                turn.llm?.let { c ->
                    Text(
                        c.model,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.textSecondary,
                        maxLines = 1,
                    )
                }
                Text(
                    fmtMs(turn.totalMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.textPrimary,
                )
            }

            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Turn meta: latency, tokens, finish reason.
                turn.llm?.let { c ->
                    FlowRowChips {
                        TraceChip("LLM ${fmtMs(c.durationMs)}", LlmIndigo, scheme)
                        val tok = c.totalTokens ?: ((c.promptTokens ?: 0) + (c.completionTokens ?: 0))
                        if (tok > 0) TraceChip("$tok tok", scheme.textSecondary, scheme)
                        c.finishReason?.let { TraceChip(it, scheme.textSecondary, scheme) }
                        // Provider-reported cache/reasoning breakdown — absent on providers
                        // that don't report it, so these are opt-in, not always present.
                        c.cachedTokens?.takeIf { it > 0 }?.let { TraceChip("cached $it", scheme.textSecondary, scheme, outlined = true) }
                        c.reasoningTokens?.takeIf { it > 0 }?.let { TraceChip("think $it tok", scheme.textSecondary, scheme, outlined = true) }
                    }
                }

                // The exact bytes sent and received — everything else on this card is a reading of these.
                turn.llm?.let { c ->
                    RawButtons(
                        listOfNotNull(
                            c.rawRequestFile?.let { "Request sent" to it },
                            c.rawResponseFile?.let { "Response received" to it },
                        ),
                        dir, scheme, onRaw,
                    )
                }

                // A2 diagnostic: where this request stopped matching the previous one.
                turn.llm?.prefixProbe?.let { PayloadSection("🧩 Cache prefix", it, scheme, accent = LlmIndigo) }

                // Thinking — collapsed by default (present only for thinking models).
                turn.llm?.reasoning?.takeIf { it.isNotBlank() }?.let { r ->
                    PayloadSection("🧠 Thinking", r, scheme, accent = LlmIndigo)
                }

                // The model's decision this turn — always visible (the spine).
                // Starts OPEN (it is the spine of the turn) but gains a collapse handle once
                // it is long — a thinking model's decision runs to thousands of characters,
                // and now that payloads wrap instead of scrolling sideways, "always open"
                // means several screens of scrolling between one turn and the next.
                turn.llm?.response?.takeIf { it.isNotBlank() }?.let { resp ->
                    PayloadSection(
                        "decision", resp, scheme,
                        accent = LlmIndigo,
                        collapsible = resp.length > DECISION_COLLAPSE_OVER,
                        // Past a few thousand characters it is no longer a spine — a thinking
                        // model that inlines its <thought> block into the response makes one
                        // turn four screens tall. Fold it and let the reader ask for it.
                        startCollapsed = resp.length > DECISION_FOLD_OVER,
                    )
                }

                // What the model saw this turn — collapsed by default (usually large).
                if (showInternals) {
                    turn.llm?.prompt?.takeIf { it.isNotBlank() }?.let { delta ->
                        PayloadSection("input this turn", delta, scheme)
                    }
                }

                // Tools run this turn.
                turn.tools.forEach { inv ->
                    ToolRow(inv, images[inv.index], scheme, onZoom, showInternals, crops[inv.index], beats[inv.index].orEmpty(), dir, onRaw)
                }
            }
        }
    }
}

/** The timeline marker: a zero-padded ink pill, so 3 and 13 line up. */
@Composable
private fun StepNumber(number: Int) {
    Surface(shape = Mono.ShapePill, color = Mono.Ink) {
        Text(
            number.toString().padStart(2, '0'),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            fontFamily = FontFamily.Monospace,
            color = Mono.TextOnInk,
        )
    }
}

/**
 * One spoken turn, or one recorded absence.
 *
 * Mono rules apply as everywhere else: black is content (AURA), the hairline chip is the user,
 * and blood is spent ONLY on the entries that mean something went wrong — a lost transcription
 * or a reply the user had to cut off. Those are what someone opens a conversation trace to find,
 * so they are the one thing here allowed to shout.
 *
 * Shares the turn card's full-width geometry: the old left rail is gone from both, or spoken
 * entries would sit indented against nothing.
 */
@Composable
private fun SpokenBlock(u: Utterance, scheme: MonoScheme) {
    val isGap = u.kind == "gap"
    val isCut = u.kind == "interrupted"
    val isAura = u.speaker == "aura"
    val who = when (u.speaker) {
        "user" -> "YOU"
        "aura" -> "AURA"
        else -> "SYSTEM"
    }
    val accent = when {
        isGap || isCut -> Mono.Blood
        isAura -> Mono.Ink
        else -> scheme.textSecondary
    }

    Surface(shape = Mono.ShapeCardSmall, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // A 3dp edge in place of the old node-and-rail: same "who is speaking" signal,
            // none of the gutter.
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRowChips {
                    TraceChip(who, accent, scheme, outlined = !isAura && !isGap && !isCut)
                    if (u.kind != "speech") TraceChip(u.kind, scheme.textSecondary, scheme, outlined = true)
                    if (!u.complete && !isGap) TraceChip("partial", scheme.textSecondary, scheme, outlined = true)
                }
                Text(
                    u.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isGap) Mono.Blood else scheme.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun ToolRow(
    inv: ToolInvocation,
    image: File?,
    scheme: MonoScheme,
    onZoom: (File) -> Unit,
    showInternals: Boolean,
    crop: ImageBitmap? = null,
    beats: List<NarrationBeat> = emptyList(),
    dir: File? = null,
    onRaw: (File, String) -> Unit = { _, _ -> },
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Mono.ShapeChip)
            .background(scheme.chip)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusGlyph(inv.success)
            Text(
                inv.toolName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                color = if (inv.success) scheme.textPrimary else FailRed,
            )
            Text(
                fmtMs(inv.durationMs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.textSecondary,
            )
        }
        // Hook decision, tool origin, skill load — small inline tags.
        val tags = buildList {
            inv.hookDecision?.let { hook ->
                val (icon, color) = when (hook.kind) {
                    "deny" -> "⛔" to FailRed
                    "confirm" -> "✋" to WarnAmber
                    "rewrite" -> "✏️" to WarnAmber
                    else -> "•" to scheme.textSecondary
                }
                add(Triple("$icon ${hook.hook}: ${hook.reason}", color, false))
            }
            inv.origin?.takeIf { it.isNotBlank() }?.let { add(Triple(it, scheme.textSecondary, true)) }
            inv.skillLoaded?.takeIf { it.isNotBlank() }?.let { add(Triple("📚 $it", scheme.textSecondary, true)) }
        }
        if (tags.isNotEmpty()) {
            FlowRowChips { tags.forEach { (t, c, outlined) -> TraceChip(t, c, scheme, outlined = outlined) } }
        }
        // Every hook, gate and post step the call went through — the thing a developer opens
        // this row to see when a call was refused or behaved oddly.
        if (showInternals) PipelineTrailSection(inv.trail, scheme)
        // What the user's overlay said about this call.
        beats.forEach { b -> MonoLine("overlay: ${b.text}", scheme) }
        crop?.let { cut ->
            TargetCropView(
                cut,
                "som_id ${inv.targetSomId ?: "?"} · ${inv.targetBounds}",
                scheme,
                image?.let { f -> { onZoom(f) } },
            )
        }
        if (showInternals) {
            RawButtons(
                listOfNotNull(
                    inv.rawArgsFile?.let { "Full input" to it },
                    inv.rawResultFile?.let { "Full result" to it },
                ),
                dir, scheme, onRaw,
            )
        }
        // Args + output: open if short, collapsed if long. Never truncated away, always copyable.
        if (showInternals) {
            inv.argsJson?.takeIf { it.isNotBlank() && it != "{}" }?.let {
                PayloadSection(
                    "input", it, scheme,
                    collapsible = it.length > INLINE_LIMIT,
                    truncatedFrom = inv.argsFullLength,
                )
            }
        }
        // read_screen's result already IS the picture — show that verbatim string rather
        // than the JSON-shaped "output" block, and never a redraw of it (see GridPayloadSection).
        val gridPayload = inv.gridPayload
        if (gridPayload != null) {
            // The character grid is how AURA sees a screen. It is the single largest block on
            // the page and it is the part of the machine worth keeping to ourselves, so a normal
            // trace gets the screenshot above it instead — which is the better evidence anyway.
            if (showInternals) GridPayloadSection(gridPayload, scheme)
        } else if (showInternals) {
            inv.outputSummary.takeIf { it.isNotBlank() }?.let {
                PayloadSection(
                    "output", it, scheme,
                    collapsible = it.length > INLINE_LIMIT,
                    truncatedFrom = inv.outputFullLength,
                )
            }
        }
        // perceive_screen has no verbatim picture of its own (its result is positional
        // JSON), so it still gets a redrawn one.
        if (showInternals) {
            inv.screenCanvas?.takeIf { it.isNotBlank() }?.let { ScreenCanvasSection(it, scheme) }
        }
        // With a crop, the marked-up screenshot (a cross at a coordinate) says less and is gone;
        // what is left worth showing is the screen AFTER the action, when one was captured.
        val picture = if (crop != null) image?.takeIf { inv.somImage } else image
        if (crop != null && picture != null) MonoLine("after the action:", scheme)
        picture?.let { file ->
            if (inv.somImage) {
                Text(
                    "🔵 ui_tree (accessibility)   🔴 omniparser (vision)",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.textSecondary,
                )
            }
            TraceImage(file, onClick = { onZoom(file) })
        }
    }
}

// ── helpers ─────────────────────────────────────────────────────────────────

/** Above this many characters the always-open decision block earns a collapse handle. */
private const val DECISION_COLLAPSE_OVER = 600

/** Above this, it starts folded rather than burying the next turn. */
private const val DECISION_FOLD_OVER = 2_500

/** som (perception) > annotated gesture > raw screenshot. Annotates gestures on demand. */
private fun resolveImage(store: McpSessionStore, dir: File?, sessionId: String, inv: ToolInvocation): File? {
    store.somImageFile(sessionId, inv.index)?.let { return it }
    if (inv.gestureType != null && inv.hasScreenshot && dir != null) {
        SessionScreenshotAnnotator.annotate(dir, inv)
        store.annotatedFile(sessionId, inv.index)?.let { return it }
    }
    return store.screenshotFile(sessionId, inv.index)
}
