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
import com.aura.aura_ui.presentation.screens.McpSessionDetailScreen
import com.aura.aura_ui.presentation.screens.McpSessionLogScreen
import com.aura.aura_ui.presentation.screens.TrustedDevicesScreen
import com.aura.aura_ui.presentation.screens.ProviderSettingsScreen
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
import com.aura.aura_ui.presentation.screens.settings.ApiKeysScreen
import com.aura.aura_ui.presentation.screens.settings.AppearanceScreen
import com.aura.aura_ui.presentation.screens.settings.PermissionsHealthScreen
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.presentation.components.LocalBack
import com.aura.aura_ui.agentcore.AgentCore
import com.aura.aura_ui.agentcore.AllChatsPage
import com.aura.aura_ui.agentcore.ConnectorsPage
import com.aura.aura_ui.agentcore.MemoryPage
import com.aura.aura_ui.agentcore.RunLogsPage
import com.aura.aura_ui.agentcore.RunDetailPage
import com.aura.aura_ui.agentcore.LogKeepPage
import com.aura.aura_ui.agentcore.RunDetailPage
import com.aura.aura_ui.agentcore.LogKeepPage
import com.aura.aura_ui.agentcore.SchedulesPage
import com.aura.aura_ui.agentcore.SkillsPage
import androidx.compose.runtime.CompositionLocalProvider
import com.aura.aura_ui.ui.theme.MonoScheme

/**
 * The app's routes. Home is the chat; everything else is opened from the chat's drawer or from
 * another page, and goes back with the page's own back button ([LocalBack]).
 *
 * - Contract: agent-behaviour screens live under `agent/`, app-behaviour screens under
 *   `settings/`. The chat agent's own pages (chats, run logs, schedules) sit at the top level.
 */

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
    const val AGENT_CONNECTORS = "agent/connectors"

    /** The chat agent's pages, opened from the chat's drawer. */
    const val CHATS = "chats"
    const val RUN_LOGS = "run_logs"
    const val RUN_LOG = "run_log/{id}"
    const val RUN_LOGS_KEEP = "run_logs_keep"

    fun runLog(id: String) = "run_log/$id"
    const val SCHEDULES = "schedules"
    const val AGENT_COMPANION = "agent/companion"
    const val AGENT_VOICE = "agent/voice"

    /** The status dashboard that used to be Home. Reached from the chat drawer. */
    const val HOME_DASHBOARD = "home/dashboard"

    const val SETTINGS_APPEARANCE = "settings/appearance"

    /** The one place API keys are entered (besides onboarding). See [apiKeys]. */
    const val SETTINGS_API_KEYS = "settings/api_keys?provider={provider}"

    /** The API keys page, opening the key editor for [provider] (an endpoint id, or "tavily"). */
    fun apiKeys(provider: String? = null) = if (provider == null) "settings/api_keys" else "settings/api_keys?provider=$provider"
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

    val gateViewModel: RemoteGateViewModel = hiltViewModel()
    val gateState by gateViewModel.gateState.collectAsState()

    when (val state = gateState) {
        is GateState.KillSwitchBlocked ->
            BlockScreen(title = "AURA is turned off", message = state.message, updateUrl = null)
        is GateState.VersionBlocked ->
            BlockScreen(title = "Update required", message = state.message, updateUrl = state.updateUrl)
        else -> {
            // Allowed or UpdateAvailable: render the app; overlay a dismissible
            // update banner above the nav content when an update is available.
            var updateDismissed by remember { mutableStateOf(false) }
    val aura = LocalAura.current
    CompositionLocalProvider(LocalBack provides { navController.navigateUp() }) {
    Box(Modifier.fillMaxSize().background(aura.canvas)) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(Mono.MotionNormal)) },
            exitTransition = { fadeOut(tween(Mono.MotionFast)) },
            popEnterTransition = { fadeIn(tween(Mono.MotionNormal)) },
            popExitTransition = { fadeOut(tween(Mono.MotionFast)) },
        ) {
            // ── Top level ──────────────────────────────────────────────
            composable(AuraRoutes.HOME) {
                com.aura.aura_ui.agentcore.AgentChatScreen(
                    onNavigate = { route -> navController.navigate(route) },
                    onVoice = { onStartChat(null) },
                )
            }
            composable(AuraRoutes.HOME_DASHBOARD) {
                AuraHomeScreen(
                    onStartChat = onStartChat,
                    onOpenActivity = { navController.navigate(AuraRoutes.ACTIVITY) },
                    onOpenBrowser = onOpenBrowser,
                    onFixHealth = { navController.navigate(AuraRoutes.SETTINGS_PERMISSIONS) },
                )
            }
            composable(AuraRoutes.AGENT) {
                AgentHubScreen(onOpen = { route -> navController.navigate(route) })
            }
            composable(AuraRoutes.CHATS) {
                AllChatsPage(onOpen = { id ->
                    AgentCore.openThread.value = id
                    navController.popBackStack(AuraRoutes.HOME, inclusive = false)
                })
            }
            composable(AuraRoutes.RUN_LOGS) {
                RunLogsPage(onOpen = { navController.navigate(AuraRoutes.runLog(it)) }, onKeep = { navController.navigate(AuraRoutes.RUN_LOGS_KEEP) })
            }
            composable(AuraRoutes.RUN_LOG) { entry ->
                RunDetailPage(entry.arguments?.getString("id").orEmpty(), onDeleted = { navController.navigateUp() })
            }
            composable(AuraRoutes.RUN_LOGS_KEEP) { LogKeepPage() }
            composable(AuraRoutes.SCHEDULES) { SchedulesPage() }
            composable(AuraRoutes.AGENT_MEMORY) { MemoryPage() }
            composable(AuraRoutes.AGENT_SKILLS) { SkillsPage() }
            composable(AuraRoutes.AGENT_CONNECTORS) { ConnectorsPage() }
            composable(AuraRoutes.ACTIVITY) {
                McpSessionLogScreen(
                    onNavigateBack = { navController.navigateUp() },
                    onOpenSession = { id -> navController.navigate(AuraRoutes.sessionDetail(id)) },
                    onOpenRetention = { navController.navigate(AuraRoutes.ACTIVITY_RETENTION) },
                )
            }
            composable(AuraRoutes.SETTINGS) {
                MonoSettingsScreen(onOpen = { route -> navController.navigate(route) }, debug = BuildConfig.DEBUG)
            }
            composable(AuraRoutes.SETTINGS_APPEARANCE) { AppearanceScreen() }
            composable(
                route = AuraRoutes.SETTINGS_API_KEYS,
                arguments = listOf(navArgument("provider") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                ApiKeysScreen(preselect = entry.arguments?.getString("provider"))
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
                ProviderSettingsScreen(onOpenApiKeys = { id -> navController.navigate(AuraRoutes.apiKeys(id)) })
            }
            composable(AuraRoutes.AGENT_TOOLS) {
                ToolsScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.AGENT_HOOKS) {
                HooksScreen(onNavigateBack = { navController.navigateUp() })
            }
            composable(AuraRoutes.AGENT_COMPANION) {
                CompanionScreen(
                    onOpenVoiceSettings = { navController.navigate(AuraRoutes.AGENT_VOICE) },
                    onOpenApiKeys = { navController.navigate(AuraRoutes.apiKeys(com.aura.aura_ui.agent.llm.LlmEndpointCatalog.GEMINI_ID)) },
                )
            }
            composable(AuraRoutes.AGENT_VOICE) {
                VoiceSettingsScreen()
            }

            // ── Settings sub-screens ──────────────────────────────────
            composable(AuraRoutes.SETTINGS_PERMISSIONS) {
                PermissionsHealthScreen(onNavigateBack = { navController.navigateUp() })
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
            if (state is GateState.UpdateAvailable && state.updateUrl.isNotBlank() && !updateDismissed) {
                UpdateDialog(state, onDismiss = { updateDismissed = true })
            }
    }
    }
        }
    }
}
