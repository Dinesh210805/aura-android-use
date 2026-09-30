package com.aura.aura_ui.agent

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
class RunKeepAwakeTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test fun `acquire holds a screen wake lock so the display cannot sleep mid-run`() {
        val keepAwake = RunKeepAwake(ctx)
        keepAwake.acquire()
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun `release lets the screen sleep again`() {
        val keepAwake = RunKeepAwake(ctx)
        keepAwake.acquire()
        keepAwake.release()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun `acquire is idempotent - a second acquire does not stack a second lock`() {
        val keepAwake = RunKeepAwake(ctx)
        keepAwake.acquire()
        val first = ShadowPowerManager.getLatestWakeLock()
        keepAwake.acquire()
        assertTrue(first === ShadowPowerManager.getLatestWakeLock())
        keepAwake.release()
        assertFalse(first.isHeld)
    }

    @Test fun `release without acquire is a safe no-op`() {
        RunKeepAwake(ctx).release()
    }

    @Test fun `overlapping runs hold independent locks - one ending does not drop the other`() {
        val runA = RunKeepAwake(ctx)
        val runB = RunKeepAwake(ctx)
        runA.acquire()
        val lockA = ShadowPowerManager.getLatestWakeLock()
        runB.acquire()
        val lockB = ShadowPowerManager.getLatestWakeLock()
        runA.release()
        assertFalse(lockA.isHeld)
        assertTrue(lockB.isHeld)
    }
}
