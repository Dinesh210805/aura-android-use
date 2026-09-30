package com.aura.aura_ui.accessibility

/**
 * UI Tree validation result.
 */
data class UITreeValidationResult(
    val isValid: Boolean,
    val reason: String? = null,
    val nodeCount: Int = 0,
    val validBoundsRatio: Float = 0f,
    val packageName: String? = null,
)

/**
 * UI Tree validator - rejects garbage trees before sending to backend.
 *
 * Rejection is judged on the TREE — node count and bounds sanity — never on the
 * package name.
 *
 * ### Why the package list is gone
 *
 * This object used to reject Google Maps, every camera app, and anything matching
 * `.*\.game\..*` outright. Rejection sets `validation_failed=true`, which makes
 * `UiTreeToElements.extract` return nothing, which forces the full CV pass — so
 * those apps could never use their accessibility tree at all, however good it was.
 *
 * It is only the *canvas surface* of Maps that is opaque; its search bar, layer
 * buttons and directions panel are ordinary labeled Android views, and the same
 * goes for a camera's shutter and mode controls. The regexes were broad enough to
 * catch unrelated packages too (`.*\.game\..*` matches any app with that path
 * segment).
 *
 * The question "can the agent drive this screen from the tree?" is already
 * measured directly, per screen, by `TreeSufficiency` (element count, labeled
 * fraction, overlap, empty-band coverage), and the model can force the visual pass
 * itself with `detail="full"`. Those measure; a package list guessed — and a real
 * canvas game fails the measurement anyway, because it exposes one node and nothing
 * else.
 */
object UITreeValidator {
    private const val MIN_NODE_COUNT = 3
    private const val MIN_VALID_BOUNDS_RATIO = 0.1f

    /**
     * Validate UI tree before sending to backend.
     *
     * @param elements List of UI elements
     * @param packageName Current app package name — reported back for diagnostics,
     *   never used to decide validity.
     * @return Validation result
     */
    fun validate(
        elements: List<UIElementData>,
        packageName: String?,
    ): UITreeValidationResult {
        // Check min node count
        if (elements.size < MIN_NODE_COUNT) {
            return UITreeValidationResult(
                isValid = false,
                reason = "Too few nodes: ${elements.size} < $MIN_NODE_COUNT",
                nodeCount = elements.size,
                validBoundsRatio = 0f,
                packageName = packageName,
            )
        }
        
        // Check valid bounds ratio
        val nodesWithValidBounds = elements.count { elem ->
            elem.bounds.width > 5 && elem.bounds.height > 5 &&
            elem.bounds.right > elem.bounds.left &&
            elem.bounds.bottom > elem.bounds.top
        }
        
        val validBoundsRatio = if (elements.isNotEmpty()) {
            nodesWithValidBounds.toFloat() / elements.size
        } else {
            0f
        }
        
        if (validBoundsRatio < MIN_VALID_BOUNDS_RATIO) {
            return UITreeValidationResult(
                isValid = false,
                reason = "Too few valid bounds: ${(validBoundsRatio * 100).toInt()}% < ${(MIN_VALID_BOUNDS_RATIO * 100).toInt()}%",
                nodeCount = elements.size,
                validBoundsRatio = validBoundsRatio,
                packageName = packageName,
            )
        }
        
        // All checks passed
        return UITreeValidationResult(
            isValid = true,
            reason = null,
            nodeCount = elements.size,
            validBoundsRatio = validBoundsRatio,
            packageName = packageName,
        )
    }
    
    /**
     * Rough app category, from the package name alone.
     *
     * DIAGNOSTIC ONLY — it is reported in the tree payload so a human reading a
     * trace can see what kind of app a run was in. Nothing branches on it, and
     * nothing should: a name is not evidence about a tree. It is deliberately
     * fuzzy (`com.gamestop.shopping` reads as "game") because being wrong in a
     * log line costs nothing, whereas being wrong in a gate blinded the agent.
     */
    fun getAppCategory(packageName: String?): String {
        if (packageName == null) return "unknown"

        return when {
            packageName.contains("game", ignoreCase = true) -> "game"
            packageName.contains("camera", ignoreCase = true) -> "camera"
            packageName.contains("map", ignoreCase = true) -> "map"
            else -> "standard"
        }
    }
}
