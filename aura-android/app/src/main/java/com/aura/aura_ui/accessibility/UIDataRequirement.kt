package com.aura.aura_ui.accessibility

/**
 * Represents UI data requirements determined by Commander Agent
 */
data class UIDataRequirement(
    val needsUITree: Boolean,
    val needsScreenshot: Boolean,
    val requestReason: String,
    val taskId: String? = null,
    val priority: Priority = Priority.NORMAL,
) {
    enum class Priority {
        LOW, // Can batch/delay
        NORMAL, // Send when ready
        HIGH, // Send immediately
        URGENT, // Send immediately, retry on failure
    }

    fun requiresData(): Boolean = needsUITree || needsScreenshot

    companion object {
        val NONE =
            UIDataRequirement(
                needsUITree = false,
                needsScreenshot = false,
                requestReason = "no_data_needed",
            )

        val UI_TREE_ONLY =
            UIDataRequirement(
                needsUITree = true,
                needsScreenshot = false,
                requestReason = "ui_navigation",
            )

        val SCREENSHOT_ONLY =
            UIDataRequirement(
                needsUITree = false,
                needsScreenshot = true,
                requestReason = "visual_verification",
            )

        val FULL_UI_DATA =
            UIDataRequirement(
                needsUITree = true,
                needsScreenshot = true,
                requestReason = "initial_context",
            )
    }
}
