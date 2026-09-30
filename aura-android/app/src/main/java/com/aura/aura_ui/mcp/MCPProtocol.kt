package com.aura.aura_ui.mcp

import org.json.JSONObject

/**
 * MCP WebSocket protocol constants and response builders.
 *
 * Single source of truth for all message types exchanged between the
 * Android companion app and the AURA MCP server over `/ws/device`.
 */
object MCPProtocol {

    // ── Downstream (Server → Android) ────────────────────────────────────────

    /** Execute a gesture (tap, swipe, long_press, type, etc.) */
    const val EXECUTE_GESTURE = "execute_gesture"

    /** Request a screenshot via MediaProjection */
    const val REQUEST_SCREENSHOT = "request_screenshot"

    /** Request the accessibility UI tree */
    const val REQUEST_UI_TREE = "request_ui_tree"

    /** Launch an app by package name */
    const val LAUNCH_APP = "launch_app"

    /** Launch a deep link URI */
    const val LAUNCH_DEEP_LINK = "launch_deep_link"

    /** Resolve a contact name to a phone number */
    const val RESOLVE_CONTACT = "resolve_contact"

    /** Request screen capture (MediaProjection) permission from the user */
    const val REQUEST_SCREEN_CAPTURE_PERMISSION = "request_screen_capture_permission"

    /** Server asks user to approve a connection */
    const val CONNECTION_REQUEST = "connection_request"

    /** Task progress update for overlay display */
    const val TASK_PROGRESS = "task_progress"

    /** Agent status update for overlay display */
    const val AGENT_STATUS = "agent_status"

    /** Show visual feedback (tap ripple, edge glow) */
    const val VISUAL_FEEDBACK = "visual_feedback"

    /** Human-in-the-loop question dialog */
    const val HITL_QUESTION = "hitl_question"

    /** Dismiss active HITL dialog */
    const val HITL_DISMISS = "hitl_dismiss"

    /** Execute a step (legacy planning system) */
    const val EXECUTE_STEP = "execute_step"

    /** Device info acknowledgment from server */
    const val DEVICE_INFO_ACK = "device_info_ack"


    // ── Upstream (Android → Server) ──────────────────────────────────────────

    /** Device metadata sent on WebSocket connect */
    const val DEVICE_INFO = "device_info"

    /** Gesture execution acknowledgment */
    const val GESTURE_ACK = "gesture_ack"

    /** Screenshot response with base64 image data */
    const val SCREENSHOT_RESPONSE = "screenshot_response"

    /** UI tree response with accessibility node data */
    const val UI_TREE_RESPONSE = "ui_tree_response"

    /** Generic command result (app launch, deep link, etc.) */
    const val COMMAND_RESULT = "command_result"

    /** Step execution result (legacy planning system) */
    const val STEP_RESULT = "step_result"

    /** Contact resolution result */
    const val CONTACT_RESOLUTION_RESULT = "contact_resolution_result"

    /** Screen capture permission grant/deny result */
    const val SCREEN_CAPTURE_PERMISSION_RESULT = "screen_capture_permission_result"

    /** User approved connection */
    const val CONNECTION_APPROVED = "connection_approved"

    /** User denied connection */
    const val CONNECTION_DENIED = "connection_denied"

    /** HITL user response */
    const val HITL_RESPONSE = "hitl_response"

    /** Task cancellation request */
    const val CANCEL_TASK = "cancel_task"


    // ── Response Builders ────────────────────────────────────────────────────

    fun buildGestureAck(commandId: String, success: Boolean, error: String? = null): JSONObject =
        JSONObject().apply {
            put("type", GESTURE_ACK)
            put("command_id", commandId)
            put("success", success)
            if (error != null) put("error", error)
            put("timestamp", System.currentTimeMillis())
        }

    fun buildCommandResult(
        commandId: String,
        commandType: String,
        success: Boolean,
        error: String? = null,
    ): JSONObject =
        JSONObject().apply {
            put("type", COMMAND_RESULT)
            put("command_id", commandId)
            put("command_type", commandType)
            put("success", success)
            if (error != null) put("error", error)
        }

    fun buildScreenshotResponse(
        requestId: String,
        screenshotBase64: String,
        screenWidth: Int,
        screenHeight: Int,
    ): JSONObject =
        JSONObject().apply {
            put("type", SCREENSHOT_RESPONSE)
            put("request_id", requestId)
            put("screenshot_base64", screenshotBase64)
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            put("timestamp", System.currentTimeMillis())
        }

    fun buildUiTreeResponse(
        requestId: String,
        uiTree: JSONObject,
    ): JSONObject =
        JSONObject().apply {
            put("type", UI_TREE_RESPONSE)
            put("request_id", requestId)
            put("ui_tree", uiTree)
        }

    fun buildContactResolutionResult(
        requestId: String,
        contactName: String,
        phoneNumber: String?,
        success: Boolean,
        error: String? = null,
    ): JSONObject =
        JSONObject().apply {
            put("type", CONTACT_RESOLUTION_RESULT)
            put("request_id", requestId)
            put("contact_name", contactName)
            put("phone_number", phoneNumber)
            put("success", success)
            if (error != null) put("error", error)
        }

    fun buildStepResult(
        stepId: String,
        success: Boolean,
        error: String? = null,
        uiAfter: Map<String, Any>? = null,
    ): JSONObject =
        JSONObject().apply {
            put("type", STEP_RESULT)
            put("step_id", stepId)
            put("success", success)
            if (error != null) put("error", error)
            put("ui_after", if (uiAfter != null) JSONObject(uiAfter) else JSONObject())
        }
}
