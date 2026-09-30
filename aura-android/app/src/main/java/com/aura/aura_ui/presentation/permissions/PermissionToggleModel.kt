package com.aura.aura_ui.presentation.permissions

// ============================================================================
// PERMISSION TOGGLE MODEL — pure logic that lets every permission render as a
// switch honestly (spec v2 §1.4).
//
//   RUNTIME  perms flip via the OS runtime dialog (turning ON requests them).
//   SPECIAL  perms (accessibility, overlay, notification-listener, screen-
//            capture, exact-alarms, battery) can only be changed on a system
//            settings page — so the switch mirrors real state and any toggle
//            routes to that page. No fake in-app "off".
// ============================================================================

enum class ToggleAction { REQUEST_RUNTIME, OPEN_SYSTEM, NONE }

data class ToggleUiState(
    val checked: Boolean,
    val routesToSystem: Boolean,
    val caption: String?,
)

private const val SYSTEM_CAPTION = "Opens Android settings"

/** How the switch should look for [p] given its current [granted] state. */
fun toggleUiState(p: AuraPermission, granted: Boolean): ToggleUiState {
    val special = p.kind == PermissionKind.SPECIAL
    return ToggleUiState(
        checked = granted,
        routesToSystem = special,
        caption = if (special) SYSTEM_CAPTION else null,
    )
}

/** What to do when the user flips [p]'s switch toward [desired] from [granted]. */
fun toggleAction(p: AuraPermission, granted: Boolean, desired: Boolean): ToggleAction = when {
    p.kind == PermissionKind.SPECIAL -> ToggleAction.OPEN_SYSTEM
    !granted && desired -> ToggleAction.REQUEST_RUNTIME
    else -> ToggleAction.NONE
}
