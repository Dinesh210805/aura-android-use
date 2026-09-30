package com.aura.aura_ui.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What makes a UI tree unusable — and, more importantly, what does NOT.
 *
 * [UITreeValidator] used to reject whole apps by package name: Google Maps,
 * every camera app, and anything matching `.*\.game\..*` or `.*\.camera\..*`.
 * Rejection sets `validation_failed=true`, which makes `UiTreeToElements.extract`
 * return an empty list, which forces the full CV pass — so those apps could never
 * use their accessibility tree at all.
 *
 * That was too blunt. Only the *canvas surface* of Maps is opaque; its search bar,
 * layer buttons and directions panel are ordinary, well-labeled Android views. The
 * regexes were also broad enough to catch unrelated packages.
 *
 * Whether a tree is usable is a property of the TREE, and there are already runtime
 * heuristics that measure it directly (element count, labeled fraction, sibling
 * overlap, empty-band coverage in `TreeSufficiency`), plus the model's own
 * `detail="full"` lever. Those measure; a package list guesses.
 */
class UITreeValidatorTest {

    private fun element(id: Int, clickable: Boolean = true, label: String = "Item $id") =
        UIElementData(
            text = label,
            contentDescription = null,
            bounds = BoundsData(
                left = 0,
                top = id * 100,
                right = 400,
                bottom = id * 100 + 80,
                centerX = 200,
                centerY = id * 100 + 40,
                width = 400,
                height = 80,
            ),
            className = "android.widget.TextView",
            isClickable = clickable,
            isScrollable = false,
            isEditable = false,
            isEnabled = true,
            isFocused = false,
            actions = emptyList(),
            packageName = "com.example",
            viewId = null,
        )

    private fun tree(count: Int) = (1..count).map { element(it) }

    @Test
    fun `a healthy tree is valid`() {
        assertTrue(UITreeValidator.validate(tree(12), "com.whatsapp").isValid)
    }

    // ── Package name is not evidence about the tree ────────────────────────────

    @Test
    fun `Google Maps keeps its tree — the canvas is opaque, the chrome is not`() {
        val result = UITreeValidator.validate(tree(12), "com.google.android.apps.maps")
        assertTrue(
            "search bar / layers / directions are ordinary views: ${result.reason}",
            result.isValid,
        )
    }

    @Test
    fun `a camera app keeps its tree — shutter and mode controls are real views`() {
        assertTrue(UITreeValidator.validate(tree(12), "com.oneplus.camera").isValid)
    }

    @Test
    fun `a package merely containing the word game is not rejected`() {
        // The old regex `.*\.game\..*` swept in anything with that path segment.
        assertTrue(UITreeValidator.validate(tree(12), "com.gamestop.shopping").isValid)
        assertTrue(UITreeValidator.validate(tree(12), "com.acme.game.launcher").isValid)
    }

    @Test
    fun `a real game with a real canvas is rejected by its tree, not its name`() {
        // One node and nothing else: this is what a SurfaceView game actually
        // exposes, and it fails on COUNT — measured, not guessed.
        assertFalse(UITreeValidator.validate(tree(1), "com.supercell.clashofclans").isValid)
    }

    // ── Tree-shaped rejections must survive ────────────────────────────────────

    @Test
    fun `too few nodes is still rejected`() {
        val result = UITreeValidator.validate(tree(2), "com.whatsapp")
        assertFalse(result.isValid)
        assertTrue(result.reason.orEmpty().contains("Too few nodes"))
    }

    @Test
    fun `a tree of degenerate bounds is still rejected`() {
        val degenerate = (1..10).map {
            element(it).copy(
                bounds = BoundsData(0, 0, 0, 0, 0, 0, 0, 0),
            )
        }
        val result = UITreeValidator.validate(degenerate, "com.whatsapp")
        assertFalse(result.isValid)
        assertTrue(result.reason.orEmpty().contains("valid bounds"))
    }

    @Test
    fun `a null package is fine — validity is judged on the tree`() {
        assertTrue(UITreeValidator.validate(tree(12), null).isValid)
    }

    // ── Category stays informational ──────────────────────────────────────────

    @Test
    fun `category still describes the app by name for diagnostics`() {
        assertEquals("game", UITreeValidator.getAppCategory("com.acme.game.launcher"))
        assertEquals("camera", UITreeValidator.getAppCategory("com.oneplus.camera"))
        assertEquals("map", UITreeValidator.getAppCategory("com.google.android.apps.maps"))
        assertEquals("standard", UITreeValidator.getAppCategory("com.whatsapp"))
        assertEquals("unknown", UITreeValidator.getAppCategory(null))
    }

    @Test
    fun `no category is a verdict on the tree`() {
        // The label is diagnostic only. Whatever it says, a healthy tree stays
        // valid — that is the whole point of deleting the package gate.
        for (pkg in listOf("com.acme.game.x", "com.oneplus.camera", "com.google.android.apps.maps")) {
            assertTrue(pkg, UITreeValidator.validate(tree(12), pkg).isValid)
        }
    }
}
