package com.aura.aura_ui.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.aura.aura_ui.R
import com.aura.aura_ui.agent.hitl.PendingQuestion

/**
 * The notification for a question the user has not answered yet, with its options as buttons.
 *
 * - Contract: [show] replaces any earlier question's notification; [cancel] is idempotent. A button
 *   starts `AuraOverlayService` with [AuraOverlayService.ACTION_ANSWER_QUESTION], which answers
 *   `AskUserBroker.shared`; a stale answer (the question already ended) is ignored there.
 * - Why: the fallback for a question card the user cannot see. A notification is never hidden by
 *   the app in front, and it reaches a locked or covered screen.
 * - Fails: silently without the notification permission; the card and the 120 s timeout remain.
 * - Security: `AskUserBroker.answer` refuses while the phone is locked, so nobody holding a locked
 *   phone can approve a step from a button; the lock screen shows no question text.
 * - Change together: `AuraOverlayService.startHitlObserver` (posts it after a delay) and its
 *   `onStartCommand` (handles the buttons).
 */
internal object QuestionNotification {

    private const val CHANNEL_ID = "aura_questions"
    private const val NOTIFICATION_ID = 0x0A5C
    private const val MAX_BUTTONS = 3

    fun show(context: Context, question: PendingQuestion) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AURA questions", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A question AURA needs answered before it goes on" },
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AURA is waiting for you")
            .setContentText(question.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question.question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            // The question can name a person or a message: only "AURA is waiting" on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("AURA is waiting for you")
                    .build(),
            )
        question.options.take(MAX_BUTTONS).forEachIndexed { i, option ->
            builder.addAction(0, option, answerIntent(context, question.id, option, requestCode = i))
        }
        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun answerIntent(context: Context, id: String, text: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            requestCode,
            Intent(context, AuraOverlayService::class.java)
                .setAction(AuraOverlayService.ACTION_ANSWER_QUESTION)
                .putExtra(AuraOverlayService.EXTRA_QUESTION_ID, id)
                .putExtra(AuraOverlayService.EXTRA_ANSWER, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
