package com.aura.aura_ui.notifications

import android.app.Notification
import com.aura.aura_ui.notifications.AuraNotificationListenerService.Companion.isWaitingOn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationWaitingFilterTest {

    @Test
    fun `a chat message is kept`() {
        assertTrue(isWaitingOn(ongoing = false, flags = 0, category = Notification.CATEGORY_MESSAGE, hasMediaSession = false))
    }

    @Test
    fun `a paused media player is dropped even though it is no longer ongoing`() {
        assertFalse(isWaitingOn(ongoing = false, flags = 0, category = null, hasMediaSession = true))
        assertFalse(isWaitingOn(ongoing = false, flags = 0, category = Notification.CATEGORY_TRANSPORT, hasMediaSession = false))
    }

    @Test
    fun `ongoing, foreground-service and group-summary notifications are dropped`() {
        assertFalse(isWaitingOn(ongoing = true, flags = 0, category = null, hasMediaSession = false))
        assertFalse(isWaitingOn(ongoing = false, flags = Notification.FLAG_FOREGROUND_SERVICE, category = null, hasMediaSession = false))
        assertFalse(isWaitingOn(ongoing = false, flags = Notification.FLAG_GROUP_SUMMARY, category = null, hasMediaSession = false))
    }
}
