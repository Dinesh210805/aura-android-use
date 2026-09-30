package com.aura.aura_ui.mcp.bridge

import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.mcp.bridge.DeviceEvent
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.bridge.UiTreeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapter for [UiTreeBridge] backed by [AuraAccessibilityService.uiTreeExtractor]
 * (snapshot) and [AccessibilityEventBuffer] (events).
 *
 * The extractor returns a `Map<String, Any>` tree. We convert to `JSONObject`
 * here so the bridge surface is a flat JSON string — the `:mcp-server` module
 * never has to know the internal map shape, which keeps it free to evolve.
 *
 * `drainEvents` blocks on the buffer; we hop to [Dispatchers.IO] so the
 * coroutine doesn't tie up the Ktor request dispatcher.
 */
class AppUiTreeBridge : UiTreeBridge {

    override suspend fun snapshot(): UiTreeSnapshot = withContext(Dispatchers.IO) {
        val svc = AuraAccessibilityService.instance
            ?: return@withContext UiTreeSnapshot(
                ok = false,
                payloadJson = """{"error":"Accessibility service not running"}""",
            )

        runCatching { svc.uiTreeExtractor.getUITree() }
            .fold(
                onSuccess = { tree ->
                    if (tree == null) {
                        UiTreeSnapshot(
                            ok = false,
                            payloadJson = """{"error":"UI tree extraction returned null"}""",
                        )
                    } else {
                        UiTreeSnapshot(ok = true, payloadJson = toJson(tree).toString())
                    }
                },
                onFailure = { t ->
                    UiTreeSnapshot(
                        ok = false,
                        payloadJson = """{"error":${JSONObject.quote(t.message ?: "unknown")}}""",
                    )
                },
            )
    }

    override suspend fun drainEvents(timeoutMs: Long, maxEvents: Int): List<DeviceEvent> =
        withContext(Dispatchers.IO) {
            AccessibilityEventBuffer.drain(timeoutMs, maxEvents)
        }

    // ── JSON serialisation ──────────────────────────────────────────────────
    // The extractor returns nested Map / List / primitives. Walk it generically
    // so schema changes upstream don't require updates here.

    @Suppress("UNCHECKED_CAST")
    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o ->
            for ((k, v) in value) o.put(k.toString(), toJson(v))
        }
        is List<*> -> JSONArray().also { a ->
            for (item in value) a.put(toJson(item))
        }
        is Number, is Boolean, is String -> value
        else -> value.toString()
    }
}
