package com.aura.aura_ui.accessibility

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural regression guard for the cross-device coordinate bug.
 *
 * The automation pipeline has three consumers of "how big is the screen":
 * the UI-tree extractor (coords reported to the agent), the coordinate resolver
 * (gesture clamping) and the screen-capture manager (VirtualDisplay the CV model
 * sees). They MUST agree, because `dispatchGesture()` and the screenshot must
 * share one coordinate space.
 *
 * They did not. Two of them read `resources.displayMetrics`, which on a Service
 * context at API 30+ can exclude system-decor insets by a device-dependent
 * amount — producing a scaled screenshot and clamped bottom-of-screen taps on
 * every device where that amount is non-zero, and nothing at all on a device
 * where it is zero.
 *
 * A behavioural test can't catch a re-introduction: it only manifests on
 * hardware with a non-zero inset delta. So this guards the *source* instead —
 * screen width/height may only be read through [ScreenGeometry].
 */
class ScreenGeometrySourceTest {

    private val mainSrc = File("src/main/java/com/aura/aura_ui")

    /** Files legitimately allowed to read raw pixel dimensions from Resources. */
    private val allowed = setOf(
        // The one implementation everyone else delegates to.
        "ScreenGeometry.kt",
    )

    private val forbidden = Regex(
        """displayMetrics\s*\.\s*(widthPixels|heightPixels)|""" +
            """(getRealMetrics|getRealSize)\s*\(""",
    )

    @Test
    fun `screen pixel dimensions are read only through ScreenGeometry`() {
        assertTrue(
            "test must run from the :app module directory; got ${mainSrc.absolutePath}",
            mainSrc.isDirectory,
        )

        val offenders = mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in allowed }
            .filter { file ->
                file.readLines()
                    // Ignore prose: these files explain the bug in comments.
                    .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                    .any { forbidden.containsMatchIn(it) }
            }
            .map { it.relativeTo(mainSrc).path }
            .toList()

        assertEquals(
            "These files read screen pixel size directly. Use " +
                "ScreenGeometry.realSizePx(context) instead — see its KDoc for why " +
                "resources.displayMetrics is wrong for gesture/screenshot coordinates.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Every consumer of screen size must route through the one helper. Listing
     * them explicitly means adding a new one without wiring it up shows up here
     * rather than as a device-specific tap offset in the field.
     */
    @Test
    fun `all screen-size consumers delegate to ScreenGeometry`() {
        val consumers = listOf(
            "accessibility/UITreeExtractor.kt",
            "accessibility/ScreenCaptureManager.kt",
            "accessibility/gesture/CoordinateResolver.kt",
            "mcp/bridge/AppDeviceBridge.kt",
            "overlay/AuraOverlayService.kt",
        )
        consumers.forEach { rel ->
            val file = File(mainSrc, rel)
            assertTrue("$rel not found — did it move?", file.isFile)
            assertTrue(
                "$rel must obtain screen size via ScreenGeometry.realSizePx()",
                file.readText().contains("ScreenGeometry.realSizePx"),
            )
        }
    }

    @Test
    fun `ScreenGeometry itself uses whole-display bounds`() {
        val impl = File(mainSrc, "accessibility/ScreenGeometry.kt").readText()
        assertTrue(
            "must read the display, not the app window",
            impl.contains("maximumWindowMetrics"),
        )
    }
}
