package com.aura.aura_ui.agent.skills

import android.content.Context

/**
 * Loads first-party skills shipped under `assets/skills/` as `.md` files (BUNDLED = trusted). The asset I/O is
 * injected so the parse/skip logic unit-tests without Android; [fromAssets] wires the real reader.
 */
class BundledSkillSource(
    private val listFiles: () -> List<String>,
    private val read: (String) -> String?,
) : SkillSource {
    override suspend fun load(): List<Skill> =
        listFiles().mapNotNull { f ->
            read(f)?.let { SkillFrontmatterParser.parse(it, SkillTrust.BUNDLED, "bundled") }
        }

    companion object {
        private const val DIR = "skills"
        fun fromAssets(context: Context): BundledSkillSource {
            val assets = context.assets
            return BundledSkillSource(
                listFiles = { runCatching { assets.list(DIR)?.toList() }.getOrNull().orEmpty() },
                read = { f -> runCatching { assets.open("$DIR/$f").bufferedReader().use { it.readText() } }.getOrNull() },
            )
        }
    }
}
