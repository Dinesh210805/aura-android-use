package com.aura.aura_ui.presentation.screens.agent

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/** Opening Settings → Tools must render, not crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolsScreenTest {
    @Test
    fun `the tools screen opens`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { ToolsScreen(onNavigateBack = {}) }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
