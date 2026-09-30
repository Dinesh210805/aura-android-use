package com.aura.aura_ui.overlay

import android.util.Log
import org.json.JSONObject

/**
 * WebSocket message handler for Human-in-the-Loop (HITL) dialogs.
 * 
 * Handles questions from the agent that require user input:
 * - Confirmations (Yes/No)
 * - Single/Multiple choice
 * - Text input
 * - Action required (e.g., biometric unlock)
 * - Notifications
 */
object HITLHandler {
    
    private const val TAG = "HITLHandler"
    
    // Callback to send responses back to WebSocket
    private var responseCallback: ((JSONObject) -> Unit)? = null
    
    /**
     * Set the callback for sending HITL responses back through WebSocket.
     */
    fun setResponseCallback(callback: (JSONObject) -> Unit) {
        responseCallback = callback
    }
    
    /**
     * Handle hitl_question WebSocket message.
     */
    fun handleMessage(json: JSONObject) {
        val questionId = json.optString("question_id", "")
        val questionType = json.optString("question_type", "")
        val title = json.optString("title", "Question")
        val message = json.optString("message", "")
        val timeoutSeconds = json.optDouble("timeout_seconds", 60.0).toFloat()
        val allowCancel = json.optBoolean("allow_cancel", true)
        val actionType = json.optString("action_type", null)
        
        // Parse options array
        val optionsArray = json.optJSONArray("options")
        val options = mutableListOf<String>()
        optionsArray?.let {
            for (i in 0 until it.length()) {
                options.add(it.getString(i))
            }
        }
        
        // Parse metadata
        val metadataJson = json.optJSONObject("metadata")
        val metadata = mutableMapOf<String, Any>()
        metadataJson?.let {
            for (key in it.keys()) {
                metadata[key] = it.get(key)
            }
        }
        
        val defaultOption = json.optString("default_option", null)
        
        Log.i(TAG, "🙋 HITL question received: id=$questionId, type=$questionType, title=$title")
        
        val serviceInstance = VisualFeedbackOverlayService.getInstance()
        if (serviceInstance == null) {
            Log.w(TAG, "⚠️ VisualFeedbackOverlayService not running - HITL dialog unavailable")
            sendErrorResponse(questionId, "Overlay service not running")
            return
        }
        
        VisualFeedbackOverlayService.showHITLDialog(
            questionId = questionId,
            questionType = questionType,
            title = title,
            message = message,
            options = options,
            defaultOption = defaultOption,
            timeoutSeconds = timeoutSeconds,
            allowCancel = allowCancel,
            actionType = actionType,
            metadata = metadata
        ) { response ->
            sendResponse(response)
        }
    }
    
    /**
     * Handle hitl_dismiss message - dismiss any active dialog.
     */
    fun handleDismiss(json: JSONObject) {
        val questionId = json.optString("question_id", "")
        Log.i(TAG, "🔇 HITL dismiss received: id=$questionId")
        
        VisualFeedbackOverlayService.dismissHITLDialog()
    }
    
    /**
     * Send HITL response back through WebSocket.
     */
    private fun sendResponse(response: Map<String, Any>) {
        val jsonResponse = JSONObject().apply {
            put("type", "hitl_response")
            for ((key, value) in response) {
                when (value) {
                    is List<*> -> {
                        val jsonArray = org.json.JSONArray()
                        value.forEach { jsonArray.put(it) }
                        put(key, jsonArray)
                    }
                    else -> put(key, value)
                }
            }
        }
        
        Log.i(TAG, "📤 Sending HITL response: $jsonResponse")
        responseCallback?.invoke(jsonResponse)
    }
    
    /**
     * Send error response when HITL cannot be shown.
     */
    private fun sendErrorResponse(questionId: String, error: String) {
        val response = JSONObject().apply {
            put("type", "hitl_response")
            put("question_id", questionId)
            put("success", false)
            put("error", error)
        }
        
        Log.w(TAG, "📤 Sending HITL error response: $response")
        responseCallback?.invoke(response)
    }
}
