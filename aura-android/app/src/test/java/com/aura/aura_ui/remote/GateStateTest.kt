package com.aura.aura_ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateStateTest {

    private fun baseline(
        currentVersionCode: Long = 100L,
        killSwitchEnabled: Boolean = false,
        killSwitchMessage: String = "killed",
        minSupportedVersionCode: Long = 0L,
        versionGateMessage: String = "too old",
        // Default "latest == current" so tests that only vary currentVersionCode
        // don't accidentally trip the update-available nudge; tests that exercise
        // the nudge set latestVersionCode explicitly. (Kotlin allows a default
        // argument to reference an earlier parameter.)
        latestVersionCode: Long = currentVersionCode,
        updateUrl: String = "https://example.com/release",
    ) = evaluateGate(
        currentVersionCode, killSwitchEnabled, killSwitchMessage,
        minSupportedVersionCode, versionGateMessage, latestVersionCode, updateUrl,
    )

    @Test fun `everything default resolves to Allowed`() {
        assertEquals(GateState.Allowed, baseline())
    }

    @Test fun `kill switch blocks regardless of version`() {
        val state = baseline(killSwitchEnabled = true, killSwitchMessage = "stop")
        assertTrue(state is GateState.KillSwitchBlocked)
        assertEquals("stop", (state as GateState.KillSwitchBlocked).message)
    }

    @Test fun `kill switch takes precedence over version block`() {
        val state = baseline(
            killSwitchEnabled = true,
            currentVersionCode = 1L,
            minSupportedVersionCode = 50L,
        )
        assertTrue("kill switch must win", state is GateState.KillSwitchBlocked)
    }

    @Test fun `version below minimum is blocked`() {
        val state = baseline(currentVersionCode = 10L, minSupportedVersionCode = 20L, versionGateMessage = "update now")
        assertTrue(state is GateState.VersionBlocked)
        val blocked = state as GateState.VersionBlocked
        assertEquals("update now", blocked.message)
        assertEquals("https://example.com/release", blocked.updateUrl)
    }

    @Test fun `version equal to minimum is NOT blocked`() {
        val state = baseline(currentVersionCode = 20L, minSupportedVersionCode = 20L)
        assertEquals(GateState.Allowed, state)
    }

    @Test fun `latest version code greater than current shows update available`() {
        val state = baseline(currentVersionCode = 90L, latestVersionCode = 100L, updateUrl = "https://x/release")
        assertTrue(state is GateState.UpdateAvailable)
        assertEquals("https://x/release", (state as GateState.UpdateAvailable).updateUrl)
    }

    @Test fun `latest version code equal to current is Allowed, not a nudge`() {
        val state = baseline(currentVersionCode = 100L, latestVersionCode = 100L)
        assertEquals(GateState.Allowed, state)
    }

    @Test fun `minimum version zero means no floor enforced`() {
        val state = baseline(currentVersionCode = 1L, minSupportedVersionCode = 0L)
        assertEquals(GateState.Allowed, state)
    }

    @Test fun `isBlocking is true only for kill switch and version block`() {
        assertTrue(GateState.KillSwitchBlocked("x").isBlocking())
        assertTrue(GateState.VersionBlocked("x", "u").isBlocking())
        assertFalse(GateState.Allowed.isBlocking())
        assertFalse(GateState.UpdateAvailable("u").isBlocking())
    }

    @Test fun `blockMessageOrNull returns the message for blocking states, null otherwise`() {
        assertEquals("stop", GateState.KillSwitchBlocked("stop").blockMessageOrNull())
        assertEquals("too old", GateState.VersionBlocked("too old", "u").blockMessageOrNull())
        assertNull(GateState.Allowed.blockMessageOrNull())
        assertNull(GateState.UpdateAvailable("u").blockMessageOrNull())
    }

    // ── restoring a saved verdict (RemoteGateManager.hydrate) ────────────────

    private val notBlocked = Blocklist.Verdict(false, null)

    @Test fun `a blocklist hit blocks with its own message even with the kill switch off`() {
        val state = evaluateGate(GateInputs(), Blocklist.Verdict(true, "you are blocked"), currentVersionCode = 100L)
        assertEquals(GateState.KillSwitchBlocked("you are blocked"), state)
    }

    @Test fun `a blocklist hit without a message uses the default`() {
        val state = evaluateGate(GateInputs(), Blocklist.Verdict(true, null), currentVersionCode = 100L)
        assertEquals(GateState.KillSwitchBlocked(Blocklist.DEFAULT_MESSAGE), state)
    }

    /** The case hydrate exists for: the saved floor still applies to this old build. */
    @Test fun `a saved version floor blocks this build`() {
        val inputs = GateInputs(minSupportedVersionCode = 200L, versionGateMessage = "update", updateUrl = "u")
        assertEquals(GateState.VersionBlocked("update", "u"), evaluateGate(inputs, notBlocked, currentVersionCode = 150L))
    }

    /** ...and stops applying the moment the user installs the update, before any fetch. */
    @Test fun `a saved version floor does not block the updated build`() {
        val inputs = GateInputs(minSupportedVersionCode = 200L, versionGateMessage = "update", updateUrl = "u")
        assertEquals(GateState.Allowed, evaluateGate(inputs, notBlocked, currentVersionCode = 200L))
    }

    @Test fun `never refreshed means allowed`() =
        assertEquals(GateState.Allowed, evaluateGate(GateInputs(), notBlocked, currentVersionCode = 1L))
}
