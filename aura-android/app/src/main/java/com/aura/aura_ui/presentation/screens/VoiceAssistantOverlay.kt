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
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val density = LocalDensity.current
    
    // Get theme-aware colors
    val colors = rememberOverlayColors()
    
    // Sheet expansion state
    var sheetExpanded by remember { mutableStateOf(false) }
    val collapsedHeight = screenHeight * 0.35f
    val expandedHeight = screenHeight * 0.75f
    
    // Drag offset for sheet
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentSheetHeight by animateDpAsState(
        targetValue = if (sheetExpanded) expandedHeight else collapsedHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "sheet_height"
    )
    
    // Draggable state for the response sheet
    val draggableState = rememberDraggableState { delta ->
        dragOffset += delta
        // Determine if we should expand or collapse based on drag direction
        if (dragOffset < -100) {
            sheetExpanded = true
            dragOffset = 0f
        } else if (dragOffset > 100) {
            sheetExpanded = false
            dragOffset = 0f
        }
    }

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
 * Empty state when no conversation
 */
@Composable
private fun EmptyOverlayState(
    suggestedCommands: List<String>,
    recentCommands: List<String>,
    resumeOffer: com.aura.aura_ui.agent.ledger.ResumeOffer?,
    colors: OverlayColorScheme,
    onSuggestionClick: (String) -> Unit,
    onResumeClick: () -> Unit,
    onResumeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Build 3: resume chip for an interrupted run (never auto-resumes — the user taps it).
        resumeOffer?.let { offer ->
            Surface(
                onClick = onResumeClick,
                shape = RoundedCornerShape(12.dp),
                color = AuraPrimary.copy(alpha = 0.12f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = AuraPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = offer.chipLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Dismiss",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.clickable(onClick = onResumeDismiss)
                    )
                }
            }
        }

        // Animated AURA orb
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            AuraPrimary.copy(alpha = 0.3f),
                            AuraSecondary.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = AuraPrimary,
                modifier = Modifier.size(32.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Hi, I'm AURA",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Medium
            ),
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Tap the mic and ask me anything",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Horizontally scrollable suggestions
        if (suggestedCommands.isNotEmpty() || recentCommands.isNotEmpty()) {
            HorizontalSuggestions(
                suggestedCommands = suggestedCommands,
                recentCommands = recentCommands,
                colors = colors,
                onSuggestionClick = onSuggestionClick
            )
        }
    }
}

/**
 * Message bubble for the transcript. WhatsApp-inverse: the user is LEFT-aligned, AURA is
 * RIGHT-aligned under the colored logo avatar. Tap a finalized bubble to reveal actions
 * (copy · like · dislike · share). Used identically by STT/TTS and Gemini Live.
 */
@Composable
private fun OverlayMessageBubble(
    message: ConversationMessage,
    colors: OverlayColorScheme,
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onLike: () -> Unit = {},
    onDislike: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isUser = message.isUser
    var showActions by remember { mutableStateOf(false) }
    // Local like/dislike state: 0 none, 1 like, -1 dislike. Mutually exclusive.
    var reaction by remember(message.id) { mutableIntStateOf(0) }
    val hapticFeedback = rememberHapticFeedback()

    // ── Typing animation ────────────────────────────────────────────────────
    // For streaming bubbles: animate visible character count from 0 → full length
    // each time the text grows. Non-streaming messages display instantly.
    val targetChars = message.text.length
    val animatedChars by animateIntAsState(
        targetValue = if (message.isStreaming) targetChars else targetChars,
        animationSpec = if (message.isStreaming) {
            tween(durationMillis = (targetChars * 18).coerceIn(80, 600))
        } else {
            snap()
        },
        label = "typingChars"
    )
    val displayText = if (message.isStreaming) {
        message.text.take(animatedChars)
    } else {
        message.text
    }

    // Blinking cursor — only animates for streaming bubbles; static 1f otherwise
    val cursorAlpha by if (message.isStreaming) {
        rememberInfiniteTransition(label = "cursor").animateFloat(
            initialValue = 1f, targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
            label = "cursorAlpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    // ────────────────────────────────────────────────────────────────────────

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(horizontalAlignment = if (isUser) Alignment.Start else Alignment.End) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 4.dp else 18.dp,
                    bottomEnd = if (isUser) 18.dp else 4.dp
                ),
                color = if (isUser) {
                    colors.userBubbleColor.copy(alpha = if (message.isPartial) 0.6f else 1f)
                } else {
                    colors.assistantBubbleColor
                },
                border = if (isUser) null else BorderStroke(1.dp, colors.textTertiary.copy(alpha = 0.14f)),
                modifier = Modifier
                    .widthIn(max = 252.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (!message.isPartial) {
                            showActions = !showActions
                        }
                    }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // Main text — with inline cursor appended while streaming
                    val shownText = if (message.isStreaming && animatedChars >= targetChars) {
                        // Text fully typed — show blinking cursor
                        buildString {
                            append(displayText)
                            append("\u2588") // block cursor ▉
                        }
                    } else {
                        displayText
                    }

                    Text(
                        text = shownText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 20.sp
                        ),
                        color = if (isUser) {
                            Color.White.copy(
                                alpha = if (message.isStreaming && animatedChars >= targetChars)
                                    0.7f + 0.3f * cursorAlpha else 1f
                            )
                        } else {
                            colors.textPrimary
                        }
                    )

                    // Timestamp only on finalized messages
                    if (!message.isPartial && !message.isStreaming) {
                        Text(
                            text = message.getRelativeTime(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) {
                                Color.White.copy(alpha = 0.7f)
                            } else {
                                colors.textSecondary
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Message Actions — copy · like · dislike · share
            AnimatedVisibility(
                visible = showActions && !message.isPartial,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                MessageActions(
                    colors = colors,
                    reaction = reaction,
                    onCopy = {
                        hapticFeedback(AuraHapticType.LIGHT)
                        onCopy()
                        showActions = false
                    },
                    onLike = {
                        hapticFeedback(AuraHapticType.LIGHT)
                        reaction = if (reaction == 1) 0 else 1
                        onLike()
                    },
                    onDislike = {
                        hapticFeedback(AuraHapticType.LIGHT)
                        reaction = if (reaction == -1) 0 else -1
                        onDislike()
                    },
                    onShare = {
                        hapticFeedback(AuraHapticType.LIGHT)
                        onShare()
                        showActions = false
                    },
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // AURA reply carries the colored logo avatar on its outer (right) edge.
        if (!isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            AuraLogoAvatar(size = 28.dp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/**
 * Skeleton steps section - Shows the planned steps from task_progress.
 * Briefly displayed before auto-minimize.
 */
@Composable
private fun SkeletonStepsSection(
    taskProgress: com.aura.aura_ui.conversation.TaskProgress,
    colors: OverlayColorScheme,
    modifier: Modifier = Modifier,
) {
    val enterTransition = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enterTransition.animateTo(1f, animationSpec = tween(400))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(enterTransition.value)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.assistantBubbleColor.copy(alpha = 0.6f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Goal header
        Text(
            text = taskProgress.goal,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Step items
        taskProgress.tasks.forEachIndexed { index, step ->
            val isCurrent = index + 1 == taskProgress.current
            val isDone = index + 1 < taskProgress.current

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Step indicator
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isDone -> AuraPrimary.copy(alpha = 0.8f)
                                isCurrent -> AuraPrimary
                                else -> colors.textSecondary.copy(alpha = 0.2f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurrent) Color.White else colors.textSecondary,
                            fontSize = 10.sp,
                        )
                    }
                }

                // Step description
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        isDone -> colors.textSecondary.copy(alpha = 0.6f)
                        isCurrent -> colors.textPrimary
                        else -> colors.textSecondary.copy(alpha = 0.7f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * On-device agent process timeline (Phase 3 slice D). Renders the structured
 * [AgentStep] stream as a clean monochrome vertical timeline — each tool call grouped
 * with its outcome (running spinner → check / cross), under a collapsible header that
 * summarizes progress. Replaces the flat log-line look of [LiveThinkingSection] for
 * the on-device agent.
 */
@Composable
private fun AgentProcessTrace(
    steps: List<AgentStep>,
    isRunning: Boolean,
    colors: OverlayColorScheme,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "trace_chevron",
    )
    val doneCount = steps.count { it.status != AgentStepStatus.RUNNING }
    val headline = when {
        isRunning -> steps.lastOrNull { it.status == AgentStepStatus.RUNNING }?.title?.let { "$it…" }
            ?: "Working…"
        steps.any { it.status == AgentStepStatus.FAILED } -> "Done with issues"
        else -> "Done"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceVariant.copy(alpha = 0.95f),
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header — tap to collapse/expand
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = colors.textPrimary,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(colors.textPrimary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (steps.any { it.status == AgentStepStatus.FAILED }) "!" else "✓",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = colors.textPrimary,
                        )
                    }
                }
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$doneCount/${steps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(260)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(220)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 14.dp, bottom = 12.dp, top = 2.dp),
                ) {
                    steps.forEachIndexed { index, step ->
                        AgentStepRow(
                            step = step,
                            isLast = index == steps.lastIndex,
                            colors = colors,
                        )
                    }
                }
            }
        }
    }
}

/** One row in [AgentProcessTrace]: status dot + connector line, verb, tool, args. */
@Composable
private fun AgentStepRow(
    step: AgentStep,
    isLast: Boolean,
    colors: OverlayColorScheme,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Timeline rail: status indicator + connector to the next step.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(18.dp),
        ) {
            Box(
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (step.status) {
                    AgentStepStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.6.dp,
                        color = colors.textPrimary,
                    )
                    AgentStepStatus.DONE -> StatusGlyph("✓", colors.textPrimary, colors)
                    AgentStepStatus.FAILED -> StatusGlyph("✗", colors.textPrimary, colors)
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .weight(1f)
                        .padding(vertical = 2.dp)
                        .background(colors.textTertiary.copy(alpha = 0.4f)),
                )
            }
        }

        // Step content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = if (step.status == AgentStepStatus.FAILED) colors.textPrimary else colors.textPrimary,
                )
                Text(
                    text = step.tool,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Small circular status glyph (✓ / ✗) for the timeline rail. */
@Composable
private fun StatusGlyph(symbol: String, tint: Color, colors: OverlayColorScheme) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(colors.textPrimary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
            color = tint,
        )
    }
}

/**
 * Live thinking section - Shows real-time agent pipeline outputs during processing.
 * Toggle button (expanded by default) reveals the thinking trace.
 */
@Composable
private fun LiveThinkingSection(
    agentOutputs: List<com.aura.aura_ui.conversation.AgentOutput>,
    processingContext: String,
    colors: OverlayColorScheme,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(true) } // Open by default
    
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_live")
    
    // Pulsing indicator
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    // Shimmer effect
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )
    
    // Rotation for expand icon
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "rotation"
    )
    
    // Auto-scroll to latest output
    val listState = rememberLazyListState()
    LaunchedEffect(agentOutputs.size) {
        if (agentOutputs.isNotEmpty() && isExpanded) {
            listState.animateScrollToItem(agentOutputs.size - 1)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Header bar — tap to toggle
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isExpanded) 0.dp else 16.dp, bottomEnd = if (isExpanded) 0.dp else 16.dp),
            color = colors.surfaceVariant.copy(alpha = 0.95f),
            shadowElevation = 4.dp,
        ) {
            Box {
                // Shimmer overlay
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    colors.accentBlue.copy(alpha = 0.08f),
                                    colors.accentPurple.copy(alpha = 0.12f),
                                    colors.accentBlue.copy(alpha = 0.08f),
                                    Color.Transparent
                                ),
                                start = Offset(x = shimmerOffset * 300f, y = 0f),
                                end = Offset(x = (shimmerOffset + 0.5f) * 300f, y = 50f)
                            )
                        )
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Animated thinking orb
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .alpha(pulseAlpha * 0.4f)
                                .scale(1f + pulseAlpha * 0.15f)
                                .clip(CircleShape)
                                .background(colors.accentBlue.copy(alpha = 0.3f))
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .alpha(0.7f + pulseAlpha * 0.3f)
                                .clip(CircleShape)
                                .background(colors.accentBlue)
                        )
                    }
                    
                    Text(
                        text = if (agentOutputs.isEmpty()) "Thinking..." else agentOutputs.last().output.take(40),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // Toggle icon
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = colors.textSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = rotationAngle }
                    )
                }
            }
        }
        
        // Expanded content — agent output stream
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(animationSpec = tween(200)) + expandVertically(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(250))
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = colors.surface.copy(alpha = 0.6f),
                tonalElevation = 1.dp,
            ) {
                if (agentOutputs.isEmpty()) {
                    // No outputs yet — show waiting state
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.accentBlue
                        )
                        Text(
                            text = processingContext.ifBlank { "Processing your request..." },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(agentOutputs.size) { index ->
                            val output = agentOutputs[index]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Agent badge
                                Text(
                                    text = output.agent,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    color = colors.accentBlue,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colors.accentBlue.copy(alpha = 0.1f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                
                                // Output text
                                Text(
                                    text = output.output,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        lineHeight = 16.sp
                                    ),
                                    color = colors.textSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Custom easing functions for premium animations
private val EaseInOutBack = CubicBezierEasing(0.68f, -0.2f, 0.32f, 1.2f)
private val EaseOutSine = CubicBezierEasing(0.39f, 0.575f, 0.565f, 1f)

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
                        // Animate while AURA speaks too (playback amplitude), not
                        // only while it listens — same wave, both directions.
                        amplitude = if (state.isListening || state.isResponding) state.audioAmplitude else 0f,
                        colors = colors,
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
                        singleLine = true
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
 * Voice Waveform Visualizer - Spectrogram-style with bars responding to speech amplitude
 */
@Composable
private fun VoiceWaveformVisualizer(
    amplitude: Float,
    colors: OverlayColorScheme,
    modifier: Modifier = Modifier,
) {
    val barCount = 24  // More bars for spectrogram look
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    
    // Simulated frequency band amplitudes - each bar gets a slightly different response
    // This creates a spectrogram-like effect where different "frequencies" respond differently
    val barHeights = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(0.1f) } } }
    
    // Update bar heights based on amplitude with frequency-band simulation
    LaunchedEffect(amplitude) {
        val normalizedAmp = amplitude.coerceIn(0f, 1f)
        barHeights.forEachIndexed { index, _ ->
            // Simulate different frequency bands responding to audio
            // Middle frequencies (speech) are more prominent
            val centerWeight = 1f - kotlin.math.abs(index - barCount / 2f) / (barCount / 2f)
            val frequencyResponse = 0.3f + (centerWeight * 0.7f)
            
            // Add some randomness for natural look
            val randomVariation = (Math.random() * 0.4 - 0.2).toFloat()
            val targetHeight = (normalizedAmp * frequencyResponse + randomVariation).coerceIn(0.08f, 1f)
            
            // Smooth transition
            barHeights[index] = barHeights[index] + (targetHeight - barHeights[index]) * 0.5f
        }
    }
    
    // Continuous animation for when there's no amplitude input (idle state)
    val idleWavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * kotlin.math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "idle_wave"
    )
    
    // Outer glow pulse
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )
    
    Box(
        modifier = modifier
            .height(48.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // Background glow effect
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(44.dp)
                .graphicsLayer { alpha = glowPulse * amplitude.coerceIn(0.3f, 1f) }
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            colors.textSecondary.copy(alpha = 0.15f),
                            colors.textSecondary.copy(alpha = 0.25f),
                            colors.textSecondary.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
        )
        
        // Spectrogram-style animated bars
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(barCount) { index ->
                // Calculate height based on amplitude or idle animation
                val baseHeight = if (amplitude > 0.05f) {
                    barHeights.getOrElse(index) { 0.1f }
                } else {
                    // Idle sine wave animation when no audio
                    val phase = idleWavePhase + (index * 0.3f)
                    0.15f + kotlin.math.sin(phase).toFloat() * 0.1f
                }
                
                // Animate height changes smoothly
                val animatedHeight by animateFloatAsState(
                    targetValue = baseHeight,
                    animationSpec = tween(50, easing = LinearEasing),
                    label = "bar_height_$index"
                )
                
                // Use textSecondary color (same as "Ask AURA" placeholder)
                val barColor = colors.textSecondary
                
                // Intensity based on height for glow effect
                val intensity = animatedHeight.coerceIn(0.3f, 1f)
                
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(animatedHeight.coerceIn(0.08f, 0.95f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    barColor.copy(alpha = intensity),
                                    barColor.copy(alpha = intensity * 0.5f)
                                )
                            )
                        )
                )
            }
        }
    }
}

// Color interpolation helper
private fun lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = start.alpha + (end.alpha - start.alpha) * fraction
    )
}

/**
 * Message action buttons
 */
@Composable
private fun MessageActions(
    colors: OverlayColorScheme,
    reaction: Int,
    onCopy: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant.copy(alpha = 0.8f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MessageActionButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = "Copy",
                tint = colors.textSecondary,
                onClick = onCopy
            )
            MessageActionButton(
                icon = if (reaction == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = "Like",
                tint = if (reaction == 1) AuraBrandViolet else colors.textSecondary,
                onClick = onLike
            )
            MessageActionButton(
                icon = if (reaction == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                contentDescription = "Dislike",
                tint = if (reaction == -1) AuraBrandMagenta else colors.textSecondary,
                onClick = onDislike
            )
            MessageActionButton(
                icon = Icons.Default.Share,
                contentDescription = "Share",
                tint = colors.textSecondary,
                onClick = onShare
            )
        }
    }
}

@Composable
private fun MessageActionButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Quick Actions Section with suggestions and recent commands
 */
@Composable
private fun QuickActionsSection(
    suggestedCommands: List<String>,
    recentCommands: List<String>,
    colors: OverlayColorScheme,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Suggested Commands
        if (suggestedCommands.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Try saying:",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                
                suggestedCommands.take(3).forEach { command ->
                    SuggestionChip(
                        text = command,
                        colors = colors,
                        onClick = { onSuggestionClick(command) }
                    )
                }
            }
        }
        
        // Recent Commands
        if (recentCommands.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Recent:",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    recentCommands.take(2).forEach { command ->
                        RecentCommandChip(
                            text = command,
                            colors = colors,
                            onClick = { onSuggestionClick(command) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(
    text: String,
    colors: OverlayColorScheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = rememberHapticFeedback()
    
    Surface(
        onClick = {
            hapticFeedback(AuraHapticType.LIGHT)
            onClick()
        },
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceVariant.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, colors.accentBlue.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = null,
                tint = colors.accentBlue,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary
            )
        }
    }
}

@Composable
private fun RecentCommandChip(
    text: String,
    colors: OverlayColorScheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = rememberHapticFeedback()
    
    Surface(
        onClick = {
            hapticFeedback(AuraHapticType.LIGHT)
            onClick()
        },
        shape = RoundedCornerShape(12.dp),
        color = colors.accentBlue.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, colors.accentBlue.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = colors.accentBlue,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Horizontally scrollable suggestions
 */
@Composable
private fun HorizontalSuggestions(
    suggestedCommands: List<String>,
    recentCommands: List<String>,
    colors: OverlayColorScheme,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Suggested commands
        if (suggestedCommands.isNotEmpty()) {
            Text(
                text = "Try saying:",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(suggestedCommands) { command ->
                    SuggestionChip(
                        text = command,
                        colors = colors,
                        onClick = { onSuggestionClick(command) }
                    )
                }
            }
        }
        
        // Recent commands
        if (recentCommands.isNotEmpty()) {
            Text(
                text = "Recent:",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(recentCommands) { command ->
                    RecentCommandChip(
                        text = command,
                        colors = colors,
                        onClick = { onSuggestionClick(command) }
                    )
                }
            }
        }
    }
}

// ============================================================================
// PREVIEW
// ============================================================================

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoiceAssistantOverlayPreview() {
    val sampleMessages = listOf(
        ConversationMessage(
            text = "Open the camera app",
            isUser = true
        ),
        ConversationMessage(
            text = "Opening Camera app for you...",
            isUser = false
        ),
        ConversationMessage(
            text = "What's on my screen?",
            isUser = true
        ),
        ConversationMessage(
            text = "I can see the Camera app is now open. You're viewing through the main camera. Would you like me to take a photo or switch cameras?",
            isUser = false
        )
    )
    
    MaterialTheme {
        VoiceAssistantOverlay(
            state = VoiceAssistantState(
                isVisible = true,
                messages = sampleMessages,
                serverConnected = true
            ),
            callbacks = VoiceAssistantCallbacks()
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoiceAssistantOverlayListeningPreview() {
    MaterialTheme {
        VoiceAssistantOverlay(
            state = VoiceAssistantState(
                isVisible = true,
                isListening = true,
                partialTranscript = "Open the...",
                audioAmplitude = 0.7f,
                suggestedCommands = listOf("Open camera", "Turn on WiFi", "What's on screen?"),
                recentCommands = listOf("Open settings", "Call mom")
            ),
            callbacks = VoiceAssistantCallbacks()
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoiceAssistantOverlayProcessingPreview() {
    MaterialTheme {
        VoiceAssistantOverlay(
            state = VoiceAssistantState(
                isVisible = true,
                isProcessing = true,
                processingContext = "Analyzing screen...",
                messages = listOf(
                    ConversationMessage(
                        text = "What's on my screen?",
                        isUser = true
                    )
                ),
                serverConnected = true
            ),
            callbacks = VoiceAssistantCallbacks()
        )
    }
}
