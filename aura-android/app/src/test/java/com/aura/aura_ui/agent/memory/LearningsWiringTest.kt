package com.aura.aura_ui.agent.memory

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LearningsWiringTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test fun `no learnings yields null hint (today's path preserved)`() = runTest {
        MemoryPrefs(ctx).learningsEnabled = true
        assertNull(attachLearningsHintsIfAny(ctx, "open some brand new app"))
    }

    @Test fun `disabled learnings yields null write-hook`() = runTest {
        MemoryPrefs(ctx).learningsEnabled = false
        assertNull(attachLearningsWriteHook(ctx, "open spotify"))
    }

    @Test fun `enabled learnings yields a write-hook`() = runTest {
        MemoryPrefs(ctx).learningsEnabled = true
        assertNotNull(attachLearningsWriteHook(ctx, "open spotify"))
    }

    @Test fun `a recorded path surfaces as a hint for the same goal`() = runTest {
        MemoryPrefs(ctx).learningsEnabled = true
        // Two steps: one-step paths are deliberately never served (MIN_PATH_STEPS).
        EncryptedLearningsStore(EncryptedJsonStore(ctx, "aura_learnings"))
            .recordVerifiedPath("com.spotify.music", "play_media", listOf("tap \"Library\"", "tap \"Liked Songs\""))
        val hint = attachLearningsHintsIfAny(ctx, "play music on spotify")
        assertNotNull(hint)
    }
}
