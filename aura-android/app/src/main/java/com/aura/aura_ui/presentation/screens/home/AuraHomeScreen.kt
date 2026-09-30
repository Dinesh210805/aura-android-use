package com.aura.aura_ui.presentation.screens.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.aura.aura_ui.agent.conversation.PersonaOwner
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aura.aura_ui.presentation.components.AiDisclaimer
import com.aura.aura_ui.presentation.components.AuraLogoBubble
import com.aura.aura_ui.presentation.components.AuraWhiteLogo
import com.aura.aura_ui.presentation.components.BloodDot
import com.aura.aura_ui.ui.theme.Capsule
import com.aura.aura_ui.ui.theme.CapsuleScheme
import com.aura.aura_ui.ui.theme.heroBrush
import java.util.Calendar

// ============================================================================
// HOME — Capsule design (2026-07-10). Greige canvas; ONE grainy-peach hero
// card carrying the greeting and display headline; below it the curved app
// marquee (AppPromptMarquee, 2026-08-16, which replaced the 2×2 capability-card
// sheet); the charcoal talk pill; health banner as a white card with the
// vermilion accent. The black capsule nav (AuraAppShell) floats below.
// The overlay opens only from here.
// ============================================================================

/** Core-permission health used by the conditional banner. */
data class HomeHealth(
    val accessibilityEnabled: Boolean,
    val overlayGranted: Boolean,
    val micGranted: Boolean,
) {
    val broken: Boolean get() = !accessibilityEnabled || !overlayGranted || !micGranted

    val firstIssue: String
        get() = when {
            !accessibilityEnabled -> "Accessibility service is off"
            !overlayGranted -> "Overlay permission missing"
            !micGranted -> "Microphone permission missing"
            else -> ""
        }
}

/** Re-checks the core permissions every time the screen resumes. */
@Composable
fun rememberHomeHealth(): HomeHealth {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var health by remember { mutableStateOf(checkHealth(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) health = checkHealth(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return health
}

private fun checkHealth(context: Context): HomeHealth {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val accessibilityOn = am.getEnabledAccessibilityServiceList(
        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
    ).any {
        it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name.endsWith("AuraAccessibilityService")
    }
    return HomeHealth(
        accessibilityEnabled = accessibilityOn,
        overlayGranted = Settings.canDrawOverlays(context),
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
    )
}

@Composable
fun AuraHomeScreen(
    onStartChat: (seedCommand: String?) -> Unit,
    onOpenActivity: () -> Unit,
    onOpenBrowser: () -> Unit,
    onFixHealth: () -> Unit,
    modifier: Modifier = Modifier,
    runActive: Boolean = false,
) {
    val cs = Capsule.scheme()
    val health = rememberHomeHealth()
    // The user's own name if they set one (Settings → Voice & Companion), else
    // no name at all. This was hardcoded to the developer's name, so every
    // install greeted its user as "Dinesh".
    val context = LocalContext.current
    val name = remember { PersonaOwner.get(context) }
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Late night"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(cs.canvas),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(14.dp))

            // ── Top row: brand (left) · Activity entry (right).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AuraLogoBubble(size = 42.dp, activeRun = runActive)
                    Text(
                        "aura",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                        ),
                        color = cs.ink,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AuraBrowserButton(cs = cs, onClick = onOpenBrowser)
                    CircleIconButton(
                        icon = Icons.Outlined.Timeline,
                        contentDescription = "Activity logs and traces",
                        cs = cs,
                        onClick = onOpenActivity,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(16.dp))

                // ── THE HERO — one grainy-peach card carrying the voice moment.
                HeroCard(
                    cs = cs,
                    greeting = if (name.isNullOrBlank()) greeting else "$greeting, $name",
                    runActive = runActive,
                )

                if (health.broken) {
                    Spacer(Modifier.height(12.dp))
                    HealthBanner(cs = cs, issue = health.firstIssue, onFix = onFixHealth)
                }

                // ── Community invite: shown until the user joins or closes it, then never again.
                val communityPrefs = remember { context.getSharedPreferences(COMMUNITY_PREFS, Context.MODE_PRIVATE) }
                var showCommunity by remember { mutableStateOf(!communityPrefs.getBoolean(KEY_COMMUNITY_DONE, false)) }
                if (showCommunity) {
                    val hide = {
                        communityPrefs.edit().putBoolean(KEY_COMMUNITY_DONE, true).apply()
                        showCommunity = false
                    }
                    Spacer(Modifier.height(12.dp))
                    CommunityCard(
                        cs = cs,
                        onJoin = {
                            openUrl(context, DISCORD_INVITE)
                            hide()
                        },
                        onWatch = { openUrl(context, LAUNCH_VIDEO) },
                        onDismiss = hide,
                    )
                }

                Spacer(Modifier.height(18.dp))

                // ── The curved app marquee: the user's own apps arcing across the
                // screen, one example prompt at a time. Seeds a chat, exactly as
                // the capability cards it replaced did.
                Box(modifier = Modifier.fillMaxWidth()) {
                    AppPromptMarquee(cs = cs)
                    Icon(
                        imageVector = Icons.Default.BubbleChart,
                        contentDescription = null,
                        tint = cs.sub.copy(alpha = 0.24f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = 6.dp, y = 30.dp)
                            .rotate(-10f)
                            .size(18.dp),
                    )
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = cs.accent.copy(alpha = 0.32f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-10).dp, y = (-16).dp)
                            .rotate(10f)
                            .size(17.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
                TalkPill(cs = cs, onClick = { onStartChat(null) })

                Spacer(Modifier.height(18.dp))
                AiDisclaimer(smallText = true)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Branded entry point for the visible AURA WebView browser. */
@Composable
private fun AuraBrowserButton(cs: CapsuleScheme, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = Capsule.ShapePill,
        color = cs.capsule,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AuraWhiteLogo(size = 20.dp)
            Text(
                text = "Browser",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = cs.onCapsule,
            )
        }
    }
}

@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    cs: CapsuleScheme,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = cs.card,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = cs.ink, modifier = Modifier.size(19.dp))
        }
    }
}

/**
 * The hero: greeting eyebrow, oversized display prompt, and the charcoal talk
 * pill — all inside one soft peach-gradient card (the app's warmest object).
 */
@Composable
private fun HeroCard(
    cs: CapsuleScheme,
    greeting: String,
    runActive: Boolean,
) {
    Surface(shape = Capsule.ShapeHero, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.background(cs.heroBrush())) {
            HeroDoodles(cs)
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        greeting.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.2.sp),
                        color = cs.sub,
                    )
                    if (runActive) BloodDot(size = 6.dp)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Tell me\nwhat's next.",
                    style = MaterialTheme.typography.displaySmall,
                    color = cs.ink,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "AURA sees your screen and gets it done.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.sub,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Quiet, touchless accents that make the hero feel made rather than filled. */
@Composable
private fun HeroDoodles(cs: CapsuleScheme) {
    Box(modifier = Modifier.fillMaxSize()) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = cs.accent.copy(alpha = 0.38f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-22).dp, y = 38.dp)
                .rotate(9f)
                .alpha(0.72f)
                .size(20.dp),
        )
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null,
            tint = cs.sub.copy(alpha = 0.42f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 24.dp, y = (-25).dp)
                .rotate(-8f)
                .alpha(0.72f)
                .size(22.dp),
        )
    }
}

/** The charcoal talk pill — the dark object inside the light hero. */
@Composable
private fun TalkPill(cs: CapsuleScheme, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = Capsule.ShapePill,
            color = cs.capsule,
            modifier = Modifier.wrapContentWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(26.dp).clip(CircleShape).background(cs.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Mic, contentDescription = null, tint = cs.onAccent, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.size(9.dp))
                Text(
                    "Talk to AURA",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = cs.onCapsule,
                )
            }
        }
    }
}

@Composable
private fun HealthBanner(cs: CapsuleScheme, issue: String, onFix: () -> Unit) {
    Surface(
        onClick = onFix,
        shape = Capsule.ShapeCard,
        color = cs.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.accent.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BloodDot()
            Text(
                issue,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = cs.accent,
                modifier = Modifier.weight(1f),
            )
            Text("Fix", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = cs.accent)
        }
    }
}

private const val COMMUNITY_PREFS = "aura_home"
private const val KEY_COMMUNITY_DONE = "community_invite_done"
private const val DISCORD_INVITE = "https://discord.com/invite/H66ws9zMPF"
private const val LAUNCH_VIDEO = "https://youtu.be/u2PS767kdMI"

/** Opens a link in whatever app handles it (Discord, YouTube, or the browser). */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Invite to the AURA Discord — the one place users can talk back to us. */
@Composable
private fun CommunityCard(
    cs: CapsuleScheme,
    onJoin: () -> Unit,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(shape = Capsule.ShapeCard, color = cs.card, modifier = Modifier.fillMaxWidth()) {
        Box {
            Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)) {
                Text(
                    "Join the AURA Discord",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = cs.ink,
                    modifier = Modifier.padding(end = 28.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Mess around with it, build something with it, tell us what breaks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.sub,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(onClick = onJoin, shape = Capsule.ShapePill, color = cs.capsule) {
                        Text(
                            "Join Discord",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = cs.onCapsule,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        )
                    }
                    Surface(onClick = onWatch, shape = Capsule.ShapePill, color = Color.Transparent) {
                        Text(
                            "Watch the launch video",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = cs.ink,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = cs.sub, modifier = Modifier.size(18.dp))
            }
        }
    }
}
