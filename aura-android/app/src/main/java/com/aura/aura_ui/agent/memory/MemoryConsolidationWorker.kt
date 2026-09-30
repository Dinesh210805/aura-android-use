package com.aura.aura_ui.agent.memory

import android.content.Context
import com.aura.aura_ui.agent.conversation.brainFactReconciler
import com.aura.aura_ui.agent.conversation.brainLlm
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.serializer
import java.util.concurrent.TimeUnit

/**
 * Periodic off-hot-path consolidation of the conversation-memory store (decay + de-dup + cap).
 * Thin glue over MemoryConsolidator; scheduling mirrors the Phase-10B session-log cleanup worker.
 * Learnings are capped per-key at write time already, so this worker focuses on the entries store.
 */
class MemoryConsolidationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val store = EncryptedJsonStore(applicationContext, "aura_memory", failClosedWhenUnencrypted = true)
            val now = System.currentTimeMillis()
            val kept = MemoryConsolidator.consolidateEntries(
                store.read("entries", serializer<MemoryEntry>()),
                nowEpochMs = now, ttlMs = TTL_MS, cap = CAP,
            )
            store.write("entries", kept, serializer<MemoryEntry>())

            // Spec 2026-07-17 — decay-prune the learnings store too (paths and
            // app facts age out on per-kind TTLs; reinforcement refreshes them).
            val learnings = EncryptedJsonStore(applicationContext, "aura_learnings", failClosedWhenUnencrypted = true)
            learnings.keys().forEach { key ->
                if (key.startsWith(EncryptedLearningsStore.FACTS_PREFIX)) {
                    val remaining = MemoryConsolidator.decayFacts(learnings.read(key, serializer<AppFactEntry>()), now)
                    learnings.write(key, remaining, serializer<AppFactEntry>())
                } else {
                    val remaining = MemoryConsolidator.decayLearnings(learnings.read(key, serializer<LearningEntry>()), now)
                    learnings.write(key, remaining, serializer<LearningEntry>())
                }
            }
            // After the mechanical pass: the two that need the brain model. Each skips itself on any
            // failure (offline, no brain configured) and simply tries again tomorrow.
            val memory = memoryService(applicationContext)
            val llm = brainLlm(applicationContext, temperature = 0.2)
            val maintenance = MemoryMaintenance(memory, llm, brainFactReconciler(applicationContext, memory))
            runCatching { maintenance.rollUpOldWeeks() }
            runCatching { maintenance.reflect() }
            runCatching { memory.refreshVectors() }
            Result.success()
        }.getOrElse { Result.success() } // never fail the chain on a consolidation hiccup
    }

    companion object {
        private const val TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
        private const val CAP = 200
        private const val UNIQUE_WORK_NAME = "aura_memory_consolidation"

        /**
         * M2 — schedule the daily pass. Idempotent (unique-name + KEEP), mirroring
         * [com.aura.aura_ui.mcp.log.McpLogCleanupWorker.schedule]; call once at
         * service boot next to that worker's schedule call.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MemoryConsolidationWorker>(
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
