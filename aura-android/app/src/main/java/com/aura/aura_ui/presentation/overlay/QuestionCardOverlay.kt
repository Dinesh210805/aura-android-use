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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aura.aura_ui.agent.hitl.AskUserBroker
import com.aura.aura_ui.agent.hitl.PendingQuestion
import com.aura.aura_ui.ui.theme.Mono

/**
 * The question card at the top of the screen: what AURA (or a connected MCP client) needs to
 * know, with tap-to-answer choices, and Type and Speak for anything else.
 *
 * - Contract: [show] replaces the card; [hide] is idempotent; main thread only. Answers go to
 *   `AskUserBroker.shared` (first answer wins, so a chip and a spoken answer can race safely).
 * - Why its own window and not the status strip: the strip must never take a touch, and this
 *   card exists to be touched. The agent is blocked on the answer, so nothing is driven under it.
 * - Why not focusable: typing happens in the overlay's prompt box ([onType]), the one window that
 *   flips focusable for the keyboard.
 * - Change together: `AuraOverlayService` (shows it from `AskUserBroker.pending`, hides it for
 *   screenshots, and keeps the status strip off while it is up).
 */
class QuestionCardOverlay(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateOwner: SavedStateRegistryOwner,
    private val onType: () -> Unit,
    private val onSpeak: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null

    fun show(question: PendingQuestion) {
        hide()
        if (!Settings.canDrawOverlays(context)) {
            Log.e(TAG, "Cannot show the question — overlay permission missing")
            return
        }
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent { QuestionCard(question, onType, onSpeak) }
        }
        runCatching { windowManager.addView(compose, layoutParams()) }
            .onSuccess { view = compose }
            .onFailure { Log.e(TAG, "Question card rejected by WindowManager: ${it.message}") }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    /** Off the glass while AURA screenshots, like the strip and the controls. */
    fun setHiddenForCapture(hidden: Boolean) {
        view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = (TOP_MARGIN_DP * density).toInt()
        }
    }

    private companion object {
        const val TAG = "QuestionCardOverlay"
        const val TOP_MARGIN_DP = 8
    }
}

/** The card's colours: the user's light or dark mode, as in the rest of the overlay. */
private val Ui: com.aura.aura_ui.ui.theme.MonoScheme
    @Composable get() = Mono.scheme(dark = com.aura.aura_ui.ui.theme.rememberOverlayDark())

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(question: PendingQuestion, onType: () -> Unit, onSpeak: () -> Unit) {
    val broker = AskUserBroker.shared
    val enter = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = enter, enter = fadeIn() + slideInVertically { -it / 3 }) {
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .shadow(18.dp, Mono.ShapeCard)
                .clip(Mono.ShapeCard)
                .background(Ui.card)
                .border(1.dp, Ui.outline, Mono.ShapeCard)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                Text("AURA needs to know", color = Ui.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    "Skip",
                    color = Ui.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { broker.dismiss(question.id) },
                )
            }
            Text(question.question, color = Ui.textPrimary, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                question.options.forEach { option ->
                    Chip(option, filled = true) { broker.answer(question.id, option) }
                }
                Chip("Type", filled = false, onClick = onType)
                Chip("Speak", filled = false, onClick = onSpeak)
            }
        }
    }
}

/**
 * One answer bubble. A [filled] chip is an answer: ink on the card's opposite colour, so it reads as
 * a button on both the white light card and the near-black dark one. An outlined chip is a way to
 * answer (Type, Speak).
 *
 * - Why theme colours and not white: a white chip on the light theme's white card is invisible, and
 *   the options read as plain text.
 * - Why the press scale: the overlay has no ripple theme, so without it a tap gives no feedback
 *   before the card closes.
 */
@Composable
private fun Chip(label: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    val ui = Ui
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "chipPress")
    Text(
        text = label,
        color = if (filled) ui.card else ui.textPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (filled) ui.textPrimary else ui.chip)
            .border(1.dp, if (filled) Color.Transparent else ui.textSecondary.copy(alpha = 0.35f), shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    )
}
