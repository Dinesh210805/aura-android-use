package com.aura.aura_ui.presentation.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRegistryTest {

    @Test
    fun `ids are unique`() {
        val ids = PermissionRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every permission is tier 1 or 2`() {
        assertTrue(PermissionRegistry.all.all { it.tier == 1 || it.tier == 2 })
    }

    @Test
    fun `tier 1 is exactly the five core permissions`() {
        // aura_keyboard is core, not optional: apps built on React Native/Flutter
        // expose no editable node, so the IME is the only path that can type there.
        assertEquals(
            setOf("microphone", "overlay", "accessibility", "notifications", "aura_keyboard"),
            PermissionRegistry.tier1.map { it.id }.toSet(),
        )
    }

    @Test
    fun `new tool planes are covered by tier 2`() {
        val tier2 = PermissionRegistry.tier2.map { it.id }.toSet()
        assertTrue("contacts missing", "contacts" in tier2)
        assertTrue("files missing", "files" in tier2)
        assertTrue("notification access missing", "notification_access" in tier2)
    }

    @Test
    fun `runtime permissions declare manifest strings, special ones do not need them`() {
        PermissionRegistry.all.filter { it.kind == PermissionKind.RUNTIME }.forEach { p ->
            // notifications is legitimately empty below API 33 at test-JVM time only
            // when the list is built for pre-33; structurally we accept empty there.
            if (p.id != "notifications") {
                assertTrue("${p.id} has no runtime permission strings", p.runtimePermissions.isNotEmpty())
            }
        }
        PermissionRegistry.all.filter { it.kind == PermissionKind.SPECIAL }.forEach { p ->
            assertTrue("${p.id} should not declare runtime strings", p.runtimePermissions.isEmpty())
        }
    }

    @Test
    fun `every permission has user-facing copy`() {
        PermissionRegistry.all.forEach { p ->
            assertTrue("${p.id} title blank", p.title.isNotBlank())
            assertTrue("${p.id} why blank", p.why.length > 10)
        }
    }
}
