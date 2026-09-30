package com.aura.aura_ui.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * Visual Feedback Service - Manages overlay windows for automation feedback.
 * 
 * Provides Apple Intelligence style visual effects:
 * - Edge glow (white glow on all 4 edges with inward shadow)
 * - Tap ripples (expanding circles at touch points)
 * 
 * Requires SYSTEM_ALERT_WINDOW permission.
 */
class VisualFeedbackOverlayService : Service() {
    
    private var windowManager: WindowManager? = null
    private var tapRippleOverlay: TapRippleOverlay? = null
    private var hitlDialogOverlay: HITLDialogOverlay? = null
    private var isOverlayAdded = false
    private var isHITLOverlayAdded = false
    
    companion object {
        private const val TAG = "VisualFeedback"
        private var instance: VisualFeedbackOverlayService? = null
        
        // Main thread handler for UI operations
        private val mainHandler = Handler(Looper.getMainLooper())
        
        fun getInstance(): VisualFeedbackOverlayService? = instance
        
        /**
         * Check if overlays can be drawn
         */
        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }
        
        /**
         * Show tap ripple at location.
         * [maxRadiusDp] is the outer ring's final radius in dp (default 96 dp).
         */
        fun showTapRipple(
            x: Float,
            y: Float,
            maxRadiusDp: Float = 96f,
            durationMs: Long = 520
        ) {
            mainHandler.post {
                instance?.tapRippleOverlay?.showRipple(x, y, maxRadiusDp, durationMs)
            }
        }
        
        /**
         * Handle visual feedback command from WebSocket.
         */
        fun handleVisualFeedbackCommand(command: Map<String, Any>) {
            val effect = command["effect"] as? String ?: return
            val action = command["action"] as? String ?: return
            val config = command["config"] as? Map<String, Any> ?: emptyMap()
            
            Log.i(TAG, "🎨 Processing visual feedback: effect=$effect, action=$action")
            
            when (effect) {
                // "edge_glow" is deliberately absent. The rim glow is owned by
                // AuraOverlayService via [EdgeGlow] — one implementation, driven by
                // AURA's own lifecycle rather than a remote command.
                "tap_ripple" -> {
                    when (action) {
                        "show" -> {
                            val x = (config["x"] as? Number)?.toFloat() ?: return
                            val y = (config["y"] as? Number)?.toFloat() ?: return
                            val maxRadiusDp = (config["max_radius_dp"] as? Number)?.toFloat() ?: 96f
                            val durationMs = (config["duration_ms"] as? Number)?.toLong() ?: 520L

                            Log.i(TAG, "👆 Showing tap ripple at ($x, $y)")
                            showTapRipple(x, y, maxRadiusDp, durationMs)
                        }
                    }
                }
            }
        }
        
        /**
         * Show HITL dialog for user input.
         */
        fun showHITLDialog(
            questionId: String,
            questionType: String,
            title: String,
            message: String,
            options: List<String> = emptyList(),
            defaultOption: String? = null,
            timeoutSeconds: Float = 60f,
            allowCancel: Boolean = true,
            actionType: String? = null,
            metadata: Map<String, Any> = emptyMap(),
            callback: (Map<String, Any>) -> Unit
        ) {
            mainHandler.post {
                instance?.showHITLDialogInternal(
                    questionId, questionType, title, message,
                    options, defaultOption, timeoutSeconds,
                    allowCancel, actionType, metadata, callback
                )
            }
        }
        
        /**
         * Dismiss HITL dialog.
         */
        fun dismissHITLDialog() {
            mainHandler.post {
                instance?.hitlDialogOverlay?.dismiss()
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        if (canDrawOverlays(this)) {
            setupOverlays()
            Log.i(TAG, "✨ Visual feedback overlays initialized")
        } else {
            Log.w(TAG, "⚠️ SYSTEM_ALERT_WINDOW permission not granted")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        removeOverlays()
        instance = null
        Log.i(TAG, "Visual feedback service stopped")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun setupOverlays() {
        if (isOverlayAdded) return
        
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
        
        // Create overlay for tap ripples
        tapRippleOverlay = TapRippleOverlay(this).apply {
            visibility = android.view.View.GONE
        }
        
        val rippleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        
        // Exclude visual feedback overlays from screen capture (Android 13+) - MUST be set before addView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val privateFlagsField = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
                privateFlagsField.isAccessible = true

                // Exclude ripple overlay
                val rippleFlags = privateFlagsField.getInt(rippleParams)
                privateFlagsField.setInt(rippleParams, rippleFlags or 0x00000080)

                Log.d(TAG, "Visual feedback overlays will be excluded from screen capture")
            } catch (e: Exception) {
                Log.w(TAG, "Could not exclude visual overlays from capture: ${e.message}")
            }
        }

        try {
            windowManager?.addView(tapRippleOverlay, rippleParams)
            
            isOverlayAdded = true
            Log.i(TAG, "Overlays added to window manager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlays", e)
        }
    }
    
    private fun removeOverlays() {
        try {
            tapRippleOverlay?.let { windowManager?.removeView(it) }
            hitlDialogOverlay?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlays", e)
        }
        tapRippleOverlay = null
        hitlDialogOverlay = null
        isOverlayAdded = false
        isHITLOverlayAdded = false
    }
    
    /**
     * Internal method to show HITL dialog.
     */
    private fun showHITLDialogInternal(
        questionId: String,
        questionType: String,
        title: String,
        message: String,
        options: List<String>,
        defaultOption: String?,
        timeoutSeconds: Float,
        allowCancel: Boolean,
        actionType: String?,
        metadata: Map<String, Any>,
        callback: (Map<String, Any>) -> Unit
    ) {
        setupHITLOverlay()
        
        hitlDialogOverlay?.showQuestion(
            questionId = questionId,
            questionType = questionType,
            title = title,
            message = message,
            options = options,
            defaultOption = defaultOption,
            timeoutSeconds = timeoutSeconds,
            allowCancel = allowCancel,
            actionType = actionType,
            metadata = metadata,
            callback = callback
        )
    }
    
    /**
     * Setup HITL dialog overlay (creates if not exists).
     * HITL overlay needs to be focusable and touchable.
     */
    private fun setupHITLOverlay() {
        if (isHITLOverlayAdded) return
        
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
        
        hitlDialogOverlay = HITLDialogOverlay(this).apply {
            visibility = android.view.View.GONE
        }
        
        // HITL is interactive - needs to be focusable and touchable
        val hitlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        
        // Exclude HITL overlay from screen capture (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val privateFlagsField = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
                privateFlagsField.isAccessible = true
                val flags = privateFlagsField.getInt(hitlParams)
                privateFlagsField.setInt(hitlParams, flags or 0x00000080)
                Log.d(TAG, "HITL dialog overlay will be excluded from screen capture")
            } catch (e: Exception) {
                Log.w(TAG, "Could not exclude HITL overlay from capture: ${e.message}")
            }
        }
        
        try {
            windowManager?.addView(hitlDialogOverlay, hitlParams)
            isHITLOverlayAdded = true
            Log.i(TAG, "🙋 HITL dialog overlay added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add HITL overlay", e)
        }
    }
}
