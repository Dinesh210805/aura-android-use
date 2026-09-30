package com.aura.aura_ui.presentation.screens.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aura.aura_ui.R
import com.aura.aura_ui.agent.conversation.PersonaOwner
import com.aura.aura_ui.remote.AuraSignIn
import com.aura.aura_ui.remote.AuthState
import com.aura.aura_ui.remote.SignInOutcome
import com.aura.aura_ui.agent.conversation.CompanionConfig
import com.aura.aura_ui.agent.conversation.LiveModelResolver
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.llm.ModelCatalog
import com.aura.aura_ui.agent.llm.RestrictedAppClassifier
import com.aura.aura_ui.mcp.AppRestrictedApps
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.presentation.components.AiDisclaimer
import com.aura.aura_ui.presentation.components.AuraLogoBubble
import com.aura.aura_ui.presentation.components.AuraSwitch
import com.aura.aura_ui.presentation.components.MonoCardGroup
import com.aura.aura_ui.presentation.components.MonoDivider
import com.aura.aura_ui.presentation.components.MonoRow
import com.aura.aura_ui.presentation.components.MonoSectionLabel
import com.aura.aura_ui.presentation.components.permissionIcon
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.presentation.permissions.AuraPermission
import com.aura.aura_ui.presentation.permissions.PermissionRegistry
import com.aura.aura_ui.presentation.permissions.openPermissionSystemPage
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.utils.AppInventoryScanner
import com.aura.aura_ui.utils.isLaunchable
import com.aura.aura_ui.presentation.utils.HapticUtils
import com.aura.mcp.bridge.RestrictedAppEntry
import com.aura.mcp.bridge.RestrictedTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============================================================================
// ONBOARDING — 10-step wizard with Natural Water Droplet Ripples & Crimson Canvas:
// 0: Cinematic Landing (Intro Text -> Natural Water Droplet Ripples + Meet AURA)
// 1: How AURA Works (Crimson Bloom Canvas with Capabilities & Disclosure)
// 2: Personalize & Google OAuth
// 3: Restricted Settings (Android 13+)
// 4: Accessibility Service (anim_accessibility.html)
// 5: Voice & Assistant Overlay (anim_microphone.html)
// 6: AURA Virtual Keyboard (anim_keyboard.html)
// 7: Notifications & Live Alerts (anim_notifications.html)
// 8: Gemini API Key Verification
// 9: Restricted Apps Scanner
// ============================================================================

private const val STEP_COUNT = 10

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableIntStateOf(0) }
    var keyVerified by remember { mutableStateOf(false) }
    var policyAccepted by remember { mutableStateOf(false) }
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    // The funnel: which step people reach, to see where onboarding loses them (no inputs sent).
    LaunchedEffect(step) { com.aura.aura_ui.telemetry.AuraAnalytics.onboardingStep(context, step) }
    // Seeded from — and written straight back to — the SAME store Settings' "What should AURA
    // call you?" field uses. It used to be a bare `remember { mutableStateOf("") }`: onboarding
    // asked for your name, dropped it the moment the step scrolled past, and Settings then asked
    // for it again. Persisting per keystroke (exactly as CompanionScreen does) also means the
    // name survives backing out of onboarding or the process dying mid-flow.
    var userName by remember { mutableStateOf(PersonaOwner.get(context).orEmpty()) }
    // Firebase Auth persists its session, so this is already true on every launch after the
    // first — the sign-in step then renders as "signed in as …" rather than asking again.
    var signedInEmail by remember { mutableStateOf(AuthState.email()) }
    // AuthState is backed by Firebase, not a Compose observable. Keep a local UI state as well
    // so the Next button is invalidated immediately when Credential Manager returns.
    var isSignedIn by remember { mutableStateOf(AuthState.isSignedIn()) }
    var signingIn by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Auto-refresh permissions on return from settings
    var refreshPermissions by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions++
                isSignedIn = AuthState.isSignedIn()
                AuthState.email()?.let { signedInEmail = it }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshPermissions++ }

    // Step 2 is the hard gate: AURA does not run for an unsigned account. Everything below it
    // (permissions, provider key) is wasted effort for someone who cannot pass it, so it blocks
    // here rather than at the end.
    val canProceed = (step != 1 || policyAccepted) &&
        (step != 2 || isSignedIn) &&
        (step != 8 || keyVerified)

    // Standard Ink / TextOnInk button colors matching AURA
    val buttonBg = if (scheme.isDark) Color.White else Mono.Ink
    val buttonFg = if (scheme.isDark) Mono.Ink else Mono.TextOnInk
    val onboardingCanvas = Color(0xFFFCF4E6) // Signature Cream White

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(onboardingCanvas)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(horizontal = if (step == 0) 0.dp else 20.dp),
    ) {
        val isPermissionStep = step in 4..7

        if (step > 0) {
            Spacer(modifier = Modifier.height(10.dp))

            // Step pager row (with Back button when on auto-advancing permission steps)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (isPermissionStep) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFEFE8DA),
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { step-- },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color(0xFF141110),
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.size(32.dp))
                }

                // Step pager pills (Monochrome style)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(STEP_COUNT) { i ->
                        val width by animateDpAsState(
                            targetValue = if (i == step) 18.dp else 5.dp,
                            label = "step_width",
                        )
                        val color by androidx.compose.animation.animateColorAsState(
                            targetValue = if (i == step) buttonBg else scheme.outline.copy(alpha = 0.5f),
                            label = "step_color",
                        )
                        Surface(
                            shape = CircleShape,
                            color = color,
                            modifier = Modifier
                                .height(4.dp)
                                .width(width),
                        ) {}
                    }
                }

                Spacer(modifier = Modifier.size(32.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "onboardingStep",
            modifier = Modifier.weight(1f),
        ) { s ->
            when (s) {
                0 -> LandingStep(onAdvance = { if (step == 0) step = 1 })
                1 -> HowAuraWorksStep(
                    accepted = policyAccepted,
                    onAcceptedChanged = { policyAccepted = it },
                )
                2 -> UserProfileAndOAuthStep(
                    name = userName,
                    onNameChanged = {
                        userName = it
                        PersonaOwner.set(context, it)
                    },
                    signedInEmail = signedInEmail,
                    signedIn = isSignedIn,
                    signingIn = signingIn,
                    error = signInError,
                    onSignIn = {
                        signingIn = true
                        signInError = null
                        scope.launch {
                            when (val out = AuraSignIn(context).signIn(context)) {
                                is SignInOutcome.Success -> {
                                    signedInEmail = out.email ?: AuthState.email()
                                    isSignedIn = true
                                }
                                // Backing out of the picker is a choice, not a fault — saying
                                // "sign-in failed" for it reads as a bug in AURA.
                                SignInOutcome.Cancelled -> Unit
                                SignInOutcome.NoGoogleAccount -> signInError =
                                    "No Google account on this phone. Add one in Android " +
                                        "Settings, then try again."
                                is SignInOutcome.Misconfigured -> signInError = out.message
                                is SignInOutcome.Failed -> signInError = out.message
                            }
                            signingIn = false
                        }
                    },
                )
                3 -> RestrictedSettingsStep()
                4 -> AccessibilityPermissionStep(
                    refreshTrigger = refreshPermissions,
                    onOpenSystem = { openPermissionSystemPage(context, it, onScreenCapture = {}) },
                    onAutoAdvance = { if (step == 4) step = 5 },
                )
                5 -> VoiceAndOverlayPermissionStep(
                    refreshTrigger = refreshPermissions,
                    onRequestRuntime = { runtimeLauncher.launch(it.toTypedArray()) },
                    onOpenSystem = { openPermissionSystemPage(context, it, onScreenCapture = {}) },
                    onAutoAdvance = { if (step == 5) step = 6 },
                )
                6 -> KeyboardPermissionStep(
                    refreshTrigger = refreshPermissions,
                    onOpenSystem = { openPermissionSystemPage(context, it, onScreenCapture = {}) },
                    onAutoAdvance = { if (step == 6) step = 7 },
                )
                7 -> NotificationsPermissionStep(
                    refreshTrigger = refreshPermissions,
                    onRequestRuntime = { runtimeLauncher.launch(it.toTypedArray()) },
                    onOpenSystem = { openPermissionSystemPage(context, it, onScreenCapture = {}) },
                    onAutoAdvance = { if (step == 7) step = 8 },
                )
                8 -> GeminiKeyStep(onVerifiedChanged = { keyVerified = it })
                else -> RestrictedAppsStep()
            }
        }

        if (step > 0 && !isPermissionStep) {
            Spacer(modifier = Modifier.height(10.dp))

            // Unified Rounded Pill Navbar (Exact size as inside app settings, clean text only, no arrow marks)
            Surface(
                shape = CircleShape,
                color = if (canProceed) buttonBg else buttonBg.copy(alpha = 0.4f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(bottom = 4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (scheme.isDark) Color(0xFF2B2B28) else Color(0xFF222220),
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable { step-- },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .clickable(enabled = canProceed) {
                                if (step < STEP_COUNT - 1) {
                                    step++
                                } else {
                                    com.aura.aura_ui.telemetry.AuraAnalytics.onboardingComplete(context)
                                    onDone()
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = when (step) {
                                1 -> if (policyAccepted) "I Understand" else "Accept Terms"
                                2 -> if (isSignedIn) "Next" else "Sign in to continue"
                                3 -> "Next"
                                4 -> "Next"
                                5 -> "Next"
                                6 -> "Next"
                                7 -> "Next"
                                8 -> if (keyVerified) "Next" else "Verify Key"
                                else -> "Launch AURA"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp,
                            ),
                            color = buttonFg,
                        )
                    }

                    // Balance spacing on the right
                    Spacer(modifier = Modifier.size(44.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

/** Standardized Embedded HTML Animation Card */
@Composable
fun OnboardingAnimationCard(
    assetFileName: String,
    modifier: Modifier = Modifier,
    onWebViewCreated: ((WebView) -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(0x00000000)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    loadUrl("file:///android_asset/onboarding/$assetFileName")
                    onWebViewCreated?.invoke(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Compact Clean Permission Row: Icon + Permission Name + Toggle */
@Composable
private fun PermissionItemRow(
    title: String,
    granted: Boolean,
    icon: ImageVector,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberMonoScheme()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.chip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = scheme.chipContent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.textPrimary,
            )
        }
        AuraSwitch(
            checked = granted,
            onCheckedChange = { onToggle() },
        )
    }
}

/** Floating Meaningful Reason Card: Black BG + Red Icon + Crisp White Text + Muted Subtitle */
@Composable
private fun FloatingReasonCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF141110),
        border = BorderStroke(1.dp, Color(0xFF2C2825)),
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF261818)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color(0xFFD62828), // Crisp Wine/Red
                    modifier = Modifier.size(14.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = (-0.2).sp,
                    ),
                    color = Color(0xFFFFFDF8),
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 8.5.sp,
                        lineHeight = 11.sp,
                    ),
                    color = Color(0xFFB0AAA0),
                    maxLines = 2,
                )
            }
        }
    }
}

// ── Step 0: Landing Step (Full-screen Cinematic Intro -> Meet AURA with Red Rings & Compact Pill Button)
@Composable
private fun LandingStep(onAdvance: () -> Unit) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isMeetAuraScene by remember { mutableStateOf(false) }
    var isRetracting by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        delay(4400) // matches the extended cinematic intro animation timing
        isMeetAuraScene = true
        HapticUtils.performOverlayChimeHaptic(context)
    }

    val buttonBg = if (scheme.isDark) Color.White else Mono.Ink
    val buttonFg = if (scheme.isDark) Mono.Ink else Mono.TextOnInk

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        OnboardingAnimationCard(
            assetFileName = "anim_landing.html",
            modifier = Modifier.fillMaxSize(),
            onWebViewCreated = { webViewRef = it },
        )

        AnimatedVisibility(
            visible = isMeetAuraScene && !isRetracting,
            enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 2 },
            exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { it / 2 },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = buttonBg,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .height(48.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (!isRetracting) {
                                isRetracting = true
                                HapticUtils.performSuccessHaptic(context)
                                webViewRef?.evaluateJavascript("window.retractHero && window.retractHero();", null)
                                scope.launch {
                                    delay(360)
                                    onAdvance()
                                }
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 34.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "Get Started",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp,
                            ),
                            color = buttonFg,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureBadge(text: String) {
    val scheme = rememberMonoScheme()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = scheme.chip,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.textPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ── Step 1: How AURA Works (Clean Mono Settings Screen Language) ────────────
@Composable
private fun HowAuraWorksStep(
    accepted: Boolean,
    onAcceptedChanged: (Boolean) -> Unit,
) {
    val scheme = rememberMonoScheme()
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Header
        Text(
            text = "How AURA Works",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.8).sp,
            ),
            color = scheme.textPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Autonomous execution with on-device safety guard rails.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.textSecondary,
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Grouped Capabilities (Exact Settings Page MonoCardGroup UI)
        MonoSectionLabel("Capabilities", scheme)
        MonoCardGroup(scheme) {
            MonoRow(
                icon = Icons.Filled.Visibility,
                title = "Sees your screen",
                subtitle = "Reads UI elements only while executing your tasks",
            )
            MonoDivider()
            MonoRow(
                icon = Icons.Outlined.Mic,
                title = "Speaks like a person",
                subtitle = "Natural conversation powered by Gemini Live",
            )
            MonoDivider()
            MonoRow(
                icon = Icons.Filled.TouchApp,
                title = "Acts on its own",
                subtitle = "Performs taps, swipes, and typing securely",
            )
            MonoDivider()
            MonoRow(
                icon = Icons.Filled.Shield,
                title = "Stays on your phone",
                subtitle = "Private keys; sensitive banking apps permanently off-limits",
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Grouped Agreement Card
        MonoSectionLabel("Agreement", scheme)
        Surface(
            shape = Mono.ShapeCard,
            color = scheme.card,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAcceptedChanged(!accepted) },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (accepted) scheme.textPrimary else Color.Transparent,
                    border = BorderStroke(1.5.dp, if (accepted) scheme.textPrimary else scheme.outline),
                    modifier = Modifier.size(22.dp),
                ) {
                    AnimatedVisibility(
                        visible = accepted,
                        enter = scaleIn(),
                        exit = scaleOut(),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = scheme.canvas,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "I agree to the Privacy Policy & Terms of Service.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = scheme.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Privacy Policy",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = scheme.textPrimary,
                            modifier = Modifier.clickable { uriHandler.openUri(com.aura.aura_ui.presentation.screens.legal.LegalCopy.PRIVACY_URL) },
                        )
                        Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = scheme.textSecondary)
                        Text(
                            text = "Terms of Service",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = scheme.textPrimary,
                            modifier = Modifier.clickable { uriHandler.openUri(com.aura.aura_ui.presentation.screens.legal.LegalCopy.TERMS_URL) },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        AiDisclaimer()
    }
}

// ── Step 2: User Profile & Google OAuth (Personalization) ────────────────────
@Composable
private fun UserProfileAndOAuthStep(
    name: String,
    onNameChanged: (String) -> Unit,
    signedInEmail: String?,
    signedIn: Boolean,
    signingIn: Boolean,
    error: String?,
    onSignIn: () -> Unit,
) {
    val scheme = rememberMonoScheme()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Personalize your AURA",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp,
            ),
            color = scheme.textPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Sign in with Google to use AURA, and tell it what to call you.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.textSecondary,
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Profile Input Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.card,
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(scheme.chip),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = scheme.chipContent, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(
                            text = "Assistant Identity",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = scheme.textPrimary,
                        )
                        Text(
                            text = "What should AURA call you?",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.textSecondary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChanged,
                    placeholder = { Text("Your name (e.g. Alex)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Google OAuth Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.card,
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xFFE1E4EA)),
                        modifier = Modifier.size(34.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_google_g),
                                contentDescription = "Google",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Google account",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = scheme.textPrimary,
                        )
                        Text(
                            text = if (signedIn) "Connected to AURA" else "Required to continue",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.textSecondary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    // Says what it is FOR. The card this replaced promised "Gmail, Calendar &
                    // Contacts access" behind a button whose onClick was an empty comment — a
                    // capability claim with nothing behind it. AURA reads mail and contacts on
                    // the phone itself, through the accessibility service, and asks for none of
                    // those scopes.
                    text = "Use your Google account to secure your AURA profile. AURA does not get " +
                        "access to your Gmail, Drive or Calendar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.textSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (signedIn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.MarkEmailRead,
                            contentDescription = null,
                            tint = scheme.textPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = signedInEmail ?: "Google account connected",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.textPrimary,
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onSignIn,
                        enabled = !signingIn,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFFDADCE0)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_google_g),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (signingIn) "Signing in…" else "Continue with Google",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }

                // Shown in place rather than as a toast: the fix for two of the four outcomes
                // (no account on the phone, provider not enabled) is not "tap it again", and a
                // message that disappears cannot say so.
                if (error != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Mono.Blood,
                    )
                }
            }
        }
    }
}

// ── Step 3: Restricted Settings (Android 13+) ────────────────────────────────
@Composable
private fun RestrictedSettingsStep() {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "One Android security step",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp,
            ),
            color = scheme.textPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (Build.VERSION.SDK_INT >= 33) {
                "Because AURA isn't installed from the Play Store, Android hides the Accessibility " +
                    "toggle behind an extra confirmation. You only need to do this once."
            } else {
                "Your Android version doesn't have this extra step — you can continue straight to permissions."
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.textSecondary,
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (Build.VERSION.SDK_INT >= 33) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = scheme.card,
                border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    listOf(
                        "Tap \"Open app info\" below",
                        "Scroll down or tap the ⋮ three-dot menu, top-right",
                        "Tap \"Allow restricted settings\"",
                        "Confirm with your fingerprint or PIN",
                    ).forEachIndexed { i, instruction ->
                        Row(
                            modifier = Modifier.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = scheme.chip,
                                modifier = Modifier.size(22.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${i + 1}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = scheme.chipContent,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = instruction,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.textPrimary,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    context.startActivity(intent)
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open App Info")
            }
        }
    }
}

// ── Step 4: Accessibility Service (anim_accessibility.html) ──────────────────
@Composable
private fun AccessibilityPermissionStep(
    refreshTrigger: Int,
    onOpenSystem: (AuraPermission) -> Unit,
    onAutoAdvance: () -> Unit,
) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val perm = remember { PermissionRegistry.all.first { it.id == "accessibility" } }
    val granted = remember(refreshTrigger) { perm.isGranted(context) }

    LaunchedEffect(granted) {
        if (granted) {
            delay(400)
            onAutoAdvance()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. Top Card with Clean Permission Row
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.card.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f)),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PermissionItemRow(
                title = "Accessibility Service",
                granted = granted,
                icon = permissionIcon(perm.id),
                onToggle = { onOpenSystem(perm) },
            )
        }

        // 2. Middle: Animation Preview (fills available space with ZERO overlap)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            OnboardingAnimationCard(
                assetFileName = "anim_accessibility.html",
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3. Bottom: 2 Clean Reason Cards side-by-side (outside the animation)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FloatingReasonCard(
                icon = Icons.Filled.Visibility,
                title = "Reads Screen Context",
                subtitle = "Identifies UI buttons & active forms",
                modifier = Modifier.weight(1f),
            )
            FloatingReasonCard(
                icon = Icons.Filled.TouchApp,
                title = "Automated Clicks",
                subtitle = "Taps & completes tasks for you",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Step 5: Voice & Overlay (anim_microphone.html) ───────────────────────────
@Composable
private fun VoiceAndOverlayPermissionStep(
    refreshTrigger: Int,
    onRequestRuntime: (List<String>) -> Unit,
    onOpenSystem: (AuraPermission) -> Unit,
    onAutoAdvance: () -> Unit,
) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val micPerm = remember { PermissionRegistry.all.first { it.id == "microphone" } }
    val overlayPerm = remember { PermissionRegistry.all.first { it.id == "overlay" } }

    val micGranted = remember(refreshTrigger) { micPerm.isGranted(context) }
    val overlayGranted = remember(refreshTrigger) { overlayPerm.isGranted(context) }

    // Strictly auto-advance only when BOTH are granted
    LaunchedEffect(micGranted, overlayGranted) {
        if (micGranted && overlayGranted) {
            delay(400)
            onAutoAdvance()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. Top Card that Morphs: First Mic -> Then Overlay once granted
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.card.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f)),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState = if (!micGranted) 0 else 1,
                transitionSpec = {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                },
                label = "permMorphStep5",
            ) { stage ->
                if (stage == 0) {
                    PermissionItemRow(
                        title = "1/2: Microphone Access",
                        granted = micGranted,
                        icon = permissionIcon(micPerm.id),
                        onToggle = { if (!micGranted) onRequestRuntime(micPerm.runtimePermissions) },
                    )
                } else {
                    PermissionItemRow(
                        title = "2/2: Display Over Other Apps",
                        granted = overlayGranted,
                        icon = permissionIcon(overlayPerm.id),
                        onToggle = { onOpenSystem(overlayPerm) },
                    )
                }
            }
        }

        // 2. Middle: Animation Preview (fills available space with ZERO overlap)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            OnboardingAnimationCard(
                assetFileName = "anim_microphone.html",
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3. Bottom: 2 Clean Reason Cards side-by-side (outside the animation)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FloatingReasonCard(
                icon = Icons.Filled.Mic,
                title = "Real-Time Voice",
                subtitle = "Natural speech-to-intent reasoning",
                modifier = Modifier.weight(1f),
            )
            FloatingReasonCard(
                icon = Icons.Filled.Layers,
                title = "Always-On Overlay",
                subtitle = "Floats seamlessly over any active app",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Step 6: AURA Virtual Keyboard (anim_keyboard.html) ───────────────────────
@Composable
private fun KeyboardPermissionStep(
    refreshTrigger: Int,
    onOpenSystem: (AuraPermission) -> Unit,
    onAutoAdvance: () -> Unit,
) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val perm = remember { PermissionRegistry.all.first { it.id == "aura_keyboard" } }
    val granted = remember(refreshTrigger) { perm.isGranted(context) }

    LaunchedEffect(granted) {
        if (granted) {
            delay(400)
            onAutoAdvance()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. Top Card with Clean Permission Row
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.card.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f)),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PermissionItemRow(
                title = "AURA Virtual Keyboard",
                granted = granted,
                icon = permissionIcon(perm.id),
                onToggle = { onOpenSystem(perm) },
            )
        }

        // 2. Middle: Animation Preview (fills available space with ZERO overlap)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            OnboardingAnimationCard(
                assetFileName = "anim_keyboard.html",
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3. Bottom: 2 Clean Reason Cards side-by-side (outside the animation)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FloatingReasonCard(
                icon = Icons.Outlined.Keyboard,
                title = "Automated Typing",
                subtitle = "Fills inputs, forms & searches in apps",
                modifier = Modifier.weight(1f),
            )
            FloatingReasonCard(
                icon = Icons.Filled.Bolt,
                title = "Instant Key Dispatch",
                subtitle = "Submits Enter & action keys directly",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Step 7: Notifications & Live Alerts (anim_notifications.html) ────────────
@Composable
private fun NotificationsPermissionStep(
    refreshTrigger: Int,
    onRequestRuntime: (List<String>) -> Unit,
    onOpenSystem: (AuraPermission) -> Unit,
    onAutoAdvance: () -> Unit,
) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val notifPerm = remember { PermissionRegistry.all.first { it.id == "notifications" } }
    val notifAccessPerm = remember { PermissionRegistry.all.first { it.id == "notification_access" } }

    val notifGranted = remember(refreshTrigger) { notifPerm.isGranted(context) }
    val accessGranted = remember(refreshTrigger) { notifAccessPerm.isGranted(context) }

    // Auto-advance only when notifications permissions are granted
    LaunchedEffect(notifGranted, accessGranted) {
        if (notifGranted && accessGranted) {
            delay(400)
            onAutoAdvance()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. Top Card that Morphs: First Push -> Then Live Alerts once granted
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.card.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f)),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState = if (!notifGranted) 0 else 1,
                transitionSpec = {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                },
                label = "permMorphStep7",
            ) { stage ->
                if (stage == 0) {
                    PermissionItemRow(
                        title = "1/2: Push Notifications",
                        granted = notifGranted,
                        icon = permissionIcon(notifPerm.id),
                        onToggle = { if (!notifGranted) onRequestRuntime(notifPerm.runtimePermissions) },
                    )
                } else {
                    PermissionItemRow(
                        title = "2/2: Live Alerts & Listener",
                        granted = accessGranted,
                        icon = permissionIcon(notifAccessPerm.id),
                        onToggle = { onOpenSystem(notifAccessPerm) },
                    )
                }
            }
        }

        // 2. Middle: Animation Preview (fills available space with ZERO overlap)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            OnboardingAnimationCard(
                assetFileName = "anim_notifications.html",
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3. Bottom: 2 Clean Reason Cards side-by-side (outside the animation)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FloatingReasonCard(
                icon = Icons.Outlined.NotificationsActive,
                title = "Live Alerts Dynamic Pill",
                subtitle = "Dynamic Island status on Android",
                modifier = Modifier.weight(1f),
            )
            FloatingReasonCard(
                icon = Icons.Filled.CheckCircle,
                title = "Direct Quick Actions",
                subtitle = "Replies and dismisses alerts on command",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Step 8: Gemini Key ───────────────────────────────────────────────────────
@Composable
private fun GeminiKeyStep(onVerifiedChanged: (Boolean) -> Unit) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val keyStore = remember { ProviderKeyStore(context.applicationContext) }
    val gemini = remember { LlmEndpointCatalog.builtinById(LlmEndpointCatalog.GEMINI_ID)!! }

    var keyText by remember { mutableStateOf(keyStore.getKey(LlmEndpointCatalog.GEMINI_ID).orEmpty()) }
    var revealed by remember { mutableStateOf(false) }
    var brainModel by remember { mutableStateOf(LlmEndpointCatalog.GEMINI_DEFAULT_MODEL_ID) }
    var liveModel by remember { mutableStateOf(CompanionConfig.LIVE_MODEL) }
    var brainModels by remember { mutableStateOf<List<String>>(listOf(LlmEndpointCatalog.GEMINI_DEFAULT_MODEL_ID)) }
    var liveModels by remember { mutableStateOf<List<String>>(listOf(CompanionConfig.LIVE_MODEL)) }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf(keyStore.isConfigured(LlmEndpointCatalog.GEMINI_ID)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Bring your own Gemini key",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp,
            ),
            color = scheme.textPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Required — drives both Agent Brain reasoning and natural Gemini Live voice mode.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.textSecondary,
        )
        Spacer(modifier = Modifier.height(14.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.card,
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri(gemini.keyUrl ?: "https://aistudio.google.com/apikey") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.chip),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, tint = scheme.chipContent, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Get a free key at aistudio.google.com/apikey",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = scheme.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open",
                        tint = scheme.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it; verified = false; onVerifiedChanged(false) },
                    label = { Text("Gemini API key") },
                    placeholder = { Text(gemini.keyHint ?: "AIza...") },
                    singleLine = true,
                    isError = error != null,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))

                ModelDropdown(
                    label = "Agent Brain Model",
                    selected = brainModel,
                    options = brainModels,
                    stripPrefix = false,
                    onSelect = { brainModel = it },
                )
                Spacer(modifier = Modifier.height(10.dp))
                ModelDropdown(
                    label = "Gemini Live Voice Model",
                    selected = liveModel,
                    options = liveModels,
                    stripPrefix = true,
                    onSelect = { liveModel = it },
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    enabled = !verifying && keyText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            verifying = true
                            error = null
                            val trimmed = keyText.trim()
                            val brainResult = ModelCatalog.fetch(gemini, trimmed)
                            val liveResult = LiveModelResolver.listLiveModels(trimmed)
                            val brainOk = brainResult.isSuccess && brainResult.getOrNull()?.isNotEmpty() == true
                            if (!brainOk) {
                                error = brainResult.exceptionOrNull()?.message ?: "Key rejected — check it and try again."
                                verified = false
                                onVerifiedChanged(false)
                            } else {
                                brainModels = brainResult.getOrNull().orEmpty().map { it.id }
                                    .let { if (it.contains(brainModel)) it else listOf(brainModel) + it }
                                liveModels = liveResult.ifEmpty { listOf(liveModel) }
                                    .let { if (it.contains(liveModel)) it else listOf(liveModel) + it }

                                keyStore.setKey(LlmEndpointCatalog.GEMINI_ID, trimmed)
                                keyStore.setSelectedEndpointId(LlmEndpointCatalog.GEMINI_ID)
                                keyStore.setSelectedModel(LlmEndpointCatalog.GEMINI_ID, brainModel)
                                val brainInfo = brainResult.getOrNull()?.firstOrNull { it.id == brainModel }
                                keyStore.setSelectedModelContextWindow(
                                    LlmEndpointCatalog.GEMINI_ID,
                                    brainInfo?.contextWindow,
                                )
                                keyStore.setSelectedModelVisionCapable(
                                    LlmEndpointCatalog.GEMINI_ID,
                                    brainInfo?.visionCapable,
                                )
                                val companion = CompanionConfig(context)
                                companion.liveModel = liveModel
                                companion.enabled = true
                                companion.useGeminiLive = true

                                verified = true
                                onVerifiedChanged(true)
                            }
                            verifying = false
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (verifying) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Mono.Ink)
                        Spacer(Modifier.width(8.dp))
                        Text("Verifying Key...")
                    } else if (verified) {
                        Icon(Icons.Filled.CheckCircle, null, tint = scheme.textPrimary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Key Verified")
                    } else {
                        Text("Verify API Key")
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Mono.Blood)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    label: String,
    selected: String,
    options: List<String>,
    stripPrefix: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun display(id: String) = if (stripPrefix) id.removePrefix("models/") else id

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            shape = RoundedCornerShape(12.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { id ->
                DropdownMenuItem(
                    text = {
                        Text(
                            display(id),
                            fontWeight = if (id == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { onSelect(id); expanded = false },
                )
            }
        }
    }
}

// ── Step 9: Restricted Apps ──────────────────────────────────────────────────
@Composable
private fun RestrictedAppsStep() {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val store = remember { AppRestrictedApps.get(context) }
    val entries by store.entries.collectAsState()

    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var hasScanned by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (entries.isEmpty() && !hasScanned) {
            scanning = true
            scanError = null
            try {
                val apps = AppInventoryScanner(context).scanInstalledApps().filter { it.isLaunchable() }
                val suggestions = RestrictedAppClassifier(context).classify(apps)
                store.mergeSuggestions(suggestions)
                hasScanned = true
            } catch (t: Throwable) {
                scanError = t.message ?: "Scan failed"
            } finally {
                scanning = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.chip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = scheme.chipContent, modifier = Modifier.size(20.dp))
            }
            Text(
                text = "Banking & Sensitive Guard",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp,
                ),
                color = scheme.textPrimary,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "AURA scans installed apps to automatically decline actions in financial and authenticator apps.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.textSecondary,
        )
        Spacer(modifier = Modifier.height(18.dp))

        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = scheme.textSecondary)
                Spacer(Modifier.width(12.dp))
                Text("Scanning apps...", style = MaterialTheme.typography.bodyMedium, color = scheme.textSecondary)
            }
        } else if (scanError != null) {
            Text("Couldn't scan: $scanError", style = MaterialTheme.typography.bodySmall, color = Mono.Blood)
        } else {
            when {
                entries.isEmpty() -> Text(
                    "No sensitive apps found on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.textSecondary,
                )
                else -> MonoCardGroup(scheme) {
                    val sorted = entries.sortedBy { it.appName }
                    sorted.forEachIndexed { i, entry ->
                        if (i > 0) HorizontalDivider(color = scheme.divider, thickness = 1.dp)
                        RestrictedAppOnboardingRow(
                            entry = entry,
                            scheme = scheme,
                            onTierChange = { tier -> store.updateTier(entry.packageName, tier) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestrictedAppOnboardingRow(
    entry: RestrictedAppEntry,
    scheme: com.aura.aura_ui.ui.theme.MonoScheme,
    onTierChange: (RestrictedTier) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = entry.appName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = entry.tier == RestrictedTier.DECLINE_ONLY,
                onClick = { onTierChange(RestrictedTier.DECLINE_ONLY) },
                label = { Text("Decline only") },
            )
            FilterChip(
                selected = entry.tier == RestrictedTier.DISABLE_ACCESSIBILITY,
                onClick = { onTierChange(RestrictedTier.DISABLE_ACCESSIBILITY) },
                label = { Text("Disable Accessibility") },
            )
        }
    }
}
