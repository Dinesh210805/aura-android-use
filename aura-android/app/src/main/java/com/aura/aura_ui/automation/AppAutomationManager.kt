package com.aura.aura_ui.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.utils.AgentLogger
import kotlinx.coroutines.*
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enhanced app automation manager with launch verification
 */
@Singleton
class AppAutomationManager
    @Inject
    constructor(
        private val context: Context,
    ) {
        companion object {
            private const val LAUNCH_VERIFICATION_TIMEOUT = 5000L // 5 seconds
            private const val VERIFICATION_CHECK_INTERVAL = 500L // 500ms
        }

        /**
         * Data class representing app information
         */
        data class AppInfo(
            val appName: String,
            val packageName: String,
            val versionName: String = "",
            val versionCode: Long = 0L,
            val isSystemApp: Boolean = false,
            val lastUpdateTime: Long = 0L,
        )

        private val packageManager = context.packageManager

        /**
         * Launch app by package name with verification
         */
        suspend fun openApp(packageName: String): AppLaunchResult =
            withContext(Dispatchers.Main) {
                try {
                    AgentLogger.Auto.i("Attempting to launch app", mapOf("packageName" to packageName))

                    // Check if app is installed
                    if (!isAppInstalled(packageName)) {
                        AgentLogger.Auto.w("App not installed", mapOf("packageName" to packageName))
                        return@withContext AppLaunchResult.failure("App not installed: $packageName")
                    }

                    val appName = getAppName(packageName)
                    AgentLogger.Auto.d("App found", mapOf("packageName" to packageName, "appName" to appName))

                    // Get current foreground app for comparison
                    val currentApp = getCurrentForegroundApp()
                    AgentLogger.Auto.d("Current foreground app: $currentApp")

                    // Launch the app
                    val launchSuccess = launchAppInternal(packageName)
                    if (!launchSuccess) {
                        AgentLogger.Auto.e("Failed to launch app via intent", null, mapOf("packageName" to packageName))
                        return@withContext AppLaunchResult.failure("Failed to launch app: $packageName")
                    }

                    AgentLogger.Auto.d("Launch intent sent, verifying...")

                    // Verify app launched successfully
                    val verificationResult = verifyAppLaunched(packageName, currentApp)

                    return@withContext if (verificationResult.success) {
                        AgentLogger.Auto.i(
                            "App launched successfully",
                            mapOf(
                                "packageName" to packageName,
                                "appName" to appName,
                                "verificationTime" to "${verificationResult.verificationTimeMs}ms",
                            ),
                        )
                        AppLaunchResult.success(packageName, appName, verificationResult.verificationTimeMs)
                    } else {
                        AgentLogger.Auto.e("App launch verification failed for $packageName: ${verificationResult.failureReason}")
                        AppLaunchResult.failure("Launch verification failed: ${verificationResult.failureReason}")
                    }
                } catch (e: Exception) {
                    AgentLogger.Auto.e("Exception during app launch", e, mapOf("packageName" to packageName))
                    return@withContext AppLaunchResult.failure("Exception: ${e.message}")
                }
            }

        /**
         * Internal app launch using intent
         */
        private fun launchAppInternal(packageName: String): Boolean {
            return try {
                // Try accessibility service first
                val accessibilityService = AuraAccessibilityService.instance
                if (accessibilityService != null) {
                    accessibilityService.launchApp(packageName)
                    true
                } else {
                    // Fallback to context launch
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(intent)
                        true
                    } else {
                        false
                    }
                }
            } catch (e: Exception) {
                AgentLogger.Auto.e("Failed to launch app internally", e, mapOf("packageName" to packageName))
                false
            }
        }

        /**
         * Verify that the app was successfully launched
         */
        private suspend fun verifyAppLaunched(
            targetPackage: String,
            previousApp: String?,
        ): VerificationResult =
            withContext(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                var attempts = 0
                val maxAttempts = (LAUNCH_VERIFICATION_TIMEOUT / VERIFICATION_CHECK_INTERVAL).toInt()

                repeat(maxAttempts) { attempt ->
                    attempts = attempt + 1

                    val currentApp = getCurrentForegroundApp()
                    val elapsed = System.currentTimeMillis() - startTime

                    AgentLogger.Auto.d(
                        "Verification check: attempt $attempts, currentApp: $currentApp, targetPackage: $targetPackage, elapsed: ${elapsed}ms",
                    )

                    when {
                        currentApp == targetPackage -> {
                            // Success - target app is now in foreground
                            return@withContext VerificationResult.success(elapsed)
                        }

                        currentApp != previousApp && currentApp != targetPackage -> {
                            // Different app launched (not our target)
                            return@withContext VerificationResult.failure(
                                "Different app launched: $currentApp (expected: $targetPackage)",
                                elapsed,
                            )
                        }

                        elapsed >= LAUNCH_VERIFICATION_TIMEOUT -> {
                            // Timeout
                            return@withContext VerificationResult.failure(
                                "Timeout waiting for app to launch",
                                elapsed,
                            )
                        }
                    }

                    delay(VERIFICATION_CHECK_INTERVAL)
                }

                // Should not reach here, but handle as timeout
                return@withContext VerificationResult.failure(
                    "Verification loop completed without success",
                    System.currentTimeMillis() - startTime,
                )
            }

        /**
         * Get current foreground app package name
         */
        private fun getCurrentForegroundApp(): String? {
            return try {
                val accessibilityService = AuraAccessibilityService.instance
                if (accessibilityService != null) {
                    val rootNode = accessibilityService.rootInActiveWindow
                    rootNode?.packageName?.toString()
                } else {
                    null
                }
            } catch (e: Exception) {
                AgentLogger.Auto.e("Failed to get current foreground app", e)
                null
            }
        }

        /**
         * Check if app is installed
         */
        private fun isAppInstalled(packageName: String): Boolean {
            return try {
                packageManager.getApplicationInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

        /**
         * Get human-readable app name
         */
        private fun getAppName(packageName: String): String {
            return try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }
        }

        /**
         * Get list of launchable apps
         */
        fun getLaunchableApps(): List<AppInfo> {
            return try {
                val launchableApps = mutableListOf<AppInfo>()
                val intent =
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }

                val activities = packageManager.queryIntentActivities(intent, 0)
                activities.forEach { resolveInfo ->
                    val packageName = resolveInfo.activityInfo.packageName
                    val appName = resolveInfo.loadLabel(packageManager).toString()

                    try {
                        val packageInfo = packageManager.getPackageInfo(packageName, 0)
                        launchableApps.add(
                            AppInfo(
                                packageName = packageName,
                                appName = appName,
                                versionName = packageInfo.versionName ?: "",
                                versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                                isSystemApp = (resolveInfo.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                                lastUpdateTime = packageInfo.lastUpdateTime,
                            ),
                        )
                    } catch (e: Exception) {
                        AgentLogger.Auto.e("Failed to get package info", e, mapOf("packageName" to packageName))
                    }
                }

                AgentLogger.Auto.d("Found ${launchableApps.size} launchable apps")
                launchableApps.sortedBy { it.appName }
            } catch (e: Exception) {
                AgentLogger.Auto.e("Failed to get launchable apps", e)
                emptyList<AppInfo>()
            }
        }

        /**
         * Find app by name (fuzzy search)
         */
        fun findAppByName(appName: String): List<AppInfo> {
            val launchableApps = getLaunchableApps()
            val searchTerm = appName.lowercase(Locale.ROOT).trim()

            return launchableApps.filter { app ->
                app.appName.lowercase(Locale.ROOT).contains(searchTerm) ||
                    app.packageName.lowercase(Locale.ROOT).contains(searchTerm)
            }.sortedBy { app ->
                // Prioritize exact matches and shorter names
                when {
                    app.appName.lowercase(Locale.ROOT) == searchTerm -> 0
                    app.appName.lowercase(Locale.ROOT).startsWith(searchTerm) -> 1
                    else -> 2
                }
            }
        }
    }

/**
 * Result of app launch operation
 */
sealed class AppLaunchResult {
    data class Success(
        val packageName: String,
        val appName: String,
        val launchTimeMs: Long,
    ) : AppLaunchResult()

    data class Failure(
        val errorMessage: String,
    ) : AppLaunchResult()

    val success: Boolean get() = this is Success
    val failure: Boolean get() = this is Failure

    companion object {
        fun success(
            packageName: String,
            appName: String,
            launchTimeMs: Long,
        ) = Success(packageName, appName, launchTimeMs)

        fun failure(errorMessage: String) = Failure(errorMessage)
    }
}

/**
 * Internal verification result
 */
private data class VerificationResult(
    val success: Boolean,
    val verificationTimeMs: Long,
    val failureReason: String? = null,
) {
    companion object {
        fun success(timeMs: Long) = VerificationResult(true, timeMs)

        fun failure(
            reason: String,
            timeMs: Long,
        ) = VerificationResult(false, timeMs, reason)
    }
}
