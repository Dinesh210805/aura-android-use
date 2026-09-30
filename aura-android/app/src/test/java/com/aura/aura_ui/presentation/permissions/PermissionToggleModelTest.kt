package com.aura.aura_ui.presentation.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionToggleModelTest {
    private fun perm(kind: PermissionKind) = AuraPermission(
        id = "x", title = "X", why = "why", tier = 1, kind = kind,
        runtimePermissions = if (kind == PermissionKind.RUNTIME) listOf("p") else emptyList(),
        isGranted = { false },
    )

    @Test
    fun `special routes to system with caption`() {
        val s = toggleUiState(perm(PermissionKind.SPECIAL), granted = false)
        assertEquals(false, s.checked)
        assertEquals(true, s.routesToSystem)
        assertEquals("Opens Android settings", s.caption)
    }

    @Test
    fun `runtime has no caption`() {
        val s = toggleUiState(perm(PermissionKind.RUNTIME), granted = true)
        assertEquals(true, s.checked)
        assertEquals(false, s.routesToSystem)
        assertNull(s.caption)
    }

    @Test
    fun `special toggle always opens system`() {
        assertEquals(
            ToggleAction.OPEN_SYSTEM,
            toggleAction(perm(PermissionKind.SPECIAL), granted = false, desired = true),
        )
        assertEquals(
            ToggleAction.OPEN_SYSTEM,
            toggleAction(perm(PermissionKind.SPECIAL), granted = true, desired = false),
        )
    }

    @Test
    fun `runtime turning on requests`() {
        assertEquals(
            ToggleAction.REQUEST_RUNTIME,
            toggleAction(perm(PermissionKind.RUNTIME), granted = false, desired = true),
        )
    }

    @Test
    fun `runtime turning off is no-op here`() {
        assertEquals(
            ToggleAction.NONE,
            toggleAction(perm(PermissionKind.RUNTIME), granted = true, desired = false),
        )
    }
}
