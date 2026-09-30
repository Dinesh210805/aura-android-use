package com.aura.aura_ui.overlay

import android.util.Log
import org.json.JSONObject

/**
 * WebSocket message handler for visual feedback commands.
 */
object VisualFeedbackHandler {
    
    private const val TAG = "VisualFeedback"
    
    /**
     * Handle visual_feedback WebSocket message.
     */
    fun handleMessage(json: JSONObject) {
        val effect = json.optString("effect")
        val action = json.optString("action")
        val config = json.optJSONObject("config")
        
        Log.i(TAG, "📩 Received visual feedback command: effect=$effect, action=$action")
        
        val configMap = mutableMapOf<String, Any>()
        config?.let {
            for (key in it.keys()) {
                configMap[key] = it.get(key)
            }
        }
        
        val serviceInstance = VisualFeedbackOverlayService.getInstance()
        if (serviceInstance == null) {
            Log.w(TAG, "⚠️ VisualFeedbackOverlayService not running - visual feedback disabled")
            return
        }
        
        VisualFeedbackOverlayService.handleVisualFeedbackCommand(
            mapOf(
                "effect" to effect,
                "action" to action,
                "config" to configMap
            )
        )
    }
    
    /**
     * Show tap ripple - call this from gesture execution before tap.
     */
    fun showTapAt(x: Int, y: Int) {
        Log.i(TAG, "👆 Showing tap ripple at ($x, $y)")
        
        val serviceInstance = VisualFeedbackOverlayService.getInstance()
        if (serviceInstance == null) {
            Log.w(TAG, "⚠️ VisualFeedbackOverlayService not running - tap ripple skipped")
            return
        }
        
        VisualFeedbackOverlayService.showTapRipple(
            x = x.toFloat(),
            y = y.toFloat()
        )
    }
    
    // onAutomationStart/onAutomationEnd used to drive the edge glow from here. Both were
    // already unreachable (no callers), and the glow now has one owner —
    // AuraOverlayService via EdgeGlow — so they are gone rather than left as a second
    // way to turn it on.
}
