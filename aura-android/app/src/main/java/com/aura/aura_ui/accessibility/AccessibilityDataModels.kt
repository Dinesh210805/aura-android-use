package com.aura.aura_ui.accessibility

data class UIElementData(
    val text: String?,
    val contentDescription: String?,
    val bounds: BoundsData,
    val className: String?,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean,
    val isFocused: Boolean,
    val actions: List<String>,
    val packageName: String?,
    val viewId: String?,
    /**
     * True for Switch / CheckBox / RadioButton / ToggleButton and Compose's
     * `Modifier.toggleable`. Independent of [isClickable]: preference rows
     * routinely put the click handler on the row and leave the widget
     * `clickable=false`, while split-target layouts
     * (`androidx.preference.TwoTargetPreference`) make the widget its own
     * target. Either way the widget is the correct thing to aim a gesture at.
     */
    val isCheckable: Boolean = false,
    /** Current toggle position. Only meaningful when [isCheckable]. */
    val isChecked: Boolean = false,
    /** Long-press-only affordances are invisible without this. */
    val isLongClickable: Boolean = false,
)

/**
 * Serialize one element into the wire shape the MCP layer parses.
 *
 * Pure (no Android types) so the payload contract is unit-testable — see
 * [com.aura.aura_ui.accessibility.UiElementSerializationTest]. Anything not
 * emitted here is invisible to the agent no matter what the tree contained.
 */
fun UIElementData.toTreeMap(): Map<String, Any> = mapOf(
    "text" to (text ?: ""),
    "contentDescription" to (contentDescription ?: ""),
    "className" to (className ?: ""),
    "bounds" to mapOf(
        "left" to bounds.left,
        "top" to bounds.top,
        "right" to bounds.right,
        "bottom" to bounds.bottom,
        "centerX" to bounds.centerX,
        "centerY" to bounds.centerY,
    ),
    "isClickable" to isClickable,
    "isScrollable" to isScrollable,
    "isEditable" to isEditable,
    "isCheckable" to isCheckable,
    "isChecked" to isChecked,
    "isLongClickable" to isLongClickable,
    "isEnabled" to isEnabled,
    "isFocused" to isFocused,
    "actions" to actions,
    "viewId" to (viewId ?: ""),
)

data class BoundsData(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val centerX: Int,
    val centerY: Int,
    val width: Int,
    val height: Int,
)

data class ScreenshotData(
    val screenshot: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val timestamp: Long,
    val uiElements: List<UIElementData>,
    val error: String? = null,  // Optional error message (permission invalidation, etc.)
)

data class GestureRequest(
    val action: String,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val duration: Long = 300L,
    val command_id: String? = null,
)

data class DeviceInfo(
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val screenWidth: Int,
    val screenHeight: Int,
)
