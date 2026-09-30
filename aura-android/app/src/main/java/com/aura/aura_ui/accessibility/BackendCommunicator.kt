package com.aura.aura_ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.aura.aura_ui.utils.AgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BackendCommunicator(
    private val service: AccessibilityService,
    private val uiTreeExtractor: UITreeExtractor,
    private val serviceScope: CoroutineScope,
    initialBackendUrl: String,
) {
    private val backendUrl = AtomicReference(initialBackendUrl.trimEnd('/'))

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // UI tree + screenshot can be large
            .writeTimeout(60, TimeUnit.SECONDS)  // Registration payload includes all installed apps
            .retryOnConnectionFailure(true)
            .build()

    fun updateBackendUrl(url: String) {
        val normalizedUrl = url.trimEnd('/')
        backendUrl.set(normalizedUrl)
        AgentLogger.Auto.i("Backend URL updated", mapOf("url" to normalizedUrl))
    }

    fun getCurrentBackendUrl(): String = backendUrl.get()

    fun registerDevice(
        screenWidth: Int,
        screenHeight: Int,
        densityDpi: Int,
        onComplete: (Boolean) -> Unit,
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val currentUrl = backendUrl.get()
                AgentLogger.Auto.i("🔄 Starting device registration with backend: $currentUrl")

                AgentLogger.Auto.i("📱 Scanning installed apps...")
                val scanner = com.aura.aura_ui.utils.AppInventoryScanner(service)
                val installedApps = scanner.scanInstalledApps()

                val deviceInfo =
                    JSONObject().apply {
                        put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("android_version", Build.VERSION.RELEASE)
                        put("screen_width", screenWidth)
                        put("screen_height", screenHeight)
                        put("density_dpi", densityDpi)
                        put("app_version", "1.0.0")
                        put(
                            "capabilities",
                            JSONArray().apply {
                                put("screenshot_capture")
                                put("ui_hierarchy")
                                put("gesture_execution")
                                put("app_automation")
                            },
                        )
                        put(
                            "installed_apps",
                            JSONArray().apply {
                                installedApps.forEach { app ->
                                    put(
                                        JSONObject().apply {
                                            put("package_name", app.packageName)
                                            put("app_name", app.appName)
                                            put("is_system_app", app.isSystemApp)
                                            put("version_name", app.versionName)
                                            put(
                                                "deep_links",
                                                JSONArray().apply {
                                                    app.deepLinks.forEach { put(it) }
                                                },
                                            )
                                            put(
                                                "intent_filters",
                                                JSONArray().apply {
                                                    app.intentFilters.forEach { filter ->
                                                        put(
                                                            JSONObject().apply {
                                                                put("action", filter.action)
                                                                put(
                                                                    "categories",
                                                                    JSONArray().apply {
                                                                        filter.categories.forEach { put(it) }
                                                                    },
                                                                )
                                                                filter.dataScheme?.let { put("data_scheme", it) }
                                                                filter.dataHost?.let { put("data_host", it) }
                                                            },
                                                        )
                                                    }
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    }

                AgentLogger.Auto.i("📦 Including ${installedApps.size} apps in registration")

                val requestBody = deviceInfo.toString().toRequestBody("application/json".toMediaType())
                val request =
                    Request.Builder()
                        .url("$currentUrl/device/register")
                        .post(requestBody)
                        .build()

                AgentLogger.Auto.i("📤 Sending registration request to: $currentUrl/device/register")
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                AgentLogger.Auto.i("📥 Registration response: code=${response.code}")

                if (response.isSuccessful && responseBody != null) {
                    val responseJson = JSONObject(responseBody)
                    val status = responseJson.optString("status")

                    if (status == "registered") {
                        AgentLogger.Auto.i("Device registered successfully with AURA backend")
                        onComplete(true)
                    } else {
                        AgentLogger.Auto.w("Device registration returned unexpected status", mapOf("status" to status))
                        onComplete(false)
                    }
                } else {
                    AgentLogger.Auto.e(
                        "Device registration failed",
                        null,
                        mapOf("responseCode" to response.code, "responseBody" to (responseBody ?: "null")),
                    )
                    onComplete(false)
                }
            } catch (e: Exception) {
                AgentLogger.Auto.e("Device registration failed with exception", e)
                onComplete(false)
            }
        }
    }

    fun sendUIDataWithRequirement(
        screenshotData: ScreenshotData,
        requirement: UIDataRequirement,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                AgentLogger.Auto.i(
                    "Sending UI data based on requirement",
                    mapOf(
                        "needs_ui_tree" to requirement.needsUITree,
                        "needs_screenshot" to requirement.needsScreenshot,
                        "reason" to requirement.requestReason,
                        "elements_count" to screenshotData.uiElements.size,
                    ),
                )
                val currentApp = uiTreeExtractor.getCurrentApp()
                val currentUrl = backendUrl.get()

                val uiData =
                    JSONObject().apply {
                        put("screenshot", screenshotData.screenshot)
                        put(
                            "ui_elements",
                            JSONArray().apply {
                                screenshotData.uiElements.forEach { element ->
                                    put(
                                        JSONObject().apply {
                                            put("text", element.text)
                                            put("contentDescription", element.contentDescription)
                                            put(
                                                "bounds",
                                                JSONObject().apply {
                                                    put("left", element.bounds.left)
                                                    put("top", element.bounds.top)
                                                    put("right", element.bounds.right)
                                                    put("bottom", element.bounds.bottom)
                                                    put("centerX", element.bounds.centerX)
                                                    put("centerY", element.bounds.centerY)
                                                    put("width", element.bounds.width)
                                                    put("height", element.bounds.height)
                                                },
                                            )
                                            put("className", element.className)
                                            put("clickable", element.isClickable)
                                            put("scrollable", element.isScrollable)
                                            put("editable", element.isEditable)
                                            put("enabled", element.isEnabled)
                                            put("packageName", element.packageName)
                                            put("viewId", element.viewId)
                                        },
                                    )
                                }
                            },
                        )
                        put("screen_width", screenshotData.screenWidth)
                        put("screen_height", screenshotData.screenHeight)
                        put("timestamp", screenshotData.timestamp)
                        put("package_name", currentApp.first)
                        put("activity_name", currentApp.second)
                        put("capture_reason", requirement.requestReason)
                        put("needs_ui_tree", requirement.needsUITree)
                        put("needs_screenshot", requirement.needsScreenshot)
                        requirement.taskId?.let { put("task_id", it) }
                    }

                val requestBody = uiData.toString().toRequestBody("application/json".toMediaType())
                val request =
                    Request.Builder()
                        .url("$currentUrl/device/ui-data")
                        .post(requestBody)
                        .build()

                AgentLogger.Auto.d("Sending UI data to: $currentUrl/device/ui-data")
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                AgentLogger.Auto.d("UI data response: code=${response.code}, body=$responseBody")

                if (response.isSuccessful) {
                    AgentLogger.Auto.i("✅ UI data sent successfully to backend")
                    onComplete?.invoke(true)
                } else {
                    AgentLogger.Auto.e("Failed to send UI data", null, mapOf("responseCode" to response.code))
                    onComplete?.invoke(false)
                }
            } catch (e: Exception) {
                AgentLogger.Auto.e("Error sending UI data to backend", e)
                onComplete?.invoke(false)
            }
        }
    }

    @Deprecated("Use sendUIDataWithRequirement instead")
    fun sendUIData(screenshotData: ScreenshotData) {
        sendUIDataWithRequirement(screenshotData, UIDataRequirement.FULL_UI_DATA, null)
    }

    fun sendUITreeOnly() {
        try {
            val uiElements = uiTreeExtractor.getUIElements()
            if (uiElements.isNotEmpty()) {
                val hasScreenshot = false
                val mode = if (hasScreenshot) "📸 FULL" else "📋 UI-ONLY"

                AgentLogger.Auto.d("📤 Sending UI data | Mode: $mode | Elements: ${uiElements.size}")

                val uiOnlyData =
                    ScreenshotData(
                        screenshot = "",
                        screenWidth = 0,
                        screenHeight = 0,
                        timestamp = System.currentTimeMillis(),
                        uiElements = uiElements,
                    )
                sendUIData(uiOnlyData)
            }
        } catch (e: Exception) {
            AgentLogger.UI.e("Error sending UI tree", e)
        }
    }

    fun sendUIDataIfRequired(
        requirement: UIDataRequirement,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        if (!requirement.requiresData()) {
            AgentLogger.Auto.d("No UI data required for: ${requirement.requestReason}")
            onComplete?.invoke(true)
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val uiElements =
                    if (requirement.needsUITree) {
                        uiTreeExtractor.getUIElements()
                    } else {
                        emptyList()
                    }

                val screenshotData =
                    ScreenshotData(
                        screenshot = "",
                        screenWidth = 0,
                        screenHeight = 0,
                        timestamp = System.currentTimeMillis(),
                        uiElements = uiElements,
                    )

                sendUIDataWithRequirement(screenshotData, requirement, onComplete)
            } catch (e: Exception) {
                AgentLogger.Auto.e("Error sending UI data", e)
                onComplete?.invoke(false)
            }
        }
    }

    fun cleanup() {
        httpClient.connectionPool.evictAll()
    }
}
