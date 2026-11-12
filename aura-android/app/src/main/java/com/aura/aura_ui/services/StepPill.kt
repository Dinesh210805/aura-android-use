package com.aura.aura_ui.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The line under AURA's cursor: what the current phone step is for.
 *
 * - Contract: [onTool] is installed as `ToolStatus.sink`, so every action from AURA's agent and from
 *   a remote MCP client lands here before it runs. The driver's own words win; without them the
 *   line is built from the tool and its arguments, and [onTarget] fills in the element under the
 *   finger once the tap point is known. Thread-safe; readers observe [line].
 * - Why present tense, before the action: the words should say where the cursor is going, not
 *   report where it has been.
 */
object StepPill {
    private val _line = MutableStateFlow("")
    val line: StateFlow<String> = _line.asStateFlow()

    /** Set while the line is our own fallback, so a target label may still improve it. */
    @Volatile
    private var pending: Pair<String, JsonObject?>? = null

    /** Every line shown since [resetTrail], oldest first; the report card lists the last few. */
    private val trail = ArrayDeque<String>()

    fun onTool(tool: String, status: String?, args: JsonObject?) {
        if (status != null) {
            pending = null
            _line.value = status
        } else {
            pending = tool to args
            _line.value = fallback(tool, args, null)
        }
        synchronized(trail) {
            trail.addLast(_line.value)
            while (trail.size > TRAIL_MAX) trail.removeFirst()
        }
    }

    /** True while the line is a fallback that the tapped element's label could improve. */
    val wantsTarget: Boolean get() = pending != null

    /** The element under the tap point, read by the accessibility service. Only refines a fallback. */
    fun onTarget(label: String?) {
        val (tool, args) = pending ?: return
        if (label.isNullOrBlank()) return
        val refined = fallback(tool, args, label)
        synchronized(trail) { if (trail.lastOrNull() == _line.value) trail[trail.lastIndex] = refined }
        _line.value = refined
    }

    /** Start a new run's trail. */
    fun resetTrail() = synchronized(trail) { trail.clear() }

    /** The steps since [resetTrail] in the words the person saw, repeats folded into one. */
    fun trail(): List<String> = synchronized(trail) {
        trail.fold(mutableListOf<String>()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }
    }

    private const val TRAIL_MAX = 40

    /** The run is over: nothing is in progress any more. Keeps the trail for the report. */
    fun clear() {
        pending = null
        _line.value = ""
    }

    internal fun fallback(tool: String, args: JsonObject?, label: String?): String {
        fun arg(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
            (args?.get(k) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
        val target = (label ?: arg("text_hint", "label", "target"))?.let(::short)
        return when (tool) {
            "tap", "click", "double_tap" -> target?.let { "Tapping $it" } ?: "Tapping"
            "long_press" -> target?.let { "Pressing and holding $it" } ?: "Pressing and holding"
            "type_text", "set_value" -> arg("text", "value")?.let { "Typing “${short(it)}”" } ?: "Typing"
            "press_enter" -> "Pressing Enter"
            "press_key" -> arg("key")?.let { "Pressing ${short(it)}" } ?: "Pressing a key"
            "scroll", "swipe" -> arg("direction")?.let { "Scrolling $it" } ?: "Scrolling"
            "launch_app", "open_app" -> arg("app_name", "name")?.let { "Opening ${short(it)}" } ?: "Opening the app"
            "open_deeplink", "open_url" -> "Opening a link"
            "go_back", "back" -> "Going back"
            "go_home", "home" -> "Going to the home screen"
            "draw", "drag" -> "Dragging"
            else -> tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun short(s: String): String = s.replace(Regex("\\s+"), " ").let { if (it.length <= 24) it else it.take(23).trimEnd() + "…" }
}
