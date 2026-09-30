package com.aura.aura_ui.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pins the agent's system prompt — [AuraAgent.SYSTEM_PROMPT], the opening line plus the tagged
 * doctrine sections — against a committed fixture. (Profile and device blocks are appended per
 * install and are not part of the fixture.)
 *
 * `contains` assertions pass while a section is silently dropped, reordered or loses a newline;
 * a byte check does not.
 *
 * ### When this test fails
 *
 * The prompt changed. If that was deliberate, regenerate with
 *
 * ```
 * GOLDEN_UPDATE=1 ./gradlew :app:testDebugUnitTest --tests '*IdentityPromptGoldenTest*'
 * ```
 *
 * and **read the diff before committing it**. An environment variable rather than `-D`, because
 * Gradle forks the test JVM without the launcher's system properties.
 */
class IdentityPromptGoldenTest {

    private val golden = File("src/test/resources/golden/identity_prompt.txt")

    private val assembled: String get() = AuraAgent.SYSTEM_PROMPT

    @Test
    fun `the assembled identity prompt matches its committed fixture`() {
        if (System.getenv("GOLDEN_UPDATE") == "1") {
            golden.parentFile.mkdirs()
            golden.writeText(assembled)
            println("golden updated: ${golden.absolutePath} (${assembled.length} chars)")
            return
        }
        check(golden.isFile) {
            "missing fixture ${golden.path} — generate it with GOLDEN_UPDATE=1"
        }
        assertEquals(
            "The identity prompt changed. If that was deliberate, regenerate the fixture with " +
                "GOLDEN_UPDATE=1 and read the diff before committing.",
            // CRLF-normalised: a Windows checkout with core.autocrlf rewrites the fixture's endings.
            golden.readText().replace("\r\n", "\n"),
            assembled,
        )
    }

    @Test
    fun `the identity prompt stays under its token ceiling`() {
        val tokens = assembled.length / 4
        println("identity prompt ≈ $tokens tok (${assembled.length} chars)")
        assert(tokens <= CEILING_TOKENS) {
            "identity prompt grew to ~$tokens tok, ceiling is $CEILING_TOKENS. " +
                "Every request pays this. Move situational rules to their tool or result first."
        }
    }

    private companion object {
        /** 2026-09-23 rewrite: ~1,190 tok (was 2,868 incl. the capabilities paragraph). */
        const val CEILING_TOKENS = 1_300
    }
}
