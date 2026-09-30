package com.aura.aura_ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aura.aura_ui.utils.AgentLogger
import java.util.Locale

class UITreeExtractor(private val service: AccessibilityService) {
    
    /**
     * AURA's own package, so its overlays can be filtered out of the perceived tree.
     *
     * Read from the running service rather than listed as constants. The list this
     * replaced held the Gradle *namespace* plus the debug applicationId, and was missing
     * the release applicationId entirely — so in release builds AURA perceived its own
     * floating overlay as part of the app it was driving. See [OwnAppDetector].
     */
    private val ownPackage: String get() = service.packageName

    companion object {
        // Depth cap exists only to stop runaway/cyclic trees. It must comfortably
        // exceed real hierarchies: Compose/React-Native apps routinely nest 20-32
        // levels (measured: Swiggy home = 32 deep, search bar at 19 — the old cap
        // of 15 silently dropped the entire content area and every editable field).
        private const val MAX_DEPTH = 60

        // Payload cap, applied to the FINISHED list by [ElementBudget] — never
        // mid-walk. Stopping the recursion at N elements drops whole regions of
        // the screen, because a depth-first walk fills its budget from one
        // subtree; see ElementBudget's KDoc. Truncation is REPORTED to callers
        // via [Extraction] — never silent.
        private const val MAX_ELEMENTS = 800

        // Anti-runaway ceiling for the walk itself. Not a payload budget: it
        // exists only so a cyclic or pathological tree cannot spin forever, and
        // sits far above any real screen (measured: ~200-250 raw nodes, dense
        // list views a few hundred more).
        private const val WALK_CEILING = 4000

        /**
         * Caps on the off-screen text channel. It is unbounded in principle — a long
         * feed can hold thousands of collapsed nodes — and it is the one part of the
         * payload with no geometry to make it self-limiting. 200 strings covers every
         * carousel measured (Amazon home produced 138) while bounding the worst case.
         */
        private const val MAX_OFFSCREEN_TEXT = 200
        private const val MAX_OFFSCREEN_TEXT_LEN = 120
    }

    /** Result of a tree walk plus honesty flags about what the walk could not see. */
    data class Extraction(
        val elements: List<UIElementData>,
        val truncatedByDepth: Boolean,
        val truncatedByCount: Boolean,
    ) {
        val truncated: Boolean get() = truncatedByDepth || truncatedByCount
    }

    private class WalkStats {
        var depthHit = false
        var countHit = false
    }

    fun getUIElements(): List<UIElementData> = extract().elements

    /**
     * Screen size for this walk, read ONCE per extraction rather than per node —
     * [ScreenGeometry] queries the display, and a dense tree visits thousands of
     * nodes. Re-read every extraction so a rotation is picked up.
     */
    private var walkScreenW = 0
    private var walkScreenH = 0

    /**
     * Text from nodes whose rect collapsed — content that exists but is not on screen.
     * A LinkedHashSet: the same carousel string appears on several nested nodes, and
     * reading order is worth preserving. Reset per extraction.
     */
    private val offscreenText = LinkedHashSet<String>()

    fun extract(): Extraction {
        val elements = mutableListOf<UIElementData>()
        val stats = WalkStats()
        screenSizePx().let { (w, h) -> walkScreenW = w; walkScreenH = h }
        offscreenText.clear()

        try {
            // EVERY window that makes up what the user is looking at, topmost first —
            // not just the app underneath. A dialog, popup menu or autocomplete
            // dropdown is its own window, and walking only one of them is why the
            // agent used to perceive the screen BEHIND a dialog and tap through it.
            val roots = resolveRoots()
            if (roots.isNotEmpty()) {
                for (root in roots) {
                    extractUIElements(root, elements, parentNode = null, stats = stats)
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            } else {
                // No window list (pre-Lollipop, or the framework refused it).
                val activeRoot = service.rootInActiveWindow
                if (activeRoot != null) {
                    extractUIElements(activeRoot, elements, parentNode = null, stats = stats)
                    @Suppress("DEPRECATION")
                    activeRoot.recycle()
                }
            }
        } catch (e: Exception) {
            AgentLogger.UI.e("Error extracting UI elements", e)
        }

        // Cap the FINISHED list, by usefulness rather than by walk order.
        val budgeted = ElementBudget.trim(elements, MAX_ELEMENTS)
        if (ElementBudget.wasTrimmed(elements, MAX_ELEMENTS)) stats.countHit = true

        if (stats.depthHit || stats.countHit) {
            AgentLogger.UI.w(
                "UI tree truncated",
                mapOf(
                    "byDepth" to stats.depthHit.toString(),
                    "byCount" to stats.countHit.toString(),
                    "elements" to budgeted.size.toString(),
                    "rawElements" to elements.size.toString(),
                ),
            )
        }
        return Extraction(
            elements = budgeted,
            truncatedByDepth = stats.depthHit,
            truncatedByCount = stats.countHit,
        )
    }
    
    /**
     * Roots for every window that makes up the current screen, topmost first.
     *
     * Ordering and eligibility live in [WindowRootSelection] — a pure function with
     * its own tests, because the bug this replaced was never in the tree walk but in
     * this decision: the old code took whichever `TYPE_APPLICATION` window the
     * framework happened to list first and stopped, so dialogs were invisible and, in
     * split-screen, the background app could win.
     *
     * Roots are fetched BEFORE selection because [WindowRootSelection] needs each
     * window's package to exclude AURA's own overlay, and only the root carries it.
     * Every root that does not make the cut is recycled here.
     */
    private fun resolveRoots(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()

        return try {
            val windows: List<AccessibilityWindowInfo> = service.windows.orEmpty()
            if (windows.isEmpty()) {
                AgentLogger.UI.d("No windows available, falling back to active window")
                return emptyList()
            }

            val activeWindowId = service.rootInActiveWindow?.windowId
                ?: WindowRootSelection.UNDEFINED_WINDOW_ID

            // id -> root, for the windows that actually yielded one.
            val rootsById = LinkedHashMap<Int, AccessibilityNodeInfo>()
            val descriptors = mutableListOf<WindowRootSelection.WindowDescriptor>()
            for (window in windows) {
                val root = window.root ?: continue
                if (rootsById.containsKey(window.id)) {
                    @Suppress("DEPRECATION")
                    root.recycle()
                    continue
                }
                rootsById[window.id] = root
                // Bounds let the selector drop a window a fullscreen dialog has
                // covered. An unreadable rect stays empty, meaning "unknown".
                val rect = Rect()
                runCatching { window.getBoundsInScreen(rect) }
                descriptors += WindowRootSelection.WindowDescriptor(
                    id = window.id,
                    type = window.type,
                    layer = window.layer,
                    packageName = root.packageName?.toString().orEmpty(),
                    bounds = WindowRootSelection.Bounds(
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                    ),
                )
            }

            val chosen = WindowRootSelection.select(descriptors, activeWindowId, ownPackage)
            AgentLogger.UI.d("📱 ${windows.size} windows, walking ${chosen.size}: $chosen")

            // Recycle the roots we are not walking — they were only fetched to read
            // their package name.
            for ((id, root) in rootsById) {
                if (id !in chosen) {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
            chosen.mapNotNull { rootsById[it] }
        } catch (e: Exception) {
            AgentLogger.UI.e("Error getting windows, falling back to active window", e)
            emptyList()
        }
    }

    private fun extractUIElements(
        node: AccessibilityNodeInfo?,
        elements: MutableList<UIElementData>,
        currentDepth: Int = 0,
        maxDepth: Int = MAX_DEPTH,
        // Anti-runaway only. The payload budget is applied to the finished list
        // by [ElementBudget]; stopping the WALK here would drop whole regions of
        // the screen, which is precisely the bug that motivated the split.
        maxElements: Int = WALK_CEILING,
        parentNode: AccessibilityNodeInfo? = null,
        stats: WalkStats? = null,
    ) {
        if (node == null) return
        if (currentDepth > maxDepth) {
            stats?.depthHit = true
            return
        }
        if (elements.size >= maxElements) {
            stats?.countHit = true
            return
        }

        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            // Smart bounds handling: Use parent bounds if current node has text but 0x0 size
            val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            val hasZeroSize = bounds.width() == 0 || bounds.height() == 0
            
            val finalBounds = if (hasZeroSize && hasText && parentNode != null) {
                // Try to use parent's bounds if child has text but no size
                val parentBounds = Rect()
                parentNode.getBoundsInScreen(parentBounds)
                if (parentBounds.width() > 5 && parentBounds.height() > 5) {
                    AgentLogger.UI.d("📍 Using parent bounds for text element: '${node.text ?: node.contentDescription}'")
                    parentBounds
                } else {
                    bounds
                }
            } else {
                bounds
            }
            
            // A node earns a place by its RECT, never by carrying text.
            //
            // The rule here used to be "valid bounds OR (has text AND no size AND has
            // a parent)". The second arm reads as a rescue for labels whose own rect is
            // 0x0, but the borrow above has already handled that case: a successful
            // borrow returns a parent rect >5x5, which satisfies the first arm on its
            // own. So the second arm could only ever fire when the borrow FAILED — i.e.
            // it admitted nodes whose rect was still degenerate, and nothing else.
            //
            // On a carousel that is exactly the scrolled-away cards: Android clips them
            // to zero area at the viewport edge, parent and child together, so the
            // borrow fails and 170 of 331 nodes on the Amazon home screen arrived with
            // rects like [1241,840,1241,1085] — labeled, invisible, untappable. See
            // [VisibleBounds] for why the discriminator must be geometry.
            val shouldInclude = VisibleBounds.isVisible(
                finalBounds.left, finalBounds.top, finalBounds.right, finalBounds.bottom,
                walkScreenW, walkScreenH,
            ) && finalBounds.width() > 5 && finalBounds.height() > 5

            // Rejected for geometry, but it still SAYS something. A collapsed rect means
            // "not here", not "not real": on the Amazon home screen these 170 nodes held
            // 138 distinct strings — the entire carousel the user can scroll to
            // ("Starting ₹31,999*", "8000mAh battery", "Up to 45% off"). Dropping the
            // box is right; dropping the words threw away the only description of what
            // is one swipe away, and left the agent unable to answer "what offers are
            // there" without scrolling blind.
            //
            // No coordinates are kept, deliberately. These have none that mean anything,
            // and inventing one — say, the carousel's visible rect — would put
            // off-screen text where the user can see it, which is worse than omitting
            // it. This is text, not a target.
            if (!shouldInclude && hasText) {
                val said = (node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString())?.trim()
                if (!said.isNullOrBlank() && said.length <= MAX_OFFSCREEN_TEXT_LEN &&
                    offscreenText.size < MAX_OFFSCREEN_TEXT
                ) {
                    offscreenText.add(said)
                }
            }

            if (shouldInclude) {
                val boundsData =
                    BoundsData(
                        left = finalBounds.left,
                        top = finalBounds.top,
                        right = finalBounds.right,
                        bottom = finalBounds.bottom,
                        centerX = finalBounds.centerX(),
                        centerY = finalBounds.centerY(),
                        width = finalBounds.width(),
                        height = finalBounds.height(),
                    )
                
                // Inherit clickability from parent if current node has text but not clickable
                val isClickable = node.isClickable || 
                                 (hasText && !node.isClickable && parentNode?.isClickable == true)

                // Extract available actions from node
                val actionsList = mutableListOf<String>()
                node.actionList?.forEach { action ->
                    when (action.id) {
                        AccessibilityNodeInfo.ACTION_CLICK -> actionsList.add("click")
                        AccessibilityNodeInfo.ACTION_LONG_CLICK -> actionsList.add("long_click")
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> actionsList.add("scroll_forward")
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> actionsList.add("scroll_backward")
                        AccessibilityNodeInfo.ACTION_SET_TEXT -> actionsList.add("set_text")
                        AccessibilityNodeInfo.ACTION_COPY -> actionsList.add("copy")
                        AccessibilityNodeInfo.ACTION_PASTE -> actionsList.add("paste")
                        AccessibilityNodeInfo.ACTION_CUT -> actionsList.add("cut")
                    }
                }
                
                val element =
                    UIElementData(
                        text = node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        bounds = boundsData,
                        className = node.className?.toString(),
                        isClickable = isClickable,
                        isScrollable = node.isScrollable,
                        isEditable = node.isEditable || node.className?.toString()?.contains("EditText") == true,
                        isEnabled = node.isEnabled,
                        isFocused = node.isFocused,
                        actions = actionsList,
                        packageName = node.packageName?.toString(),
                        viewId = node.viewIdResourceName,
                        // Toggle state. Read straight off the widget, never
                        // inherited from the parent: the ROW's checked state is
                        // meaningless, only the Switch's is real.
                        isCheckable = node.isCheckable,
                        isChecked = node.isChecked,
                        isLongClickable = node.isLongClickable,
                    )

                elements.add(element)
            }

            if (elements.size >= maxElements) {
                stats?.countHit = true
                return
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (elements.size >= maxElements) {
                    stats?.countHit = true
                    break
                }

                try {
                    val child = node.getChild(i)
                    extractUIElements(child, elements, currentDepth + 1, maxDepth, maxElements, node, stats)
                    @Suppress("DEPRECATION")
                    child?.recycle()
                } catch (e: Exception) {
                    AgentLogger.UI.d("Skipping unreadable child $i of ${node.className}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error extracting UI element", e)
        }
    }

    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                findNodesByTextRecursive(rootNode, text.lowercase(Locale.ROOT), matchingNodes)
            }
        } catch (e: Exception) {
            AgentLogger.UI.e("Error finding nodes by text", e)
        }
        return matchingNodes
    }

    private fun findNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        searchText: String,
        results: MutableList<AccessibilityNodeInfo>,
    ) {
        try {
            val nodeText = node.text?.toString()?.lowercase(Locale.ROOT)
            val nodeDesc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)

            if ((nodeText?.contains(searchText) == true) || (nodeDesc?.contains(searchText) == true)) {
                results.add(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    findNodesByTextRecursive(child, searchText, results)
                }
            }
        } catch (e: Exception) {
            AgentLogger.UI.e("Error in recursive text search", e)
        }
    }

    fun safeGetRootInActiveWindow(maxRetries: Int = 3): AccessibilityNodeInfo? {
        repeat(maxRetries) { attempt ->
            try {
                val root = service.rootInActiveWindow
                if (root != null) {
                    return root
                }
                if (attempt < maxRetries - 1) {
                    Thread.sleep(50)
                }
            } catch (e: Exception) {
                AgentLogger.Auto.d("Attempt ${attempt + 1}/$maxRetries to get root node failed: ${e.message}")
                if (attempt < maxRetries - 1) {
                    Thread.sleep(50)
                }
            }
        }
        AgentLogger.Auto.d("⚠️ Could not access rootInActiveWindow after $maxRetries attempts")
        return null
    }

    fun getUITree(): Map<String, Any>? {
        return try {
            val extraction = extract()
            val uiElements = extraction.elements
            val (packageName, _) = getCurrentApp()
            
            // Validate UI tree before returning
            val validation = UITreeValidator.validate(uiElements, packageName)
            
            if (!validation.isValid) {
                AgentLogger.UI.w("UI tree validation failed: ${validation.reason}")
                // Return validation failure info instead of null
                return mapOf(
                    "validation_failed" to true,
                    "validation_reason" to (validation.reason ?: "Unknown"),
                    "elements_count" to validation.nodeCount,
                    "valid_bounds_ratio" to validation.validBoundsRatio,
                    "package_name" to (packageName ?: ""),
                    "app_category" to UITreeValidator.getAppCategory(packageName),
                    "requires_vision" to true,
                    "timestamp" to System.currentTimeMillis(),
                )
            }
            
            val treeMap = mutableMapOf<String, Any>()

            val (screenW, screenH) = screenSizePx()
            treeMap["validation_failed"] = false
            treeMap["elements_count"] = uiElements.size
            treeMap["clickable_count"] = uiElements.count { it.isClickable }
            treeMap["scrollable_count"] = uiElements.count { it.isScrollable }
            treeMap["editable_count"] = uiElements.count { it.isEditable }
            // Screen dimensions let consumers judge tree COVERAGE — e.g. a tree
            // whose interactive elements are all clustered in a thin band while a
            // large region is empty is a hallmark of an app that hides its UI from
            // third-party accessibility services (perceive_screen uses this to
            // escalate to the vision tier). 0 when unavailable.
            treeMap["screen_width_px"] = screenW
            treeMap["screen_height_px"] = screenH
            // Content that exists but is not on screen — see the collection site in the
            // walk. No coordinates by design: these nodes have none that mean anything.
            if (offscreenText.isNotEmpty()) {
                treeMap["offscreen_text"] = offscreenText.toList()
            }
            treeMap["package_name"] = packageName ?: ""
            treeMap["app_category"] = UITreeValidator.getAppCategory(packageName)
            treeMap["valid_bounds_ratio"] = validation.validBoundsRatio
            // Honesty flag: a capped walk means elements are MISSING, not absent
            // from the screen — the agent must not conclude "no such element".
            treeMap["truncated"] = extraction.truncated
            if (extraction.truncated) {
                treeMap["truncation_reason"] = buildList {
                    if (extraction.truncatedByDepth) add("depth>$MAX_DEPTH")
                    if (extraction.truncatedByCount) add("elements>$MAX_ELEMENTS")
                }.joinToString("+")
            }

            val elementsData = uiElements.map { it.toTreeMap() }

            treeMap["elements"] = elementsData
            treeMap["timestamp"] = System.currentTimeMillis()

            AgentLogger.UI.d("UI tree validated: ${uiElements.size} elements, ${(validation.validBoundsRatio * 100).toInt()}% valid bounds")
            treeMap
        } catch (e: Exception) {
            AgentLogger.UI.e("Error getting UI tree", e)
            null
        }
    }

    /**
     * Real display size in pixels, or (0,0) if it can't be read.
     *
     * Delegates to [ScreenGeometry] so the size reported to the agent is the
     * SAME number CoordinateResolver clamps gestures against and
     * ScreenCaptureManager sizes the screenshot with. When these three drifted
     * apart, CV-derived taps were offset by the decor-inset difference on any
     * device where that difference was non-zero.
     */
    private fun screenSizePx(): Pair<Int, Int> = ScreenGeometry.realSizePx(service)

    fun getCurrentApp(): Pair<String, String> {
        return try {
            val rootNode = service.rootInActiveWindow
            val packageName = rootNode?.packageName?.toString() ?: ""
            val activityName = "unknown_activity"

            Pair(packageName, activityName)
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error getting current app info", e)
            Pair("", "")
        }
    }
}
