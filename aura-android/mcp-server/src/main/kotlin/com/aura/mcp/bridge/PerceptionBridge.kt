package com.aura.mcp.bridge

/**
 * Port for AI-driven UI-element detection — Layer 2 of the perception pipeline.
 *
 * Phase 5 wires this to an on-device YOLOv8 (Microsoft OmniParser
 * `icon_detect`, quantized ONNX, ~20MB) plus ML Kit Text Recognition for
 * label resolution. The contract is intentionally generic so the
 * implementation can swap in a different model later without breaking
 * tools.
 *
 * **The bridge produces pixel-precise [BBox]es from a CV model — never from
 * a VLM.** This preserves the project-wide invariant: VLMs may pick among
 * numbered elements, but they never generate raw coordinates.
 */
interface PerceptionBridge {
    /**
     * Detect interactive UI elements in [pngBytes].
     *
     * @param pngBytes  Raw PNG bytes (NOT base64) — typically obtained from
     *                  [ScreenshotBridge.captureBase64Png] decoded.
     * @param withOcr   If true, run an OCR pass and overlay text on each
     *                  bbox's label. If false, every label is the raw class
     *                  name (e.g. "icon"). The `omniparser_detect` MCP tool
     *                  uses false; `perceive_screen` uses true.
     * @param withAnnotatedImage  If true, also produce a PNG with numbered
     *                  red boxes overlayed for the `get_annotated_screenshot`
     *                  tool. Adds a ~50-150ms canvas pass.
     */
    suspend fun detectElements(
        pngBytes: ByteArray,
        withOcr: Boolean = true,
        withAnnotatedImage: Boolean = false,
    ): PerceptionResult

    /**
     * Phase 10 — render numbered SoM boxes over [pngBytes] using
     * **externally-supplied** element groups (not the CV model's output).
     *
     * Each [AnnotationGroup] is drawn in its own color onto a SINGLE image,
     * so `perceive_screen` can emit one annotated screenshot whose box color
     * tells the agent each element's source: UI-tree bounds (factually
     * accurate, read from the accessibility system) vs OmniParser bounds
     * (a model output). Decoupling annotation from detection lets the tool
     * layer choose both its source of truth and the color legend.
     *
     * @param maxLongSidePx if > 0 and smaller than the screenshot's long edge,
     *   the OUTPUT IMAGE is downscaled to that long side (box geometry scaled to
     *   match). This shrinks the returned payload without touching the element
     *   coordinates the tool reports — the image is for the model's eyes, the
     *   JSON coords stay full-resolution for the device's finger. 0 = no scaling.
     * @return base64-encoded PNG with overlays, or null on draw failure.
     */
    suspend fun drawAnnotations(
        pngBytes: ByteArray,
        groups: List<AnnotationGroup>,
        maxLongSidePx: Int = 0,
    ): String?
}

/**
 * A set of [elements] to annotate in one [colorArgb]. The tool layer assigns
 * the color by source (e.g. blue = ui_tree, red = omniparser) so a single
 * combined image is self-describing.
 */
data class AnnotationGroup(
    val elements: List<DetectedElement>,
    val colorArgb: Int,
)

/** Result of one detection pass. */
data class PerceptionResult(
    val ok: Boolean,
    val elements: List<DetectedElement>,
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    /** Set only when `withAnnotatedImage = true`. Base64 PNG with numbered overlays. */
    val annotatedBase64Png: String? = null,
    val error: String? = null,
    /** Per-stage timings in ms (e.g. "yolo", "ocr") for diagnostics. Empty if untimed. */
    val timingsMs: Map<String, Long> = emptyMap(),
    /** Name of the ONNX execution provider that served this detection (e.g.
     * "XNNPACK"/"NNAPI"/"CPU"), or null if unknown. Diagnostic only. */
    val engine: String? = null,
)

/** One detected UI element with Set-of-Marks number, bbox, and label. */
data class DetectedElement(
    /** 1-based SoM number. Matches the numbered overlay in [PerceptionResult.annotatedBase64Png]. */
    val somId: Int,
    val bbox: BBox,
    /** Raw class name from the CV model. Always non-empty (e.g. "icon"). */
    val elementType: String,
    /** OCR-resolved text if available, otherwise empty. Set only when `withOcr = true`. */
    val label: String,
    /** CV detection confidence, 0..1. */
    val confidence: Float,
    /**
     * Toggle state for checkable widgets (Switch / CheckBox / RadioButton /
     * ToggleButton, and Compose's `Modifier.toggleable`). `null` means "not a
     * checkable thing" — deliberately not `false`, so the agent can tell
     * "this is off" apart from "this has no on/off state".
     *
     * Without this the agent cannot distinguish a Wi-Fi row that is already on
     * from one that is off, and turns it off while trying to turn it on.
     */
    val checked: Boolean? = null,
    /**
     * False for a control that is present but greyed out. Kept as an element
     * rather than filtered away for two reasons: the agent needs to reason
     * "the control exists but is unavailable — satisfy the prerequisite first",
     * and dropping it on-device just let the OmniParser pass OCR the same
     * pixels back as an untagged element with fuzzier bounds.
     */
    val enabled: Boolean = true,
    /**
     * Text can be typed into this element — the agent must use `type_text`, not `tap`.
     *
     * Carried explicitly rather than inferred from [elementType] because getting it
     * wrong fails SILENTLY: an empty text field and a plain box look identical in a
     * screenshot, so a model that taps one sees nothing happen and no error, and has no
     * way to learn what it got wrong. Contrast a mis-picked scroll target, which the
     * agent notices immediately.
     */
    /**
     * The accessibility tree claims this node can be acted on (clickable / scrollable /
     * editable / checkable / long-clickable, or a dispatchable click action).
     *
     * False does NOT mean "cannot be tapped" — apps routinely omit the flag on real
     * targets, which is why such nodes are still emitted. It means "the app did not say
     * so", and it is what makes a box grey instead of blue.
     *
     * A separate field rather than a value of [elementType], because elementType carries
     * the Android class name whenever one is present ("TextView", "FrameLayout") and so
     * cannot also encode this. Inferring it from a blank label was the first attempt and
     * was wrong: a passive section header like "Saved networks" has a perfectly good
     * label and is still not a target.
     *
     * Defaults true so CV detections — which are only ever produced for things that look
     * actionable — keep their previous meaning.
     */
    val interactive: Boolean = true,
    val editable: Boolean = false,
    /** Scroll target rather than a tap target. */
    val scrollable: Boolean = false,
    /** Responds to a long press (context menus, drag handles). */
    val longClickable: Boolean = false,
    /** Currently holds input focus — relevant when deciding whether to type now. */
    val focused: Boolean = false,
    /**
     * A container/scroll host rather than a target: tapping it lands somewhere
     * arbitrary inside. Flagged rather than dropped — it is a real scroll target, and
     * the model may legitimately want the whole region.
     */
    val wrapper: Boolean = false,
)

data class BBox(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
) {
    val centerX: Int get() = (x1 + x2) / 2
    val centerY: Int get() = (y1 + y2) / 2
    val width: Int get() = x2 - x1
    val height: Int get() = y2 - y1
    val area: Int get() = width * height
}
