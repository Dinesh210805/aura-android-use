package com.aura.aura_ui.agent.conversation

import android.content.Context
import com.aura.aura_ui.agent.AuraAgent
import com.aura.aura_ui.agent.ledger.RunLedgerStore
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.mcp.bridge.AppBrowserBridge
import com.aura.aura_ui.mcp.log.LiveSessionScope

/**
 * Prod PhoneTaskRunner: hands the task to the EXISTING Koog action agent. Because it calls
 * runFromSavedSettings, every gesture still passes the #1 hook chain + SensitivePolicy -- the
 * conversation plane gains no privilege. Stream events become spoken-progress stage strings.
 */
class AuraAgentPhoneRunner(private val context: Context) : PhoneTaskRunner {
    /**
     * Wrapped in [LiveSessionScope.expectRun] so the run's trace lands in the conversation's
     * session: the words that asked for the task and the taps that carried it out belong to one
     * story, and answering "did the phone do what was actually asked?" needs both on one clock.
     */
    override suspend fun run(task: String, onProgress: (String) -> Unit): String =
        minimizedBrowser {
            LiveSessionScope.expectRun {
                AuraAgent(context).runFromSavedSettings(task) { onProgress(it.toProgress()) }
            }
        }

    /**
     * Build 3: continue the most recent interrupted run, or null if there's nothing resumable.
     * Routes through AuraAgent.resumeRun → runSpike → the same hook chain + SensitivePolicy.
     */
    override suspend fun resumeLatest(onProgress: (String) -> Unit): String? {
        val store = RunLedgerStore(EncryptedJsonStore(context, RunLedgerStore.STORE_NAME))
        val resumable = store.latestResumable() ?: return null
        return minimizedBrowser {
            LiveSessionScope.expectRun {
                AuraAgent(context).resumeRun(resumable.runId) { onProgress(it.toProgress()) }
            }
        }
    }

    /**
     * Web lookups asked for by voice ("what's in the news") run in AURA's own browser as the
     * minimized bubble, so the conversation carries on over whatever the user was doing.
     */
    private suspend fun <T> minimizedBrowser(block: suspend () -> T): T {
        val browser = AppBrowserBridge.shared(context)
        browser.openMinimized = true
        return try {
            block()
        } finally {
            browser.openMinimized = false
        }
    }
}
