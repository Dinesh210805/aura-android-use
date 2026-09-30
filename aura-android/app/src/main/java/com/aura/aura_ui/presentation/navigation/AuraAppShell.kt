package com.aura.aura_ui.presentation.navigation

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.aura_ui.remote.GateState
import com.aura.aura_ui.remote.RemoteGateViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aura.aura_ui.presentation.screens.CompanionScreen
import com.aura.aura_ui.presentation.screens.McpCenterScreen
import com.aura.aura_ui.presentation.screens.RestrictedAppsScreen
import com.aura.aura_ui.presentation.screens.McpLogRetentionScreen
import com.aura.aura_ui.BuildConfig
import com.aura.aura_ui.presentation.screens.eval.EvalSuiteScreen
import com.aura.aura_ui.presentation.screens.McpServersScreen
import com.aura.aura_ui.presentation.screens.McpSessionDetailScreen
import com.aura.aura_ui.presentation.screens.McpSessionLogScreen
import com.aura.aura_ui.presentation.screens.TrustedDevicesScreen
import com.aura.aura_ui.presentation.screens.MemoryScreen
import com.aura.aura_ui.presentation.screens.ProviderSettingsScreen
import com.aura.aura_ui.presentation.screens.SkillsScreen
import com.aura.aura_ui.presentation.screens.TavilyKeyScreen
import com.aura.aura_ui.presentation.screens.VoiceSettingsScreen
import com.aura.aura_ui.presentation.screens.agent.AgentHubScreen
import com.aura.aura_ui.presentation.screens.agent.HooksScreen
import com.aura.aura_ui.presentation.screens.agent.ToolsScreen
import androidx.compose.ui.graphics.Color
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.presentation.screens.home.AuraHomeScreen
import com.aura.aura_ui.presentation.screens.legal.LicensesScreen
import com.aura.aura_ui.presentation.screens.legal.PrivacyPolicyScreen
import com.aura.aura_ui.presentation.screens.legal.TermsScreen
import com.aura.aura_ui.presentation.screens.settings.MonoSettingsScreen
import com.aura.aura_ui.presentation.screens.settings.PermissionsHealthScreen
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme

// ============================================================================
// APP SHELL — the single-activity navigation host that replaces both the old
// auto-overlay entry and MainActivity's hand-rolled settings state machine.
//
// Four top-level tabs (Home · Agent · Activity · Settings); every pre-existing
// screen is REHOMED here, not rewritten. Spec §3 rule: agent-behavior screens
// live under Agent, app-behavior screens under Settings.
// ============================================================================

object AuraRoutes {
    const val HOME = "home"
    const val AGENT = "agent"
    const val ACTIVITY = "activity"
    const val SETTINGS = "settings"

    const val AGENT_BRAIN = "agent/brain"
    const val AGENT_TOOLS = "agent/tools"
    const val AGENT_HOOKS = "agent/hooks"
    const val AGENT_SKILLS = "agent/skills"
    const val AGENT_MEMORY = "agent/memory"
    const val AGENT_MCP_SERVERS = "agent/mcp_servers"
    const val AGENT_COMPANION = "agent/companion"
    const val AGENT_VOICE = "agent/voice"

    const val SETTINGS_TAVILY = "settings/tavily"
    const val SETTINGS_MCP_CENTER = "settings/mcp_center"
    const val SETTINGS_TRUSTED_DEVICES = "settings/trusted_devices"
    const val SETTINGS_RESTRICTED_APPS = "settings/restricted_apps"
    const val SETTINGS_PERMISSIONS = "settings/permissions"
    const val SETTINGS_PRIVACY = "settings/privacy"
    const val SETTINGS_TERMS = "settings/terms"
    const val SETTINGS_LICENSES = "settings/licenses"

    /**
     * Debug-only eval cockpit. Registered behind `BuildConfig.DEBUG` below, so in a release build
     * this string names a destination the graph does not contain.
     */
    const val EVAL_SUITE = "eval"

    const val ACTIVITY_SESSION = "activity/session/{sessionId}"
    const val ACTIVITY_RETENTION = "activity/retention"

    fun sessionDetail(sessionId: String) = "activity/session/$sessionId"
}

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val bottomTabs = listOf(
    BottomTab(AuraRoutes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    BottomTab(AuraRoutes.AGENT, "Agent", Icons.Outlined.SmartToy, Icons.Filled.SmartToy),
    BottomTab(AuraRoutes.SETTINGS_MCP_CENTER, "MCP", Icons.Outlined.Hub, Icons.Filled.Hub),
    BottomTab(AuraRoutes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

/**
 * @param onStartChat open the chat overlay (optionally seeded with an example command).
 * @param onRequestScreenCapture launch the MediaProjection consent (needs the Activity).
 */
@Composable
fun AuraAppShell(
    onStartChat: (seedCommand: String?) -> Unit,
    onRequestScreenCapture: () -> Unit,
    onOpenBrowser: () -> Unit,
    startDestination: String = AuraRoutes.HOME,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val analyticsContext = LocalContext.current
    LaunchedEffect(currentRoute) {
        val route = currentRoute ?: return@LaunchedEffect
        FirebaseAnalytics.getInstance(analyticsContext).logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            Bundle().apply { putString(FirebaseAnalytics.Param.SCREEN_NAME, route) },
        )
        FirebaseCrashlytics.getInstance().log("nav: $route")
    }
    val scheme = rememberMonoScheme()
    val warm = com.aura.aura_ui.ui.theme.rememberWarmScheme()

    val gateViewModel: RemoteGateViewModel = hiltViewModel()
    val gateState by gateViewModel.gateState.collectAsState()

    when (val state = gateState) {
        is GateState.KillSwitchBlocked ->
            BlockScreen(title = "AURA is turned off", message = state.message, updateUrl = null, scheme = scheme)
        is GateState.VersionBlocked ->
            BlockScreen(title = "Update required", message = state.message, updateUrl = state.updateUrl, scheme = scheme)
        else -> {
            // Allowed or UpdateAvailable: render the app; overlay a dismissible
            // update banner above the nav content when an update is available.
            var updateDismissed by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = warm.canvasBottom,
        bottomBar = {
            // The capsule is the app's ONE navigation element: tab icons on
            // top-level screens, ‹ back · title on subpages (Capsule design).
            MonoBottomBar(
                currentRoute = currentRoute,
                scheme = scheme,
                onSelect = { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onBack = { navController.navigateUp() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state is GateState.UpdateAvailable && state.updateUrl.isNotBlank() && !updateDismissed) {
                UpdateBanner(
                    updateUrl = state.updateUrl,
                    scheme = scheme,
                    onDismiss = { updateDismissed = true },
                )
            }
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            enterTransition = { fadeIn(tween(Mono.MotionNormal)) },
            exitTransition = { fadeOut(tween(Mono.MotionFast)) },
            popEnterTransition = { fadeIn(tween(Mono.MotionNormal)) },
            popExitTransition = { fadeOut(tween(Mono.MotionFast)) },
        ) {
            // ── Top level ──────────────────────────────────────────────
            composable(AuraRoutes.HOME) {
                AuraHomeScreen(
                    onStartChat = onStartChat,
                    onOpenActivity = { navController.navigate(AuraRoutes.ACTIVITY) },
                    onOpenBrowser = onOpenBrowser,
                    onFixHealth = { navController.navigate(AuraRoutes.SETTINGS_PERMISSIONS) },
                )
            }
            composable(AuraRoutes.AGENT) {
                AgentHubScreen(
                    onOpenBrain = { navController.navigate(AuraRoutes.AGENT_BRAIN) },
                    onOpenTools = { navController.navigate(AuraRoutes.AGENT_TOOLS) },
                    onOpenHooks = { navController.navigate(AuraRoutes.AGENT_HOOKS) },
                    onOpenSkills = { navController.navigate(AuraRoutes.AGENT_SKILLS) },
                    onOpenMemory = { navController.navigate(AuraRoutes.AGENT_MEMORY) },
                    onOpenMcpServers = { navController.navigate(AuraRoutes.AGENT_MCP_SERVERS) },
                    onOpenVoice = { navController.navigate(AuraRoutes.AGENT_COMPANION) },
                )
            }
            composable(AuraRoutes.ACTIVITY) {
                McpSessionLogScreen(
                    onNavigateBack = { navController.navigateUp() },
                    onOpenSession = { id -> navController.navigate(AuraRoutes.sessionDetail(id)) },
                    onOpenRetention = { navController.navigate(AuraRoutes.ACTIVITY_RETENTION) },
                )
            }
            composable(AuraRoutes.SETTINGS) {
                MonoSettingsScreen(
                    onOpenPermissions = { navController.navigate(AuraRoutes.SETTINGS_PERMISSIONS) },
                    onOpenTavilyKey = { navController.navigate(AuraRoutes.SETTINGS_TAVILY) },
                    onRequestScreenCapture = onRequestScreenCapture,
                    onOpenPrivacy = { navController.navigate(AuraRoutes.SETTINGS_PRIVACY) },
                    onOpenTerms = { navController.navigate(AuraRoutes.SETTINGS_TERMS) },
                    onOpenLicenses = { navController.navigate(AuraRoutes.SETTINGS_LICENSES) },
                    onOpenRestrictedApps = { navController.navigate(AuraRoutes.SETTINGS_RESTRICTED_APPS) },
                    onOpenEvalSuite = if (BuildConfig.DEBUG) {
                        { navController.navigate(AuraRoutes.EVAL_SUITE) }
                    } else {
                        null
                    },
                )
            }
            // The eval cockpit spends real provider quota driving the agent through the whole task
            // suite, so it is not part of a release graph at all — not merely hidden in one.
            if (BuildConfig.DEBUG) {
                composable(AuraRoutes.EVAL_SUITE) {
                    EvalSuiteScreen(
                        onOpenTrace = { id -> navController.navigate(AuraRoutes.sessionDetail(id)) },
                    )
                }
            }

            // ── Agent sub-screens ─────────────────────────────────────
            composable(AuraRoutes.AGENT_BRAIN) {
                ProviderSettingsScreen(
                    onNavigateBack = { navController.navigateUp() },
                    onOpenTrustedDevices = { navController.navigate(AuraRoutes.SETTINGS_TRUSTED_DEVICES) },
                    onOpenRestrictedApps = { navController.navigate(AuraRoutes.SETTINGS_RESTRICTED_APPS) },
                    onOpenActivityLog = { navController.navigate(AuraRoutes.ACTIVITY) },
                    onOpenRetention = { navController.navigate(AuraRoutes.ACTIVITY_RETENTION) },
                )
            }
            composable(AuraRoutes.AGENT_TOOLS) {
                ToolsScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.AGENT_HOOKS) {
                HooksScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.AGENT_SKILLS) {
                SkillsScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.AGENT_MEMORY) {
                MemoryScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.AGENT_MCP_SERVERS) {
                McpServersScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.AGENT_COMPANION) {
                CompanionScreen(
                    onNavigateBack = { navController.navigateUp() },
                    onOpenVoiceSettings = { navController.navigate(AuraRoutes.AGENT_VOICE) },
                )
            }
            composable(AuraRoutes.AGENT_VOICE) {
                VoiceSettingsScreen(onNavigateBack = { navController.navigateUp() })
            }

            // ── Settings sub-screens ──────────────────────────────────
            composable(AuraRoutes.SETTINGS_PERMISSIONS) {
                PermissionsHealthScreen(
                    onNavigateBack = { navController.navigateUp() },
                    onRequestScreenCapture = onRequestScreenCapture,
                )
            }
            composable(AuraRoutes.SETTINGS_TAVILY) {
                TavilyKeyScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.SETTINGS_PRIVACY) {
                PrivacyPolicyScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.SETTINGS_TERMS) {
                TermsScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.SETTINGS_LICENSES) {
                LicensesScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.SETTINGS_MCP_CENTER) {
                McpCenterScreen(
                    onNavigateBack = { navController.navigateUp() },
                    onOpenActivityLog = { navController.navigate(AuraRoutes.ACTIVITY) },
                    onOpenRetention = { navController.navigate(AuraRoutes.ACTIVITY_RETENTION) },
                    onOpenTrustedDevices = { navController.navigate(AuraRoutes.SETTINGS_TRUSTED_DEVICES) },
                    onOpenRestrictedApps = { navController.navigate(AuraRoutes.SETTINGS_RESTRICTED_APPS) },
                )
            }
            composable(AuraRoutes.SETTINGS_TRUSTED_DEVICES) {
                TrustedDevicesScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.SETTINGS_RESTRICTED_APPS) {
                RestrictedAppsScreen(onNavigateBack = { navController.navigateUp() })
            }

            // ── Activity sub-screens ──────────────────────────────────
            composable(
                route = AuraRoutes.ACTIVITY_SESSION,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { entry ->
                McpSessionDetailScreen(
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    onNavigateBack = { navController.navigateUp() },
                )
            }
            composable(AuraRoutes.ACTIVITY_RETENTION) {
                McpLogRetentionScreen(onNavigateBack = { navController.navigateUp() })
            }
        }
        }
    }
        }
    }
}

/**
 * Human-readable titles the contextual capsule shows on subpages.
 * Fallback: the last route segment, capitalised.
 */
private val routeTitles = mapOf(
    AuraRoutes.AGENT_BRAIN to "Brain",
    AuraRoutes.AGENT_TOOLS to "Tools",
    AuraRoutes.AGENT_HOOKS to "Hooks",
    AuraRoutes.AGENT_SKILLS to "Skills",
    AuraRoutes.AGENT_MEMORY to "Memory",
    AuraRoutes.AGENT_MCP_SERVERS to "MCP Servers",
    AuraRoutes.AGENT_COMPANION to "Companion",
    AuraRoutes.AGENT_VOICE to "Voice",
    AuraRoutes.ACTIVITY to "Activity",
    AuraRoutes.ACTIVITY_SESSION to "Session",
    AuraRoutes.ACTIVITY_RETENTION to "Retention",
    AuraRoutes.SETTINGS_TAVILY to "Search key",
    AuraRoutes.SETTINGS_PERMISSIONS to "Permissions",
    AuraRoutes.SETTINGS_PRIVACY to "Privacy",
    AuraRoutes.SETTINGS_TERMS to "Terms",
    AuraRoutes.SETTINGS_LICENSES to "Licences",
)

private fun titleFor(route: String?): String =
    routeTitles[route]
        ?: route?.substringAfterLast('/')?.substringBefore('{')
            ?.replace('_', ' ')
            ?.replaceFirstChar { it.uppercase() }
            .orEmpty()

/**
 * The Capsule nav (2026-07-10 redesign): one BLACK floating capsule strip in
 * both themes — AURA's control element. On top-level tabs it holds the four
 * icons (active = highlight round + vermilion dot); on subpages it MORPHS into
 * ‹ back · title, so the capsule is the only navigation chrome in the app.
 */
@Composable
private fun MonoBottomBar(
    currentRoute: String?,
    scheme: MonoScheme,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    val capsule = com.aura.aura_ui.ui.theme.Capsule.scheme()
    val isTabLevel = currentRoute == null || bottomTabs.any { it.route == currentRoute }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 14.dp)
            .navigationBarsPadding(),
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = capsule.capsule,
            shadowElevation = 14.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = isTabLevel,
                transitionSpec = { fadeIn(tween(Mono.MotionNormal)) togetherWith fadeOut(tween(Mono.MotionFast)) },
                label = "capsuleMode",
            ) { tabLevel ->
                if (tabLevel) {
                    CapsuleTabs(currentRoute = currentRoute, capsule = capsule, onSelect = onSelect)
                } else {
                    CapsuleContext(title = titleFor(currentRoute), capsule = capsule, onBack = onBack)
                }
            }
        }
    }
}

/** Subpage mode: ‹ back (left) · page title (center) · symmetry spacer (right). */
@Composable
private fun CapsuleContext(
    title: String,
    capsule: com.aura.aura_ui.ui.theme.CapsuleScheme,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 7.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(Mono.ShapePill)
                .background(Color.White.copy(alpha = 0.10f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = capsule.onCapsule,
                modifier = Modifier.size(19.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = capsule.onCapsule,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(44.dp))
    }
}

/** Top-level mode: the four tab icons with the sliding highlight. */
@Composable
private fun CapsuleTabs(
    currentRoute: String?,
    capsule: com.aura.aura_ui.ui.theme.CapsuleScheme,
    onSelect: (String) -> Unit,
) {
    val selectedIndex = bottomTabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = 7.dp, vertical = 7.dp),
            ) {
                val itemWidth = maxWidth / bottomTabs.size
                val highlightX by animateDpAsState(
                    targetValue = itemWidth * selectedIndex + (itemWidth - 44.dp) / 2,
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f),
                    label = "navHighlight",
                )

                // Soft highlight round sliding under the active icon.
                Box(
                    modifier = Modifier
                        .offset(x = highlightX)
                        .size(44.dp)
                        .clip(Mono.ShapePill)
                        .background(Color.White.copy(alpha = 0.10f)),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    bottomTabs.forEach { tab ->
                        val selected = tab.route == currentRoute
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(Mono.ShapePill)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onSelect(tab.route) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.icon,
                                contentDescription = tab.label,
                                tint = if (selected) capsule.onCapsule else capsule.onCapsuleDim,
                                modifier = Modifier.size(21.dp),
                            )
                            Spacer(Modifier.height(3.dp))
                            // The vermilion presence dot — the capsule's only color.
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(Mono.ShapePill)
                                    .background(if (selected) capsule.accent else Color.Transparent),
                            )
                        }
                    }
                }
    }
}
