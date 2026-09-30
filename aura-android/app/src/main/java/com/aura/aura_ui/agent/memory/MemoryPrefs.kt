package com.aura.aura_ui.agent.memory

import android.content.Context

/** Plain on/off toggle for the learnings feature (default ON -- accumulating learnings is the point). */
class MemoryPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("aura_memory_prefs", Context.MODE_PRIVATE)
    var learningsEnabled: Boolean
        get() = prefs.getBoolean(KEY_LEARN, true)
        set(v) { prefs.edit().putBoolean(KEY_LEARN, v).apply() }

    private companion object { const val KEY_LEARN = "learnings_enabled" }
}
