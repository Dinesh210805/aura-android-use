package com.aura.mcp.bench

import com.aura.mcp.bridge.AnnotationGroup
import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.CaptureResult
import com.aura.mcp.bridge.ContactResolution
import com.aura.mcp.bridge.ContactsBridge
import com.aura.mcp.bridge.DeepLinkBridge
import com.aura.mcp.bridge.DeepLinkCatalog
import com.aura.mcp.bridge.FileSearchResult
import com.aura.mcp.bridge.FilesBridge
import com.aura.mcp.bridge.NoOpAuditLogger
import com.aura.mcp.bridge.OpenUriResult
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.PerceptionResult
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.UriResolution
import com.aura.mcp.bridge.WebSearchBridge
import com.aura.mcp.bridge.WebSearchResult
import com.aura.mcp.server.InertBrowserBridge
import com.aura.mcp.server.InertMediaBridge
import com.aura.mcp.server.InertNotificationBridge
import com.aura.mcp.server.InertSystemIntentBridge
import com.aura.mcp.server.McpServerBuilder
import com.aura.mcp.tools.ScreenPayload
import com.aura.mcp.tools.ScreenText
import com.aura.mcp.tools.UiTreeToElements
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** One tool reply as a scenario sees it. [json] is null when the reply is not a JSON object. */
internal data class Reply(val tool: String, val text: String, val isError: Boolean, val json: JsonObject?) {
    fun str(key: String): String? = (json?.get(key) as? JsonPrimitive)?.content
}

internal data class Check(val name: String, val ok: Boolean, val detail: String)

/** The phone at one moment, for the report: every visible box, and the touches since the last frame. */
internal data class Frame(val caption: String, val boxes: List<JsonObject>, val touches: List<TouchRecord>)

/**
 * One way to break a tool. [gap] names the planned change that fixes it when the scenario is
 * expected to fail today; a gap scenario that passes is reported as closed.
 */
internal class Scenario(
    val id: String,
    val group: String,
    val title: String,
    val gap: String? = null,
    val body: suspend Ctx.() -> Unit,
)

/**
 * What a scenario drives: a fresh [MockPhone] behind a fresh, fully built MCP server, so guard
 * state never leaks between scenarios.
 */
internal class Ctx(val phone: MockPhone) {
    private val server: Server = buildServer(phone)
    val transcript = mutableListOf<Reply>()
    val checks = mutableListOf<Check>()
    val frames = mutableListOf<Frame>()
    private var touchMark = 0

    suspend fun call(tool: String, args: Map<String, Any?> = emptyMap()): Reply {
        val registered = server.tools[tool]
        val reply = if (registered == null) {
            Reply(tool, "tool '$tool' is not registered", isError = true, json = null)
        } else {
            val result = registered.handler(
                CallToolRequest(CallToolRequestParams(name = tool, arguments = JsonObject(args.mapValues { toJson(it.value) }))),
            )
            val blocks = result.content.filterIsInstance<TextContent>().map { it.text.orEmpty() }
            val text = blocks.joinToString("\n")
            // The tool's own result comes first; the post-action observation follows it.
            val json = runCatching { Json.parseToJsonElement(blocks.first()).jsonObject }.getOrNull()
            Reply(tool, text, result.isError == true, json)
        }
        transcript += reply.copy(text = reply.text.take(if (tool == "read_screen") 2_400 else 700))
        if (phone.touches.size > touchMark) frame("after $tool")
        return reply
    }

    suspend fun read(): Reply = call("read_screen").also { if (frames.isEmpty()) frame("first look") }

    /**
     * The som_id `read_screen` gave [nodeId] on the current screen, found by numbering the
     * same payload with the same functions. Null when the node is not on screen.
     */
    fun som(nodeId: String): Int? {
        val node = phone.nodeOrNull(nodeId) ?: return null
        val box = phone.visibleBox(node) ?: return null
        val payload = phone.payload()
        val elements = UiTreeToElements.extract(payload, minElements = 1)
        val rendered = ScreenPayload.render(
            elements, phone.foregroundPkg(), idle = true, settled = true,
            offscreen = ScreenText.offscreen(payload),
        ) ?: return null
        val same = rendered.elements.filter { it.bbox == box }
        val label = node.text.ifBlank { node.desc }
        return (same.firstOrNull { it.label == label && it.elementType == node.cls.substringAfterLast('.') }
            ?: same.firstOrNull { it.elementType == node.cls.substringAfterLast('.') }
            ?: same.firstOrNull())?.somId
    }

    suspend fun tap(nodeId: String, style: String? = null): Reply {
        val id = som(nodeId) ?: return Reply("tap", "bench: '$nodeId' has no som_id on screen", true, null)
            .also { check("'$nodeId' is on screen to tap", false, "") }
        return call("tap", buildMap { put("som_id", id); style?.let { put("style", it) } })
    }

    fun check(name: String, ok: Boolean, detail: String = "") {
        checks += Check(name, ok, detail)
    }

    /** The last touch went to [nodeId], and only through a route that a real phone would take. */
    fun expectHit(nodeId: String, via: String? = null) {
        val last = phone.touches.lastOrNull()
        check(
            "touch reached '$nodeId'" + (via?.let { " via $it" } ?: ""),
            last?.hit == nodeId && (via == null || last.via == via),
            "last touch: ${last ?: "none"}",
        )
    }

    fun expectRefused(reply: Reply, needle: String) =
        check("refused: mentions \"$needle\"", reply.isError && needle in reply.text, reply.text.take(240))

    fun expectNoTouch(since: Int) =
        check("no touch was delivered", phone.touches.size == since, "touches: ${phone.touches.drop(since)}")

    fun frame(caption: String) {
        val boxes = mutableListOf<JsonObject>()
        for (w in phone.windows.sortedBy { it.layer }) {
            for (n in w.root.subtree()) {
                val b = phone.visibleBox(n) ?: continue
                if (n === w.root && !w.own) continue
                boxes += buildJsonObject {
                    put("id", n.id)
                    put("x1", b.x1); put("y1", b.y1); put("x2", b.x2); put("y2", b.y2)
                    put("label", n.text.ifBlank { n.desc })
                    put("layer", w.layer)
                    put("own", w.own)
                    put(
                        "kind",
                        when {
                            n.editable -> "editable"
                            n.scrollable -> "scrollable"
                            n.clickable || n.longClickable -> "clickable"
                            else -> "plain"
                        },
                    )
                    put("enabled", n.enabled)
                }
            }
        }
        frames += Frame(caption, boxes, phone.touches.drop(touchMark))
        touchMark = phone.touches.size
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map(::toJson))
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
        else -> JsonPrimitive(v.toString())
    }
}

internal data class Outcome(val scenario: Scenario, val status: String, val ctx: Ctx?, val error: String?, val ms: Long)

/** Runs every scenario and writes `toolbench.json` plus a self-contained `report.html`. */
internal object Bench {

    suspend fun run(scenarios: List<Scenario>): List<Outcome> = scenarios.map { s ->
        val started = System.currentTimeMillis()
        val ctx = Ctx(MockPhone())
        val error = runCatching { s.body(ctx) }.exceptionOrNull()
        val passed = error == null && ctx.checks.isNotEmpty() && ctx.checks.all { it.ok }
        val status = when {
            passed && s.gap != null -> "closed"
            passed -> "pass"
            s.gap != null -> "gap"
            else -> "fail"
        }
        Outcome(s, status, ctx, error?.let { "${it::class.simpleName}: ${it.message}" }, System.currentTimeMillis() - started)
    }

    fun write(outcomes: List<Outcome>, dir: File) {
        dir.mkdirs()
        val data = buildJsonObject {
            put("generated", java.time.OffsetDateTime.now().toString())
            putJsonArray("scenarios") {
                for (o in outcomes) add(
                    buildJsonObject {
                        put("id", o.scenario.id)
                        put("group", o.scenario.group)
                        put("title", o.scenario.title)
                        put("status", o.status)
                        put("gap", o.scenario.gap)
                        put("error", o.error)
                        put("ms", o.ms)
                        putJsonArray("checks") {
                            o.ctx?.checks?.forEach { c ->
                                add(buildJsonObject { put("name", c.name); put("ok", c.ok); put("detail", c.detail) })
                            }
                        }
                        putJsonArray("transcript") {
                            o.ctx?.transcript?.forEach { r ->
                                add(buildJsonObject { put("tool", r.tool); put("error", r.isError); put("text", r.text) })
                            }
                        }
                        putJsonArray("frames") {
                            o.ctx?.frames?.forEach { f ->
                                add(
                                    buildJsonObject {
                                        put("caption", f.caption)
                                        put("boxes", JsonArray(f.boxes))
                                        putJsonArray("touches") {
                                            f.touches.forEach { t ->
                                                add(buildJsonObject {
                                                    put("kind", t.kind); put("x", t.x); put("y", t.y)
                                                    put("hit", t.hit); put("via", t.via)
                                                })
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        val json = data.toString()
        File(dir, "toolbench.json").writeText(json)
        val template = Bench::class.java.getResource("/toolbench/report.html")?.readText()
            ?: error("report template missing from test resources")
        // `</` would end the inline <script> early if a tool reply contained it.
        File(dir, "report.html").writeText(template.replace("/*__DATA__*/null", json.replace("</", "<\\/")))
    }
}

private fun buildServer(phone: MockPhone): Server = McpServerBuilder.build(
    serverName = "toolbench",
    version = "0",
    deviceBridge = phone,
    screenshotBridge = NoScreenshot,
    uiTreeBridge = phone,
    perceptionBridge = NoPerception,
    webSearchBridge = NoWebSearch,
    deepLinkBridge = NoDeepLinks,
    notificationBridge = InertNotificationBridge,
    mediaBridge = InertMediaBridge,
    systemIntentBridge = InertSystemIntentBridge,
    contactsBridge = NoContacts,
    filesBridge = NoFiles,
    browserBridge = InertBrowserBridge,
    auditLogger = NoOpAuditLogger,
    runStepsEnabled = true,
)

private object NoScreenshot : ScreenshotBridge {
    override fun isReady(): Boolean = false
    override fun requestPermission(): Boolean = false
    override suspend fun captureBase64Png(): CaptureResult = error("the mock phone has no pixels")
}

private object NoPerception : PerceptionBridge {
    override suspend fun detectElements(pngBytes: ByteArray, withOcr: Boolean, withAnnotatedImage: Boolean): PerceptionResult =
        error("the mock phone has no pixels")
    override suspend fun drawAnnotations(pngBytes: ByteArray, groups: List<AnnotationGroup>, maxLongSidePx: Int): String? = null
}

private object NoWebSearch : WebSearchBridge {
    override fun isConfigured(): Boolean = false
    override suspend fun search(query: String, maxResults: Int, topic: String): WebSearchResult = error("offline")
}

private object NoDeepLinks : DeepLinkBridge {
    override suspend fun listDeepLinks(packageName: String): DeepLinkCatalog = error("not modelled")
    override fun resolveUri(uri: String): UriResolution = error("not modelled")
    override fun openUri(uri: String, packageName: String?): OpenUriResult = error("not modelled")
}

private object NoContacts : ContactsBridge {
    override suspend fun resolveContact(name: String): ContactResolution = error("not modelled")
}

private object NoFiles : FilesBridge {
    override suspend fun findFiles(query: String?, kind: String?, limit: Int): FileSearchResult = error("not modelled")
    override fun openFile(uri: String): OpenUriResult = error("not modelled")
}

/** Builders for scene code. */
internal fun box(id: String, x1: Int, y1: Int, x2: Int, y2: Int, block: MockNode.() -> Unit = {}) =
    MockNode(id, BBox(x1, y1, x2, y2)).apply(block)

internal fun button(id: String, text: String, x1: Int, y1: Int, x2: Int, y2: Int, block: MockNode.() -> Unit = {}) =
    box(id, x1, y1, x2, y2) {
        cls = "android.widget.Button"; this.text = text; clickable = true
        onTap = { changed("tap $id") }
        block()
    }

internal fun label(id: String, text: String, x1: Int, y1: Int, x2: Int, y2: Int, block: MockNode.() -> Unit = {}) =
    box(id, x1, y1, x2, y2) { cls = "android.widget.TextView"; this.text = text; block() }

internal fun field(id: String, hint: String, x1: Int, y1: Int, x2: Int, y2: Int, block: MockNode.() -> Unit = {}) =
    box(id, x1, y1, x2, y2) { cls = "android.widget.EditText"; desc = hint; editable = true; clickable = true; block() }

internal fun screen(pkg: String, vararg kids: MockNode, layer: Int = 1) =
    MockWindow(pkg, layer, box("root:$pkg:$layer", 0, 0, 1080, 2400) { cls = "android.widget.FrameLayout" }.add(*kids))
