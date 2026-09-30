package com.aura.aura_ui.mcp.log

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Phase 10B — daily WorkManager job that deletes session log folders
 * older than the user-configured retention window.
 *
 * Schedule it once from [com.aura.aura_ui.services.AssistantForegroundService]
 * onCreate. WorkManager dedupes by unique-name, so repeated calls are
 * idempotent — calling enqueueUniquePeriodicWork with KEEP semantics
 * does nothing if the job is already scheduled.
 */
class McpLogCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = LogRetentionPrefs(applicationContext)
        val days = prefs.retentionDays()
        val cutoff = System.currentTimeMillis() - days * MS_PER_DAY
        val deleted = McpSessionStore(applicationContext).deleteOlderThan(cutoff)
        Log.i(TAG, "Cleanup pass: deleted $deleted session(s) older than $days days")
        return Result.success()
    }

    companion object {
        private const val TAG = "McpLogCleanup"
        private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L
        private const val UNIQUE_WORK_NAME = "aura_mcp_log_cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<McpLogCleanupWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            ).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }
    }
}
