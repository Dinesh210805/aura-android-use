package com.aura.aura_ui.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.agent.hitl.AskUserBroker
import com.aura.aura_ui.agent.hitl.PendingQuestion

/**
 * HITL question card — renders the agent's `ask_user` question just above the input pill: the
 * question text, each option as a numbered tap-chip, and an "Other…" chip that hands off to the
 * input pill for a free-text answer.
 *
 * ## Palette
 *
 * Every colour comes from the caller's [OverlayColorScheme] — the same Capsule charcoal scheme the
 * rest of the overlay renders from. It previously used the flat `Mono.Ink` / `Mono.TextOnInk`
 * tokens directly, which is a *different* palette from the surrounding window: the card read as
 * pure black against the overlay's warm charcoal, visibly not part of the same UI. Taking the
 * scheme as a parameter means it cannot drift again — there is no second source of colour to
 * forget to update.
 *
 * ## Why "Other…" hands off to the pill instead of hosting its own field
 *
 * The overlay window is `FLAG_NOT_FOCUSABLE` by default, so the soft keyboard can only attach after
 * the service flips the window focusable — machinery that already backs the input pill. A second
 * in-card text field cannot reach that machinery (it stays dead and wedges the IME), and two
 * focusable fields in one window fight over focus. So "Other…" calls [onOtherTapped] (== the pill's
 * activate hook); the user types in the pill and submits, and the overlay routes that line to
 * [AskUserBroker.answer] because a question is pending.
 *
 * For that handoff to work the pill must actually be showing its text field — see the
 * `questionPending` parameter on `InputBar`, which forces text mode while a question waits. Without
 * it the pill renders a waveform during a task and "Other…" hands focus to a field that was never
 * composed.
 *
 * [pending] is passed in rather than collected here so the card and the pill react to the same
 * emission of [AskUserBroker.pending] — they must never disagree about whether a question is up.
 */
@Composable
internal fun AskUserCard(
    pending: PendingQuestion?,
    onOtherTapped: () -> Unit,
    colors: OverlayColorScheme,
    modifier: Modifier = Modifier,
    broker: AskUserBroker = AskUserBroker.shared,
) {
    AnimatedVisibility(
        visible = pending != null,
        enter = fadeIn(tween(ASK_ANIM_IN_MS)) + slideInVertically { it / 3 },
        exit = fadeOut(tween(ASK_ANIM_OUT_MS)) + slideOutVertically { it / 3 },
        modifier = modifier,
    ) {
        pending?.let { q -> AskUserCardBody(q, broker, colors, onOtherTapped) }
    }
}

@Composable
private fun AskUserCardBody(
    q: PendingQuestion,
    broker: AskUserBroker,
    colors: OverlayColorScheme,
    onOtherTapped: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(ASK_CARD_RADIUS),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.surfaceVariant),
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // heightIn cap + scroll keeps a long question or the max option set from
        // overflowing the bottom-anchored (clipped) overlay column.
        Column(
            modifier = Modifier
                .padding(18.dp)
                .heightIn(max = askCardMaxHeight())
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "AURA needs a decision",
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = q.question,
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )

            q.options.forEachIndexed { i, option ->
                OptionChip(index = i + 1, label = option, colors = colors) { broker.answer(q.id, option) }
            }

            // "Other…" — hands off to the input pill (the one focusable text path
            // in this window). Tapping it makes the pill focusable + shows the IME;
            // the overlay routes the submitted line to broker.answer.
            OtherChip(
                colors = colors,
                // With no options there is nothing to tap, so the chip has to be the whole
                // answer affordance rather than the escape hatch beside one. Task 19 of the
                // 2026-08-26 suite asked "Where would you like to go?" with no options and
                // presented the user a bare "Other…" — a dead end that read as a bug.
                label = if (q.options.isEmpty()) "Type your answer" else "Something else…",
                onClick = onOtherTapped,
            )

            // The question is spoken aloud too, and speaking is usually the fastest way out of
            // a card that appeared mid-task. Saying so matters most when there are no options:
            // otherwise the only visible route is a keyboard.
            Text(
                text = "or just say it out loud",
                color = colors.textSecondary,
                fontSize = 12.sp,
            )

            Text(
                text = "Dismiss",
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { broker.dismiss(q.id) }
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun OtherChip(colors: OverlayColorScheme, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(ASK_CHIP_RADIUS),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "type below",
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun OptionChip(index: Int, label: String, colors: OverlayColorScheme, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(ASK_CHIP_RADIUS),
        color = colors.iconBackground,
        border = BorderStroke(1.dp, colors.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.surfaceVariant),
            ) {
                Text(
                    text = "$index",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            Text(text = label, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * How tall the card may be, given how much screen the keyboard has left.
 *
 * ### The bug this fixes
 *
 * Reported 2026-08-26: *"when I tap on other field the text area hides behind the keyboard."*
 * The overlay window is bottom-anchored and WRAP_CONTENT, and its content already lifts over the
 * IME with `imePadding()` — so the panel does clear the keyboard. What it does not do is get
 * SHORTER. With a fixed 340dp cap, card + input pill + keyboard can exceed the screen, and the
 * window grows upward until the card's top — question text and all — is clipped off it. The user
 * sees a field they cannot read, docked to a keyboard, which is exactly the report.
 *
 * So the cap is what is actually left: screen, minus the keyboard, minus the room the input pill
 * and the panel's own padding need below the card. [MIN_CARD_HEIGHT] floors it, because a card
 * squeezed to nothing is a different broken state — the content scrolls inside instead.
 *
 * Recomposes with the IME because `WindowInsets.ime` is an animated, observable inset: the cap
 * shrinks as the keyboard slides in and grows back as it goes.
 */
@Composable
private fun askCardMaxHeight(): Dp {
    val density = LocalDensity.current
    val imeHeight = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    return (screenHeight - imeHeight - SPACE_BELOW_CARD)
        .coerceIn(MIN_CARD_HEIGHT, MAX_CARD_HEIGHT)
}

/** Input pill, its margins, and enough breathing room that the card never touches the pill. */
private val SPACE_BELOW_CARD = 168.dp

/** Below this the card is unreadable; it scrolls its own content instead of shrinking further. */
private val MIN_CARD_HEIGHT = 140.dp

/** Unchanged from before: with no keyboard up, this is still as tall as the card should get. */
private val MAX_CARD_HEIGHT = 340.dp

/** Card/chip geometry, matching the overlay's other floating surfaces (resume chip, input pill). */
private val ASK_CARD_RADIUS = 24.dp
private val ASK_CHIP_RADIUS = 12.dp
private const val ASK_ANIM_IN_MS = 220
private const val ASK_ANIM_OUT_MS = 140
