package com.aura.aura_ui.agent.memory

import android.app.AlarmManager
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Robolectric 4.11 cannot model targetSdk 36 and drops to API 19, where allow-while-idle alarms do
// not exist. Pin a level that has them.
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Test fun `a future reminder fires at its due time, an overdue one right away`() {
        assertEquals(5_000L, ReminderScheduler.triggerAt(dueAtEpochMs = 5_000L, nowEpochMs = 1_000L))
        assertEquals(1_000L + ReminderScheduler.OVERDUE_GRACE_MS, ReminderScheduler.triggerAt(500L, 1_000L))
    }

    @Test fun `saving a reminder arms an alarm for it`() = runTest {
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "reminders_1"))
            .also { svc -> svc.onRemind = { ReminderScheduler.schedule(ctx, it) } }
        val due = System.currentTimeMillis() + 60 * 60 * 1000

        memory.remind("call Amma", due)

        val next = alarms.peekNextScheduledAlarm()
        assertNotNull("remind() must arm an alarm — a stored reminder that never rings is the bug", next)
        assertEquals(due, next!!.triggerAtTime)
    }

    @Test fun `a delivered reminder is not re-armed after reboot`() = runTest {
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "reminders_2"))
        val c = memory.remind("done already", System.currentTimeMillis() + 1_000)
        memory.completeCommitments(listOf(c.id))

        memory.pendingCommitments().forEach { ReminderScheduler.schedule(ctx, it) }

        assertNull(alarms.peekNextScheduledAlarm())
    }
}
