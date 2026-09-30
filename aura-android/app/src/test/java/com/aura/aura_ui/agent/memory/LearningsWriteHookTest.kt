package com.aura.aura_ui.agent.memory

import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningsWriteHookTest {
    private class FakeStore : LearningsStore {
        val recorded = mutableListOf<Triple<String, String, List<String>>>()
        val recordedRecoveries = mutableListOf<List<String>>()
        override suspend fun hintsFor(appPackage: String, goalType: String) = ""
        override suspend fun appHints(appPackage: String, installedAppVersion: String?) = ""
        override suspend fun recordVerifiedPath(
            appPackage: String,
            goalType: String,
            steps: List<String>,
            recoveries: List<String>,
            appVersion: String,
            source: String,
            goalLabel: String,
            endsAt: String,
        ) {
            recorded.add(Triple(appPackage, goalType, steps))
            if (recoveries.isNotEmpty()) recordedRecoveries.add(recoveries)
        }
        override suspend fun recordFacts(
            appPackage: String,
            kind: FactKind,
            texts: List<String>,
            appVersion: String,
            source: String,
        ) {
            if (kind == FactKind.RECOVERY) recordedRecoveries.add(texts)
        }
    }
    private val ctx = HookContext(confirm = { true })
    private fun ok() = CallToolResult(content = listOf(TextContent("ok")), isError = false)

    /** A gesture result carrying an E2 post_action_observation block. */
    private fun okWithObservation(screenChanged: Boolean, foregroundApp: String = "") = CallToolResult(
        content = listOf(
            TextContent("""{"success":true,"action":"tap"}"""),
            TextContent(
                """{"post_action_observation":{"settled":true,"screen_changed":$screenChanged,""" +
                    """"foreground_app":"$foregroundApp","element_count":10}}""",
            ),
        ),
        isError = false,
    )

    private suspend fun post(hook: LearningsWriteHook, tool: String, args: JsonObject, result: CallToolResult = ok()) =
        hook.onPostTool(tool, args, result, ctx)

    /** E6 — errored results reach the hook on its failure lane, like the chain routes them. */
    private suspend fun fail(hook: LearningsWriteHook, tool: String, args: JsonObject, result: CallToolResult) =
        hook.onPostToolFailure(tool, args, result, ctx)

    @Test fun `flushes the accumulated trail on an allowed end_session`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("play liked songs on spotify", store)
        post(hook, "launch_app", buildJsonObject { put("app", "spotify") })
        post(hook, "tap", buildJsonObject { put("som_id", "3"); put("label", "Library") })
        post(hook, "perceive_screen", buildJsonObject {})  // read-only -> no step
        post(hook, "end_session", buildJsonObject {})
        hook.onRunEnd("completed")
        assertEquals(1, store.recorded.size)
        val (app, goalType, steps) = store.recorded.single()
        assertEquals("com.spotify.music", app)
        assertEquals("play_media", goalType)
        assertTrue(steps.any { it.contains("Library") })
        assertTrue(steps.none { it.contains("perceive_screen") })
    }

    @Test fun `does not flush without end_session`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("open spotify", store)
        post(hook, "tap", buildJsonObject { put("som_id", "1") })
        // E6 RunEnd: teardown without an allowed end_session (cancelled/failed/budget
        // run) must never write — success-only stays structural.
        hook.onRunEnd("failed")
        assertTrue(store.recorded.isEmpty())
    }

    @Test fun `does not flush an empty trail on end_session`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("hello", store)
        post(hook, "end_session", buildJsonObject {})
        hook.onRunEnd("completed")
        assertTrue(store.recorded.isEmpty())
    }

    @Test fun `an errored end_session does not record`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("open spotify", store)
        post(hook, "tap", buildJsonObject { put("som_id", "1") })
        // E6: an errored end_session rides the failure lane and never verifies the run.
        fail(hook, "end_session", buildJsonObject {}, CallToolResult(content = listOf(TextContent("err")), isError = true))
        hook.onRunEnd("completed")
        assertTrue(store.recorded.isEmpty())
    }

    @Test fun `onRunEnd flushes exactly once`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("open spotify", store)
        post(hook, "tap", buildJsonObject { put("som_id", "1"); put("label", "Spotify") })
        post(hook, "tap", buildJsonObject { put("som_id", "2"); put("label", "Library") })
        post(hook, "end_session", buildJsonObject {})
        hook.onRunEnd("completed")
        hook.onRunEnd("completed")
        assertEquals(1, store.recorded.size)
    }

    // ── M7: a "verified path" must contain only steps that actually succeeded ──

    @Test fun `failed steps are excluded from the verified path`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("play liked songs on spotify", store)
        val err = CallToolResult(content = listOf(TextContent("tap failed")), isError = true)
        fail(hook, "tap", buildJsonObject { put("som_id", "1"); put("label", "Search") }, err) // dead end
        post(hook, "tap", buildJsonObject { put("som_id", "2"); put("label", "Library") })     // the real path
        post(hook, "tap", buildJsonObject { put("som_id", "3"); put("label", "Liked Songs") })
        post(hook, "end_session", buildJsonObject {})
        hook.onRunEnd("completed")
        assertEquals(1, store.recorded.size)
        val steps = store.recorded.single().third
        assertTrue("the successful step must be recorded", steps.any { it.contains("Library") })
        assertTrue("a failed gesture must never appear in a verified path", steps.none { it.contains("Search") })
    }

    // ── Recovery capture: dead ends followed by a working alternative are a lesson ──

    @Test fun `a failed step followed by a working alternative records a recovery`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("add iphone to cart on amazon", store)
        val err = CallToolResult(content = listOf(TextContent("not found after 3 scrolls")), isError = true)
        fail(hook, "scroll_to", buildJsonObject { put("direction", "down"); put("description", "Add to Cart") }, err)
        post(hook, "tap", buildJsonObject { put("som_id", "9"); put("label", "Add to cart") })
        post(hook, "end_session", buildJsonObject {})
        hook.onRunEnd("completed")
        val recoveries = store.recordedRecoveries.single()
        assertEquals(1, recoveries.size)
        assertTrue("recovery must name the dead end", recoveries.single().contains("scroll_to"))
        assertTrue("recovery must name what worked", recoveries.single().contains("Add to cart"))
    }

    @Test fun `a no-effect write becomes a dead end and is kept out of the path`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("search on amazon", store)
        // press_enter dispatched fine but the screen never changed — a silent dead end.
        post(hook, "press_enter", buildJsonObject {}, okWithObservation(screenChanged = false))
        post(hook, "tap", buildJsonObject { put("som_id", "7"); put("label", "iPhone 17 Pro suggestion") }, okWithObservation(screenChanged = true))
        post(hook, "tap", buildJsonObject { put("som_id", "9"); put("label", "Add to cart") }, okWithObservation(screenChanged = true))
        post(hook, "end_session", buildJsonObject {})
        hook.onRunEnd("completed")
        val steps = store.recorded.single().third
        assertTrue("no-effect steps must not pollute the verified path", steps.none { it.contains("press_enter") })
        val recoveries = store.recordedRecoveries.single()
        assertTrue("the silent dead end must become a recovery lesson", recoveries.single().contains("press_enter"))
        assertTrue(recoveries.single().contains("suggestion"))
    }

    // ── Spec 2026-07-17: recovery pairs carry their own mid-run evidence, so they
    //    persist even when the run never verified. Paths stay success-only. ──

    @Test fun `an unverified run still persists evidence-verified recoveries`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("search on amazon", store)
        post(hook, "press_enter", buildJsonObject {}, okWithObservation(screenChanged = false))
        post(hook, "tap", buildJsonObject { put("som_id", "7"); put("label", "Search suggestion") }, okWithObservation(screenChanged = true))
        hook.onRunEnd("failed") // no allowed end_session — run NOT verified
        assertTrue("no verified path may be written", store.recorded.isEmpty())
        assertEquals("the recovery lesson must survive", 1, store.recordedRecoveries.size)
    }

    // ── M4 (real fix): the E2 observation bundle carries the true foreground app ──

    @Test fun `foreground_app from the observation bundle drives attribution`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("add iphone 17 pro to cart", store) // goal names no known app
        post(
            hook, "tap", buildJsonObject { put("som_id", "3") },
            okWithObservation(screenChanged = true, foregroundApp = "in.amazon.mShop.android.shopping"),
        )
        post(
            hook, "tap", buildJsonObject { put("som_id", "4"); put("label", "Add to cart") },
            okWithObservation(screenChanged = true, foregroundApp = "in.amazon.mShop.android.shopping"),
        )
        post(hook, "end_session", buildJsonObject {})
        hook.onRunEnd("completed")
        assertEquals("in.amazon.mShop.android.shopping", store.recorded.single().first)
    }

    // ── M4: app attribution must sniff the arg names the tools actually use ──

    @Test fun `app attribution reads package_name as the tools send it`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("play liked songs", store)
        post(hook, "launch_app", buildJsonObject { put("package_name", "com.spotify.music") })
        post(hook, "tap", buildJsonObject { put("som_id", "3") })
        post(hook, "end_session", buildJsonObject {})
        hook.onRunEnd("completed")
        assertEquals("com.spotify.music", store.recorded.single().first)
    }

    @Test fun `app attribution reads app_name as lookup_app sends it`() = runTest {
        val store = FakeStore()
        val hook = LearningsWriteHook("play liked songs", store)
        post(hook, "lookup_app", buildJsonObject { put("app_name", "spotify") })
        post(hook, "tap", buildJsonObject { put("som_id", "3") })
        post(hook, "tap", buildJsonObject { put("som_id", "4"); put("label", "Library") })
        post(hook, "end_session", buildJsonObject {})
        hook.onRunEnd("completed")
        assertEquals("com.spotify.music", store.recorded.single().first)
    }
}
