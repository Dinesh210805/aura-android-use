package com.aura.aura_ui.data

import com.google.gson.annotations.SerializedName

/**
 * Response from backend containing pending commands
 */
data class CommandResponse(
    @SerializedName("status") val status: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("command_count") val commandCount: Int,
    @SerializedName("commands") val commands: List<Command>,
    @SerializedName("timestamp") val timestamp: Long,
)

/**
 * A command to be executed by the device
 */
data class Command(
    @SerializedName("command_id") val commandId: String,
    @SerializedName("command_type") val commandType: String,
    @SerializedName("payload") val payload: Map<String, Any?>? = null,
    @SerializedName("created_at") val createdAt: String,
)

/**
 * Result of command execution to be sent back to backend
 */
data class CommandResult(
    @SerializedName("success") val success: Boolean,
    @SerializedName("error") val error: String? = null,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Installed app information
 */
data class AppInfo(
    @SerializedName("package_name") val packageName: String,
    @SerializedName("app_name") val appName: String,
    @SerializedName("is_system_app") val isSystemApp: Boolean = false,
    @SerializedName("version_name") val versionName: String = "",
    @SerializedName("deep_links") val deepLinks: List<String> = emptyList(),
    @SerializedName("intent_filters") val intentFilters: List<IntentFilterInfo> = emptyList(),
    @SerializedName("deep_link_uri") val deepLinkUri: String? = null, // Optional deep link URI for direct launch
)

/**
 * Intent filter information for deep linking
 */
data class IntentFilterInfo(
    @SerializedName("action") val action: String,
    @SerializedName("categories") val categories: List<String> = emptyList(),
    @SerializedName("data_scheme") val dataScheme: String? = null,
    @SerializedName("data_host") val dataHost: String? = null,
)
