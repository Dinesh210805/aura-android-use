package com.aura.aura_ui.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.aura.aura_ui.agent.hitl.AskUserBroker
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.aura.aura_ui.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.conversation.AgentStep
import com.aura.aura_ui.conversation.AgentStepStatus
import com.aura.aura_ui.conversation.ConversationMessage
import com.aura.aura_ui.conversation.ConversationPhase
import com.aura.aura_ui.presentation.components.*
import com.aura.aura_ui.presentation.utils.AuraHapticType
import com.aura.aura_ui.presentation.utils.rememberHapticFeedback
import com.aura.aura_ui.ui.theme.*
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

// ============================================================================
// AURA VOICE ASSISTANT OVERLAY UI
// Premium Apple-Inspired Design with Modern Voice Assistant UX
// ============================================================================

/**
 * The overlay's fixed color scheme. The app itself is light-only (dark mode was
 * removed as a user setting), but the overlay is DELIBERATELY always dark: it
 * floats over arbitrary apps, so a charcoal surface reads as "AURA's own layer"
 * regardless of what's beneath it. The palette is the app's own Capsule charcoal
 * tokens ([Mono.scheme] dark) — the same warm near-black used by the app's dark
 * surfaces — so the overlay and the app share one design language.
 */
@Composable
private fun rememberOverlayColors(): OverlayColorScheme = OverlayColors

/**
 * Color scheme interface for overlay
 */
// internal, not private: AskUserCard renders from this same scheme so the HITL card cannot drift
// into a different palette from the window it floats in (it used to hardcode flat Mono tokens).
internal data class OverlayColorScheme(
    val scrim: Color,
    val inputBarBackground: Color,
    val responseSheetBackground: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val dragHandle: Color,
    val iconBackground: Color,
    val accentGreen: Color,
    val accentBlue: Color,
    val accentPurple: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val micActiveGlow: Color,
    val micPulseGlow: Color,
    val userBubbleColor: Color,
    val assistantBubbleColor: Color,
)

/**
 * Charcoal overlay palette — built from the app's Capsule dark tokens so it can
 * never drift from the app's own dark surfaces. `canvas`/`card`/`chip` are the
 * three warm-charcoal elevations; text is the warm off-white pair.
 */
private val OverlayColors: OverlayColorScheme = run {
    val cap = Mono.scheme(dark = true)
    OverlayColorScheme(
        // Dim the app behind so the dark overlay reads as a distinct layer.
        scrim = Color.Black.copy(alpha = 0.32f),
        // Pill = the most-raised surface (chip); sheet = the card elevation.
        inputBarBackground = cap.chip.copy(alpha = 0.98f),
        responseSheetBackground = cap.card.copy(alpha = 0.98f),
        surface = cap.card,
        surfaceVariant = cap.chip,
        dragHandle = cap.textSecondary.copy(alpha = 0.5f),
        iconBackground = cap.chip,
        // Neutral warm greys for cursor / shimmer / secondary accents.
        accentGreen = cap.textPrimary.copy(alpha = 0.8f),
        accentBlue = Color(0xFF57534A),
        accentPurple = cap.textSecondary,
        textPrimary = cap.textPrimary,
        textSecondary = cap.textSecondary,
        textTertiary = cap.textSecondary.copy(alpha = 0.55f),
        micActiveGlow = cap.textPrimary,
        micPulseGlow = Color(0xFFBBBBBB),
        userBubbleColor = Color(0xFF33302A),
        assistantBubbleColor = cap.chip,
    )
}

/**
 * State holder for the Voice Assistant Overlay
 */
data class VoiceAssistantState(
    val isVisible: Boolean = true,
    val isListening: Boolean = false,
    val isProcessing: Boolean = false,
    val isResponding: Boolean = false,
    val partialTranscript: String = "",
    val messages: List<ConversationMessage> = emptyList(),
    val serverConnected: Boolean = false,
    val audioAmplitude: Float = 0f,
    val processingContext: String = "", // "Executing action...", "Analyzing screen...", etc.
    val suggestedCommands: List<String> = emptyList(),
    val recentCommands: List<String> = emptyList(),
    // Agent outputs for thinking trace
    val agentOutputs: List<com.aura.aura_ui.conversation.AgentOutput> = emptyList(),
    val latestAgentOutput: com.aura.aura_ui.conversation.AgentOutput? = null,
    // Task progress skeleton steps
    val taskProgress: com.aura.aura_ui.conversation.TaskProgress? = null,
    // True when a Gemini Live bidirectional session is active (session persists across turns)
    val isGeminiLiveSession: Boolean = false,
    // True while the user is actively typing. The host overlay window is
    // FLAG_NOT_FOCUSABLE by default (so it doesn't steal global focus), which also
    // blocks the IME — so the service flips it focusable on input activation. Drives
    // the tap-to-activate scrim + focus request in the input bar.
    val inputActive: Boolean = false,
    // On-device agent step timeline (tool calls grouped with their outcome). When
    // non-empty, the chat shows the redesigned AgentProcessTrace instead of the
    // legacy LiveThinkingSection.
    val agentSteps: List<com.aura.aura_ui.conversation.AgentStep> = emptyList(),
    // Build 3 (resumable runs): a persisted interrupted run worth continuing. When non-null,
    // the resting (empty) overlay shows a "Resume: <goal> (step k/n)" chip. Never auto-resumes.
    val resumeOffer: com.aura.aura_ui.agent.ledger.ResumeOffer? = null,
    // Screen-share (MediaProjection) is currently held — AURA can see the screen. Drives the
    // brand-tinted screen-share toggle in the input pill.
    val isScreenSharing: Boolean = false,
    /** Space kept free under the prompt box, for the run controls while a run is paused. */
    val bottomLiftDp: Int = 0,
)

/**
 * Callbacks for Voice Assistant interactions
 */
data class VoiceAssistantCallbacks(
    val onDismiss: () -> Unit = {},
    val onMicClick: () -> Unit = {},
    val onSettingsClick: () -> Unit = {},
    val onTextSubmit: (String) -> Unit = {},
    val onMessageCopy: (String) -> Unit = {},
    val onMessageRetry: (ConversationMessage) -> Unit = {},
    val onMessageShare: (String) -> Unit = {},
    val onMessageDelete: (String) -> Unit = {},
    val onSuggestionClick: (String) -> Unit = {},
    val onClearChat: () -> Unit = {},
    // Text-input focus lifecycle. The host service toggles the overlay window's
    // FLAG_NOT_FOCUSABLE so the soft keyboard can attach (see
    // AuraOverlayService.setOverlayInputFocusable).
    val onInputActivate: () -> Unit = {},
    val onInputDeactivate: () -> Unit = {},
    // Build 3: tap the resume chip to continue the interrupted run; dismiss to abandon it.
    val onResumeClick: () -> Unit = {},
    val onResumeDismiss: () -> Unit = {},
    // Toggle the MediaProjection screen-share session on/off (auto-requests permission when on).
    val onScreenShareToggle: () -> Unit = {},
    // Per-message feedback on an AI reply.
    val onMessageLike: (ConversationMessage) -> Unit = {},
    val onMessageDislike: (ConversationMessage) -> Unit = {},
)

/**
 * Main Voice Assistant Overlay Composable
 * Displays a Google Assistant-style floating overlay
 */
@Composable
fun VoiceAssistantOverlay(
    state: VoiceAssistantState,
    callbacks: VoiceAssistantCallbacks,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val colors = rememberOverlayColors()

    // Open animation — an elastic, physics-based rise (spring → 60fps) with a slight
    // scale-up + overshoot so it feels alive rather than a slide/PPT transition. Fires in
    // step with the open haptic. Fade is a quick, separate curve so the
    // content doesn't ghost during the bounce.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val rise by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 240f),
        label = "overlayRise",
    )
    val fade by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = LinearOutSlowInEasing),
        label = "overlayFade",
    )
    val riseTranslation = with(density) { (1f - rise) * 96.dp.toPx() }
    val openScale = 0.88f + 0.12f * rise

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        // The host window is bottom-anchored and WRAP_CONTENT (see
        // AuraOverlayService.createWindowParams). Nothing here may claim the full screen
        // height: anything that does re-inflates the window to full screen and brings back
        // the dead background it was shrunk to avoid. Hence fillMaxWidth (not fillMaxSize)
        // here, and matchParentSize on the rim glow.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // CRITICAL: imePadding() moves content up when keyboard appears
                .imePadding()
        ) {
            // The brand rim glow is NOT drawn here. It used to be a Compose copy
            // (AuraEdgeGlow) hand-matched to the automation glow, which meant two
            // implementations drifting apart — and once this window stopped covering the
            // screen it could only hug the panel, which read wrong. There is now one
            // implementation, in its own full-screen pass-through window:
            // AuraOverlayService.showEdgeGlow() → [com.aura.aura_ui.overlay.EdgeGlow].

            // Content Column — elastic spring rise + scale on open.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = fade
                        translationY = riseTranslation
                        scaleX = openScale
                        scaleY = openScale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    },
                verticalArrangement = Arrangement.Bottom
            ) {
                // HITL question card — when the agent calls ask_user, this floats
                // just above the pill with tap-choices + an "Other…" chip. Observes
                // the process-wide AskUserBroker directly; hidden when nothing pends.
                // "Other…" activates the input pill (the one focusable text path in
                // this window); the submitted line is routed to the pending question.
                //
                // Collected HERE rather than inside each consumer so the card and the
                // pill react to the same emission — the pill must be in text mode
                // before "Other…" can hand it focus.
                val pendingQuestion by AskUserBroker.shared.pending.collectAsState()

                AskUserCard(
                    pending = pendingQuestion,
                    onOtherTapped = callbacks.onInputActivate,
                    colors = colors,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                // Resume chip — floats just above the pill when an interrupted run is offered.
                state.resumeOffer?.let { offer ->
                    ResumeChip(
                        offer = offer,
                        colors = colors,
                        onResumeClick = callbacks.onResumeClick,
                        onResumeDismiss = callbacks.onResumeDismiss,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Swipe down on the pill to close. Tapping the empty space above no
                // longer dismisses — that space is outside the window now and belongs to
                // the app behind — so the gesture lives here instead. The transcript
                // sheet owns its own vertical drag (expand/collapse), so putting this on
                // the pill keeps the two from competing for the same pointer.
                var pillDragDy by remember { mutableFloatStateOf(0f) }
                val dismissThresholdPx = with(density) { 72.dp.toPx() }

                // Input Bar (Compact Floating Pill)
                InputBar(
                    state = state,
                    colors = colors,
                    onMicClick = callbacks.onMicClick,
                    onTextSubmit = callbacks.onTextSubmit,
                    onDismiss = callbacks.onDismiss,
                    onSettingsClick = callbacks.onSettingsClick,
                    onInputActivate = callbacks.onInputActivate,
                    onInputDeactivate = callbacks.onInputDeactivate,
                    questionPending = pendingQuestion != null,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { pillDragDy = 0f },
                                onDragCancel = { pillDragDy = 0f },
                                onDragEnd = {
                                    if (pillDragDy > dismissThresholdPx) callbacks.onDismiss()
                                    pillDragDy = 0f
                                },
                            ) { _, dragAmount -> pillDragDy += dragAmount }
                        }
                )
                if (state.bottomLiftDp > 0) Spacer(modifier = Modifier.height(state.bottomLiftDp.dp))
            }
        }
    }
}

/**
 * Resume-an-interrupted-run chip, floating just above the input pill.
 * Reuses the persisted [ResumeOffer]; never auto-resumes.
 */
@Composable
private fun ResumeChip(
    offer: com.aura.aura_ui.agent.ledger.ResumeOffer,
    colors: OverlayColorScheme,
    onResumeClick: () -> Unit,
    onResumeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, colors.textTertiary.copy(alpha = 0.25f)),
        shadowElevation = 10.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = offer.chipLabel(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Surface(
                onClick = onResumeClick,
                shape = Mono.ShapePill,
                color = Mono.Ink,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "Resume",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Mono.TextOnInk,
                    )
                }
            }
            IconButton(onClick = onResumeDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}









/**
 * Bottom Input Bar (Compact Floating Pill)
 */
@Composable
private fun InputBar(
    state: VoiceAssistantState,
    colors: OverlayColorScheme,
    onMicClick: () -> Unit,
    onTextSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    onSettingsClick: () -> Unit,
    onInputActivate: () -> Unit = {},
    onInputDeactivate: () -> Unit = {},
    /**
     * True while the agent is suspended on an `ask_user` question. Forces the pill into TEXT mode
     * — see [showWave] below for why the waveform must lose this fight.
     */
    questionPending: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var textInput by remember { mutableStateOf("") }
    val hapticFeedback = rememberHapticFeedback()

    // Mic button animation
    val infiniteTransition = rememberInfiniteTransition(label = "mic_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    // A pending ask_user question BEATS the waveform, always.
    //
    // This was the "I can't type anything into the Other field" bug. ask_user fires *during* a
    // task, and in a Live session that means isGeminiLiveSession && isProcessing — so showWave was
    // true and the pill rendered a waveform INSTEAD of the TextField. Tapping "Other…" flipped the
    // window focusable and set inputActive, but the focusRequester it targets lives inside the
    // TextField branch, which was never composed. There was literally nothing on screen to type
    // into, deterministically, in exactly the situation where the answer is needed most.
    val showWave = !questionPending &&
        (
            state.isListening ||
                (state.isGeminiLiveSession && (state.isProcessing || state.isResponding))
            )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(percent = 50),
        color = colors.inputBarBackground,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The screen-share action is intentionally hidden because AURA now reads the
            // screen through AccessibilityService.takeScreenshot(). Keep this leading slot
            // useful: it is the direct escape hatch for dismissing the chat pill.
            IconButton(
                onClick = {
                    hapticFeedback(AuraHapticType.LIGHT)
                    onDismiss()
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.iconBackground)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close chat",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Voice waveform or text input field
            if (showWave) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    VoiceWaveformVisualizer(
                        // The user's voice only: AURA's playback must not move it.
                        amplitude = if (state.isListening) state.audioAmplitude else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Phase verb shown beneath the waveform.
                    // LISTENING with no transcript → empty: the waveform animation already
                    // communicates listening state; showing "Listening" alongside the mic
                    // button is redundant and the user explicitly doesn't want it.
                    val phaseLabel = when {
                        state.isListening && state.partialTranscript.isNotBlank() ->
                            state.partialTranscript
                        state.isListening -> ""
                        state.isProcessing -> if (state.isGeminiLiveSession) "Executing task..." else "Thinking..."
                        state.isResponding -> "Speaking..."
                        else -> ""
                    }
                    if (phaseLabel.isNotBlank()) {
                        Text(
                            text = phaseLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.isListening && state.partialTranscript.isNotBlank())
                                colors.textPrimary
                            else
                                colors.textSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                // Talking isn't always possible: switch to the keyboard. The mic closes.
                Text(
                    text = "Type",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(colors.iconBackground)
                        .clickable {
                            hapticFeedback(AuraHapticType.LIGHT)
                            onInputActivate()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            } else {
                val focusRequester = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                // The host overlay window is FLAG_NOT_FOCUSABLE by default, so the IME
                // can't attach until the input is activated. Once the service flips the
                // window focusable (state.inputActive), request focus + show the keyboard.
                LaunchedEffect(state.inputActive) {
                    if (state.inputActive) {
                        runCatching { focusRequester.requestFocus() }
                        keyboard?.show()
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    TextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = {
                            Text(
                                text = "Ask anything",
                                color = colors.textSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { if (!it.isFocused && state.inputActive) onInputDeactivate() },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = colors.accentBlue,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        // Enter sends and puts the keyboard away, like the send button.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (textInput.isNotBlank()) {
                                    onTextSubmit(textInput)
                                    textInput = ""
                                }
                                onInputDeactivate()
                            },
                        ),
                    )
                    // Tap-to-activate scrim — present only until the window is focusable.
                    // Touches reach views even in a FLAG_NOT_FOCUSABLE window, so this
                    // fires reliably where the TextField's own focus request cannot.
                    if (!state.inputActive) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onInputActivate() }
                        )
                    }
                }
            }
            
            // Primary action — the colored Aura logo on a white round (replaces the mic).
            // Static; tap to start/stop listening or end an active Gemini Live session.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (state.isGeminiLiveSession || state.isListening) {
                            hapticFeedback(AuraHapticType.RECORDING_STOP)
                        } else {
                            hapticFeedback(AuraHapticType.RECORDING_START)
                        }
                        onMicClick()
                    },
                contentAlignment = Alignment.Center,
            ) {
                AuraLogoAvatar(size = 40.dp)
            }
            
            // Send button (shown when text is entered)
            AnimatedVisibility(visible = textInput.isNotBlank()) {
                IconButton(
                    onClick = {
                        hapticFeedback(AuraHapticType.MEDIUM)
                        onTextSubmit(textInput)
                        textInput = ""
                        // Restore the non-focusable overlay window + hide the IME.
                        onInputDeactivate()
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.accentBlue)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// NEW COMPONENTS
// ============================================================================

/**
 * The voice as a white ribbon: three sine strands that breathe with the mic level and drift
 * sideways, tapering to nothing at both ends. Calm and nearly flat in silence.
 *
 * - Contract: [amplitude] is linear RMS as a fraction of full scale (0..1), the scale every
 *   capture path reports. Pass 0 when not listening.
 * - Why dB: speech RMS sits around 0.01..0.2 of full scale, so a linear height would barely move.
 *   [waveLevel] maps [WAVE_FLOOR_DB]..[WAVE_CEIL_DB] onto 0..1, and the floor keeps room noise still.
 * - Why the level is smoothed: raw mic levels jump every 40 ms; a spring makes the ribbon swell
 *   and settle like a voice instead of flickering.
 */
@Composable
private fun VoiceWaveformVisualizer(
    amplitude: Float,
    modifier: Modifier = Modifier,
) {
    val level by animateFloatAsState(
        targetValue = waveLevel(amplitude),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 120f),
        label = "waveLevel",
    )
    val drift = rememberInfiniteTransition(label = "waveDrift")
    val phase by drift.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(WAVE_PERIOD_MS, easing = LinearEasing)),
        label = "wavePhase",
    )
    Canvas(
        modifier = modifier
            .height(48.dp)
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        val midY = size.height / 2f
        val maxA = size.height * 0.42f
        val baseA = size.height * 0.04f
        WAVE_STRANDS.forEach { (freq, speed, alpha) ->
            val path = Path()
            val a = baseA + maxA * level
            var x = 0f
            while (x <= size.width) {
                val t = x / size.width
                val taper = kotlin.math.sin(Math.PI * t).toFloat()
                val y = midY + a * taper * kotlin.math.sin(t * freq * 2 * Math.PI + phase * speed).toFloat()
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 3f
            }
            drawPath(path, Color.White.copy(alpha = alpha), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Linear RMS (0..1 of full scale) → 0..1 on a dB scale; 0 below the noise floor. */
private fun waveLevel(rms: Float): Float {
    if (rms <= 0f) return 0f
    val db = 20f * kotlin.math.log10(rms)
    return ((db - WAVE_FLOOR_DB) / (WAVE_CEIL_DB - WAVE_FLOOR_DB)).coerceIn(0f, 1f)
}

/** Each strand: cycles across the width, drift speed, opacity. */
private val WAVE_STRANDS = listOf(Triple(1.5f, 1f, 0.95f), Triple(2.2f, -1.4f, 0.5f), Triple(3.1f, 2f, 0.28f))
private const val WAVE_PERIOD_MS = 2_400

/** Below this the mic is treated as silence. Raise it if room noise moves the ribbon. */
private const val WAVE_FLOOR_DB = -48f

/** Full height. Lower it if normal speech never reaches the top. */
private const val WAVE_CEIL_DB = -12f








// ============================================================================
// PREVIEW
// ============================================================================



