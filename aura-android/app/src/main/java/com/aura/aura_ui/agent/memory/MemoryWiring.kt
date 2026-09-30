package com.aura.aura_ui.agent.memory

import android.content.Context
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore

/** The one on-disk name for conversation memory — was duplicated at every construction site. */
const val MEMORY_STORE = "aura_memory"

/**
 * M8 — memory holds user-derived PII, so the store is fail-closed: on a device whose Keystore is
 * broken it refuses to persist plaintext. That failure is silent by design at the storage layer,
 * which is why the Memory screen surfaces [EncryptedJsonStore.isEncrypted] — otherwise the only
 * symptom is "AURA forgot my name" with nothing to point at.
 */
fun memoryStore(context: Context): EncryptedJsonStore =
    EncryptedJsonStore(context.applicationContext, MEMORY_STORE, failClosedWhenUnencrypted = true)

fun memoryService(context: Context): EncryptedMemoryService =
    EncryptedMemoryService(memoryStore(context)).also { svc ->
        val app = context.applicationContext
        svc.onRemind = { ReminderScheduler.schedule(app, it) }
        svc.embedder = GeminiEmbedder({ ProviderKeyStore(app).getKey(LlmEndpointCatalog.GEMINI_ID) })
    }

/**
 * Name + languages for the SYSTEM prompt. Human-authored in Settings, so unlike memory and learned
 * hints it carries no injection surface and belongs at system authority — that is what makes
 * "only speak the languages they listed" a rule rather than a suggestion.
 *
 * Fail-soft: any error → "" → the prompt is byte-for-byte what it was before this existed.
 */
internal fun profileBlockOrEmpty(context: Context): String =
    runCatching { ProfileBlock.render(UserProfileStore(context).load()) }.getOrDefault("")

/**
 * The fenced remembered-context block for a run, or null when memory has nothing non-sensitive to
 * say. Rides the USER channel next to the goal — see [AgentMemoryContext.wrap].
 */
internal fun agentMemoryBlockOrNull(memory: EncryptedMemoryService): String? =
    runCatching { AgentMemoryContext.forRun(memory.allEntries()) }.getOrNull()
