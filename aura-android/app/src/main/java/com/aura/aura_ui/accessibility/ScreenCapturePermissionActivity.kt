package com.aura.aura_ui.accessibility

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Transparent, no-UI Activity that triggers the system MediaProjection consent dialog.
 *
 * Services (AccessibilityService, ForegroundService) cannot call startActivityForResult,
 * so this Activity acts as the lifecycle host for the permission flow:
 *
 *   1. ForegroundService receives WS command → launches this Activity via FLAG_ACTIVITY_NEW_TASK
 *   2. This Activity immediately requests the MediaProjection consent dialog
 *   3. onActivityResult forwards the granted token to AuraAccessibilityService
 *   4. Activity finishes itself — no UI is ever shown
 */
class ScreenCapturePermissionActivity : Activity() {

    companion object {
        private const val TAG = "ScreenCapturePermActivity"
        private const val MEDIA_PROJECTION_REQUEST_CODE = 1001

        fun createIntent(context: Context): Intent =
            Intent(context, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Requesting MediaProjection consent dialog")

        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            MEDIA_PROJECTION_REQUEST_CODE,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != MEDIA_PROJECTION_REQUEST_CODE) {
            finish()
            return
        }

        val granted = resultCode == RESULT_OK && data != null
        Log.d(TAG, "MediaProjection result: granted=$granted resultCode=$resultCode")

        if (granted) {
            // Forward token to the accessibility service so it can start screen capture
            val service = AuraAccessibilityService.instance
            if (service != null) {
                service.initializeMediaProjection(resultCode, data!!)
            } else {
                // Service not yet connected — stash for consumption in onServiceConnected
                AuraAccessibilityService.pendingMediaProjectionResultCode = resultCode
                AuraAccessibilityService.pendingMediaProjectionData = data
                Log.w(TAG, "AuraAccessibilityService not running yet — stashed pending token")
            }
        }

        // Notify backend of the result regardless
        AuraAccessibilityService.sendScreenCapturePermissionResult(
            granted = granted,
            error = if (granted) null else "User denied MediaProjection permission",
        )

        finish()
    }
}
