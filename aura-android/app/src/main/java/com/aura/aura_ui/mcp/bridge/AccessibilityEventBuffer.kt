package com.aura.aura_ui.mcp.bridge

import android.view.accessibility.AccessibilityEvent
import com.aura.aura_ui.BuildConfig
import com.aura.mcp.bridge.DeviceEvent
import com.aura.mcp.cache.ScreenActivity
import java.util.concurrent.ArrayBlockingQueue

/**
 * Process-global ring buffer of accessibility events for the
 * `watch_device_events` MCP tool.
 *
 * `AuraAccessibilityService.onAccessibilityEvent` pushes to [record].
 * [AppUiTreeBridge.drainEvents] reads via [drain] with a timeout.
 *
 * We use a bounded `ArrayBlockingQueue` so a quiet MCP client cannot leak
 * memory indefinitely: at capacity, oldest events are dropped silently. The
 * 256-event ceiling is enough to survive a few seconds of even noisy apps
 * (Compose recomposition storms, scrolling lists) while bounding worst-case
 * footprint to ~256 small data-class instances.
 *
 * `object` (singleton) is appropriate here because the underlying observation
 * — the OS-level accessibility service — is itself process-singleton.
 */
internal object AccessibilityEventBuffer {

    private const val CAPACITY = 256

    private val queue = ArrayBlockingQueue<DeviceEvent>(CAPACITY)

    fun record(event: AccessibilityEvent) {
        // Layer 0 of screen-change detection: classify once, here, where the raw
        // bitmasks are still available. Downstream consumers see only the verdict.
        // ScreenActivity's counters give us passive invalidation — a screen that
        // moves with no tool call behind it (notification banner, splash -> home)
        // is otherwise invisible to the write-scope generation counter.
        val packageName = event.packageName?.toString().orEmpty()
        val changeClass = ScreenActivity.classify(event.eventType, event.contentChangeTypes)

        // Our OWN overlay is a window: showing or hiding the expanded panel emits
        // TYPE_WINDOW_STATE_CHANGED, which would classify STRUCTURAL and bump the
        // activity counter on every single tool call. That would send every som_id
        // resolution down the rescue path (a tree read per gesture) and make the
        // passive-invalidation signal meaningless. ScreenSettle already filters this
        // package; the counter must filter it too or the two disagree.
        if (packageName != BuildConfig.APPLICATION_ID) {
            ScreenActivity.record(changeClass)
            // Idle is a different question from "did it change": a spinner is AMBIENT
            // (no change) but definitely NOT idle, so ScreenIdle stamps every class.
            com.aura.aura_ui.uistream.ScreenIdle.onEvent(changeClass, System.currentTimeMillis())
        }

        val mapped = DeviceEvent(
            type = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = packageName,
            timestampMs = event.eventTime,
            description = describe(event),
            changeClass = changeClass,
        )
        // offer() drops on full — preferable to blocking the accessibility
        // service's onAccessibilityEvent thread.
        if (!queue.offer(mapped)) {
            queue.poll()         // make room
            queue.offer(mapped)
        }
    }

    fun drain(timeoutMs: Long, maxEvents: Int): List<DeviceEvent> {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0)
        val collected = mutableListOf<DeviceEvent>()

        // Drain anything already buffered immediately.
        while (collected.size < maxEvents) {
            val next = queue.poll() ?: break
            collected.add(next)
        }
        if (collected.size >= maxEvents) return collected

        // Then block-wait for new events up to the deadline.
        while (collected.size < maxEvents) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val next = queue.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (next != null) collected.add(next) else break
        }
        return collected
    }

    private fun describe(event: AccessibilityEvent): String {
        val cls = event.className?.toString()?.substringAfterLast('.').orEmpty()
        val text = event.text?.joinToString(" ")?.take(80).orEmpty()
        return if (text.isNotEmpty()) "$cls: $text" else cls
    }
}
