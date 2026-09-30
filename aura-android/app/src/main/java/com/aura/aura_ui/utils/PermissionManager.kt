package com.aura.aura_ui.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Simple permission manager for the app
 */
class PermissionManager(private val logger: Logger) {
    companion object {
        const val REQUEST_OVERLAY_PERMISSION = 1001
        const val REQUEST_MICROPHONE_PERMISSION = 1002
        const val REQUEST_ALL_PERMISSIONS = 1004

        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.INTERNET)
            add(Manifest.permission.ACCESS_NETWORK_STATE)
            // POST_NOTIFICATIONS required for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun hasAllRequiredPermissions(context: Context): Boolean {
        val hasOverlayPermission = hasOverlayPermission(context)
        val hasRequiredPermissions =
            REQUIRED_PERMISSIONS.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }

        logger.d("PermissionManager", "Overlay: $hasOverlayPermission, Required: $hasRequiredPermissions")
        return hasOverlayPermission && hasRequiredPermissions
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun getMissingPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()

        if (!hasOverlayPermission(context)) {
            missing.add("System Overlay")
        }

        REQUIRED_PERMISSIONS.forEach { permission ->
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(getPermissionDisplayName(permission))
            }
        }

        return missing
    }

    fun requestAllPermissions(activity: Activity) {
        val permissionsToRequest = mutableListOf<String>()

        REQUIRED_PERMISSIONS.forEach { permission ->
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissionsToRequest.toTypedArray(),
                REQUEST_ALL_PERMISSIONS,
            )
            logger.d("PermissionManager", "Requesting permissions: ${permissionsToRequest.joinToString()}")
        }
    }

    private fun getPermissionDisplayName(permission: String): String {
        return when (permission) {
            Manifest.permission.RECORD_AUDIO -> "Microphone"
            Manifest.permission.INTERNET -> "Internet"
            Manifest.permission.ACCESS_NETWORK_STATE -> "Network State"
            Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
            else -> permission.substringAfterLast(".")
        }
    }
}
