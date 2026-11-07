package com.aura.aura_ui.presentation.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aura.aura_ui.services.OverlayEvasion
import com.aura.aura_ui.services.PausePillBehavior
import com.aura.aura_ui.services.RemoteRun
import com.aura.aura_ui.services.RemoteRun.Outcome
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.presentation.overlay.ReportMarkdown.Block

/**
 * The run report card: how a run ended (done, partial, failed, stopped, paused, or a remote client
 * went quiet), with its markdown report or, without one, the last steps.
 *
 * - Contract: [show] replaces whatever is up; [hide] is idempotent. Main thread only.
 * - Why its window sits above the run controls: while paused, Resume and Cancel must stay
 *   reachable under the card.
 * - Why not `FLAG_NOT_TOUCHABLE`: it scrolls and has a close button. `RemoteRun` clears the
 *   report on a client's next tool call and `AuraAgent.runSpike` when a run starts, so it is never
 *   on screen while anything taps.
 */
class RunReportOverlay(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateOwner: SavedStateRegistryOwner,
    private val onClose: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null

    fun show(report: RemoteRun.Report) {
        hide()
        if (!Settings.canDrawOverlays(context)) {
            Log.e(TAG, "Cannot show run report — overlay permission missing")
            return
        }
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent { RunReportCard(report, onClose) }
        }
        runCatching { windowManager.addView(compose, layoutParams()) }
            .onSuccess { view = compose }
            .onFailure { Log.e(TAG, "Run report rejected by WindowManager: ${it.message}") }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    /** Where the card is in display pixels, or null when it is not visibly on screen. */
    fun boundsOnScreen(): OverlayEvasion.Bounds? {
        val v = view?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 } ?: return null
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        return OverlayEvasion.Bounds(left = at[0], top = at[1], right = at[0] + v.width, bottom = at[1] + v.height)
    }

    /** Off the glass while AURA screenshots, like the strip and the controls. */
    fun setHiddenForCapture(hidden: Boolean) {
        view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val navId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val nav = if (navId > 0) context.resources.getDimensionPixelSize(navId) else 0
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            // Change together: PausePillOverlay.layoutParams — this clears its buttons.
            y = PausePillBehavior.bottomMarginPx(nav, dp(PausePillOverlay.MIN_BOTTOM_MARGIN_DP)) +
                dp(PausePillOverlay.BUTTON_DP) + dp(CONTROLS_GAP_DP)
        }
    }

    private companion object {
        const val TAG = "RunReportOverlay"
        const val CONTROLS_GAP_DP = 12
    }
}

// ── Card ───────────────────────────────────────────────────────────────────

private val Ui = Mono.scheme(dark = true)
private val Amber = Color(0xFFE0A43A)

private data class Status(val title: String, val badge: String, val dot: Color)

private fun statusOf(outcome: Outcome): Status = when (outcome) {
    Outcome.DONE -> Status("Task complete", "Done", Mono.ToggleGreen)
    Outcome.PARTIAL -> Status("Partly done", "Partial", Amber)
    Outcome.FAILED -> Status("Couldn't finish", "Failed", Mono.Blood)
    Outcome.STOPPED -> Status("Stopped", "Stopped", Mono.Blood)
    Outcome.PAUSED -> Status("Paused", "Paused", Amber)
    Outcome.WENT_QUIET -> Status("Run ended", "Ended", Ui.textSecondary)
}

@Composable
internal fun RunReportCard(report: RemoteRun.Report, onClose: () -> Unit) {
    val status = statusOf(report.outcome)
    val blocks = remember(report.markdown) { report.markdown?.let(ReportMarkdown::parse).orEmpty() }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    val enter = remember { MutableTransitionState(false).apply { targetState = true } }

    AnimatedVisibility(
        visibleState = enter,
        enter = fadeIn() + slideInVertically { it / 3 },
    ) {
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .shadow(18.dp, Mono.ShapeCard)
                .clip(Mono.ShapeCard)
                .background(Ui.card)
                .border(1.dp, Ui.outline, Mono.ShapeCard),
        ) {
            Header(status, onClose)
            MetaRow(report)
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                report.summary?.let { Paragraph(it, lead = true) }
                if (blocks.isNotEmpty()) {
                    blocks.forEach { BlockView(it) }
                } else if (report.recent.isNotEmpty()) {
                    Heading(if (report.outcome == Outcome.PAUSED) "So far" else "What happened", level = 3)
                    report.recent.forEach { Bullet("•", it) }
                }
                footnoteFor(report)?.let {
                    Text(it, color = Ui.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

/** Only for remote runs: each line describes a client, or the controls of a run still open. */
internal fun footnoteFor(report: RemoteRun.Report): String? = when {
    report.onDevice -> null
    report.outcome == Outcome.PAUSED -> "Resume or cancel with the buttons below."
    report.outcome == Outcome.STOPPED -> "This client is refused until it stops calling."
    report.outcome == Outcome.WENT_QUIET -> "The client stopped sending commands without finishing."
    else -> null
}

/**
 * The card as plain text, for the session log.
 * - Change together: [RunReportCard], which draws the same parts in the same order.
 */
internal fun reportText(report: RemoteRun.Report): String = listOfNotNull(
    (listOf(statusOf(report.outcome).title) + metaParts(report)).joinToString(" · "),
    report.summary,
    report.markdown ?: report.recent.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- $it" },
).joinToString("\n\n")

private fun metaParts(report: RemoteRun.Report): List<String> = listOfNotNull(
    report.client,
    formatDuration(report.durationMs),
    report.steps.takeIf { it > 0 }?.let { if (it == 1) "1 step" else "$it steps" },
)

@Composable
private fun Header(status: Status, onClose: () -> Unit) {
    Row(
        Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(Mono.ShapePill)
                .background(Ui.chip)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(status.dot))
            Spacer(Modifier.width(6.dp))
            Text(status.badge, color = Ui.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            status.title,
            color = Ui.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Ui.chip)
                .clickable(onClick = onClose)
                .semantics { contentDescription = "Close report" },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Ui.textSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun MetaRow(report: RemoteRun.Report) {
    Text(
        metaParts(report).joinToString("  ·  "),
        color = Ui.textSecondary,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
    )
}

internal fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

// ── Markdown blocks ────────────────────────────────────────────────────────

@Composable
private fun BlockView(block: Block) = when (block) {
    is Block.Heading -> Heading(block.text, block.level)
    is Block.Bullet -> Bullet(block.marker, block.text)
    is Block.Paragraph -> Paragraph(block.text)
    is Block.Table -> Table(block)
}

@Composable
private fun Heading(text: String, level: Int) {
    Text(
        inline(text),
        color = Ui.textPrimary,
        fontWeight = FontWeight.SemiBold,
        fontSize = when (level) { 1 -> 19.sp; 2 -> 16.sp; else -> 14.sp },
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Paragraph(text: String, lead: Boolean = false) {
    Text(
        inline(text),
        color = Ui.textPrimary,
        fontSize = if (lead) 15.sp else 14.sp,
        lineHeight = if (lead) 21.sp else 20.sp,
    )
}

@Composable
private fun Bullet(marker: String, text: String) {
    Row {
        Text(marker, color = Ui.textSecondary, fontSize = 14.sp, modifier = Modifier.width(22.dp))
        Text(inline(text), color = Ui.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/**
 * Up to three columns share the card's width; wider tables scroll sideways at a fixed column
 * width, because squeezing four columns into a phone card wraps every cell into a word stack.
 */
@Composable
private fun Table(table: Block.Table) {
    val cols = (table.header.takeIf { it.isNotEmpty() } ?: table.rows.firstOrNull()).orEmpty().size
    if (cols == 0) return
    val fixed = cols > 3
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Ui.outline, shape)
            .then(if (fixed) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
    ) {
        Column {
            if (table.header.isNotEmpty()) TableRow(table.header, fixed, header = true, shaded = false)
            table.rows.forEachIndexed { i, row -> TableRow(row, fixed, header = false, shaded = i % 2 == 1) }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, fixed: Boolean, header: Boolean, shaded: Boolean) {
    val bg = when {
        header -> Ui.chip
        shaded -> Ui.chip.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    Row(Modifier.background(bg).then(if (fixed) Modifier else Modifier.fillMaxWidth())) {
        cells.forEachIndexed { i, cell ->
            val width = if (fixed) {
                Modifier.width(if (i == 0) 150.dp else 120.dp)
            } else {
                Modifier.weight(if (i == 0) 1.4f else 1f)
            }
            Text(
                inline(cell),
                color = if (header) Ui.textSecondary else Ui.textPrimary,
                fontSize = if (header) 12.sp else 13.sp,
                lineHeight = 18.sp,
                fontWeight = if (header || i == 0) FontWeight.SemiBold else FontWeight.Normal,
                modifier = width.padding(horizontal = 10.dp, vertical = 9.dp),
            )
        }
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    ReportMarkdown.spans(text).forEach { span ->
        when {
            span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
            span.code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Ui.chip)) {
                append(span.text)
            }
            else -> append(span.text)
        }
    }
}
