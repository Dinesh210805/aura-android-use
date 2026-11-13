package com.aura.aura_ui.presentation.permissions

// ============================================================================
// PERMISSION TOGGLE MODEL — pure logic that lets every permission render as a
// switch honestly (spec v2 §1.4).
//
//   RUNTIME  perms: turning ON shows the OS runtime dialog. Turning OFF opens
//            AURA's App info page — Android has no API for an app to revoke
//            its own grant immediately (revokeSelfPermissionsOnKill only acts
//            when the process dies), so the user switches it off there.
//   SPECIAL  perms (accessibility, overlay, notification-listener, keyboard,
//            exact-alarms, battery) can only be changed on a system settings
//            page — so the switch mirrors real state and any toggle routes to
//            that page. No fake in-app "off".
// ============================================================================

enum class ToggleAction { REQUEST_RUNTIME, OPEN_SYSTEM, NONE }

data class ToggleUiState(
    val checked: Boolean,
    val routesToSystem: Boolean,
    val caption: String?,
)

private const val SYSTEM_CAPTION = "Opens Android settings"
private const val RUNTIME_OFF_CAPTION = "Turning off opens App info"

/** How the switch should look for [p] given its current [granted] state. */
fun toggleUiState(p: AuraPermission, granted: Boolean): ToggleUiState {
    val special = p.kind == PermissionKind.SPECIAL
    return ToggleUiState(
        checked = granted,
        routesToSystem = special,
        caption = when {
            special -> SYSTEM_CAPTION
            granted -> RUNTIME_OFF_CAPTION
            else -> null
        },
    )
}

/** What to do when the user flips [p]'s switch toward [desired] from [granted]. */
fun toggleAction(p: AuraPermission, granted: Boolean, desired: Boolean): ToggleAction = when {
    p.kind == PermissionKind.SPECIAL -> ToggleAction.OPEN_SYSTEM
    !granted && desired -> ToggleAction.REQUEST_RUNTIME
    granted && !desired -> ToggleAction.OPEN_SYSTEM
    else -> ToggleAction.NONE
}
