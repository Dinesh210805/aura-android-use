package com.aura.aura_ui.agentcore

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKoogJSONElement
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import ai.koog.serialization.typeToken
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The tool layer: every tool the model can call, from every source, behind one gate.
 *
 * - Contract: [HubTool.execute] is the only path from the model to a tool. It runs
 *   [SafetyGate], asks the user when told to, dispatches, then fences, clamps and taints the
 *   result. A refused or failed call returns an error result the model can re-plan from; only
 *   cancellation is thrown.
 * - Why the gate lives inside the tool: Koog's tool pipeline can observe a call but cannot veto
 *   one, so a check placed anywhere else is advisory.
 * - Change together: `ToolSources.kt` (where specs come from), `SafetyGate.kt`, `README.md`.
 */

/** What a tool returned, as the model reads it. */
@Serializable
data class ToolOutcome(val text: String, val isError: Boolean = false)

/** What a [ToolSource] returned, before the hub fences and clamps it. */
class SourceResult(
    val text: String,
    val isError: Boolean = false,
    /** A screenshot to show the model on the next request, or null. */
    val imagePng: ByteArray? = null,
)

/** Everything the hub knows about one tool. */
data class ToolSpec(
    val descriptor: ToolDescriptor,
    val risk: ToolRisk,
    val origin: ToolOrigin,
    /** The group this tool loads with. [ToolHub.CORE] is always loaded. */
    val toolset: String,
    /** What kind of untrusted text the result carries, or null when the harness wrote it. */
    val resultOrigin: Origin?,
    /** True when the arguments travel to a host the user did not choose. */
    val egress: Boolean = false,
) {
    val name: String get() = descriptor.name
}

/** A place tools come from: the phone, a connector, or Kotlin code in this package. */
interface ToolSource {
    val id: String

    /** Fails: an unreachable source returns an empty list, so one dead connector never blocks a turn. */
    suspend fun list(): List<ToolSpec>

    suspend fun call(name: String, args: JsonObject, turn: TurnState): SourceResult

    suspend fun close() {}
}

/** A tool implemented in Kotlin inside the app. */
class NativeTool(val spec: ToolSpec, val run: suspend (JsonObject, TurnState) -> SourceResult)

class NativeToolSource(override val id: String, private val tools: List<NativeTool>) : ToolSource {
    override suspend fun list(): List<ToolSpec> = tools.map { it.spec }

    override suspend fun call(name: String, args: JsonObject, turn: TurnState): SourceResult =
        tools.firstOrNull { it.spec.name == name }?.run?.invoke(args, turn)
            ?: SourceResult("Unknown tool: $name", isError = true)
}

/**
 * The mutable state of one turn, shared by the engine and the tools it runs.
 *
 * - Contract: one instance per turn, never reused. Thread-safe.
 */
class TurnState(
    val threadId: String,
    val mode: RunMode,
    /** Untrusted origins already in the thread's stored context. */
    inheritedTaint: Set<Origin> = emptySet(),
    private val sink: suspend (AgentEvent) -> Unit,
) {
    val taint = TurnTaint(inheritedTaint)
    private val toolsets = mutableSetOf(ToolHub.CORE)
    private val images = ArrayDeque<ByteArray>()
    private val steering = ArrayDeque<String>()
    private val notes = ArrayDeque<String>()
    private val failures = mutableMapOf<String, Int>()

    /** What the model wrote alongside the tool calls now running. Shown with a commit's Allow prompt. */
    @Volatile
    var saying: String = ""

    /** Phone calls this turn that changed something (not reads) and succeeded. */
    @Volatile
    var actions: Int = 0
        private set

    @Synchronized
    fun noteAction() {
        actions++
    }

    /** Queue reference text found in the background; it is shown after the next tool result. */
    @Synchronized
    fun note(text: String) {
        notes.addLast(text)
    }

    @Synchronized
    fun drainNotes(): List<String> = notes.toList().also { notes.clear() }

    suspend fun emit(event: AgentEvent) = sink(event)

    @Synchronized
    fun load(toolset: String): Boolean = toolsets.add(toolset)

    @Synchronized
    fun loaded(): Set<String> = toolsets.toSet()

    @Synchronized
    fun stashImage(png: ByteArray) {
        images.addLast(png)
    }

    /** Queue something the user said while the turn was running; it is shown after the next tool result. */
    @Synchronized
    fun steer(text: String) {
        steering.addLast(text)
    }

    @Synchronized
    fun drainSteering(): List<String> = steering.toList().also { steering.clear() }

    /** Count a failed call and return how many times this exact call has failed this turn. */
    @Synchronized
    fun noteFailure(callKey: String): Int = ((failures[callKey] ?: 0) + 1).also { failures[callKey] = it }

    @Synchronized
    fun failuresOf(callKey: String): Int = failures[callKey] ?: 0

    /** The screenshots produced since the last call, oldest first. */
    @Synchronized
    fun drainImages(): List<ByteArray> = images.toList().also { images.clear() }
}

/**
 * Puts a question in front of the user and waits for the answer.
 *
 * - Contract: [ask] returns the chosen option or typed text, or null when the user did not answer
 *   within [timeoutMs], the turn was cancelled, or nobody is watching ([RunMode.BACKGROUND]).
 *   Callers must treat null as "no".
 */
class UserBroker(
    private val timeoutMs: Long = 120_000L,
    /**
     * Another surface that asks the user (the overlay, the voice companion). Returns null when it
     * does not handle this turn, else the answer wrapped in a `Result` (a null answer is "no reply").
     */
    private val elsewhere: suspend (TurnState, question: String, options: List<String>) -> Result<String?>? =
        { _, _, _ -> null },
) {

    data class Pending(val id: String, val question: String, val options: List<String>)

    private val waiting = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())

    /** What the UI should be showing right now. */
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    suspend fun ask(turn: TurnState, question: String, options: List<String>): String? {
        if (turn.mode == RunMode.BACKGROUND) return null
        elsewhere(turn, question, options)?.let { routed ->
            turn.emit(AgentEvent.UserPromptRequested(UUID.randomUUID().toString(), question, options))
            return routed.getOrNull()
        }
        val prompt = Pending(UUID.randomUUID().toString(), question, options)
        val answer = CompletableDeferred<String?>()
        waiting[prompt.id] = answer
        _pending.update { it + prompt }
        return try {
            turn.emit(AgentEvent.UserPromptRequested(prompt.id, question, options))
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            waiting.remove(prompt.id)
            _pending.update { it - prompt }
        }
    }

    fun answer(promptId: String, answer: String?) {
        waiting[promptId]?.complete(answer)
    }

    /**
     * - Contract: true only for a clear yes. A spoken or typed answer counts ("yes", "go ahead");
     *   silence, a timeout or anything unclear is a no.
     */
    suspend fun confirm(turn: TurnState, question: String): Boolean =
        ask(turn, question, listOf(ALLOW, DENY))?.trim()?.let(YES::matches) == true

    companion object {
        const val ALLOW = "Allow"
        const val DENY = "Deny"
        private val YES = Regex("(?i)(allow|yes|yeah|yep|ok|okay|sure|confirm|go ahead|do it|send it|looks good)[.! ]*")
    }
}

class ToolHub(
    /** Read at every [refresh], so a connector added mid-session shows up on the next turn. */
    private val sources: () -> List<ToolSource>,
    private val broker: UserBroker,
    private val settings: () -> SafetySettings = { SafetySettings() },
    /** Label of the element a gesture call targets, read from the last screen. Null when unknown. */
    private val targetLabel: suspend (tool: String, args: JsonObject) -> String? = { _, _ -> null },
) {
    private val control = NativeToolSource("control", listOf(loadToolsetTool(), askUserTool(), updatePlanTool()))

    @Volatile
    private var catalog: Map<String, Pair<ToolSpec, ToolSource>> = emptyMap()

    /**
     * Re-read every source's tool list.
     * - Contract: call once before a turn. The first source to claim a name keeps it, so a
     *   connector can never shadow a phone or native tool.
     */
    suspend fun refresh() {
        val merged = LinkedHashMap<String, Pair<ToolSpec, ToolSource>>()
        (listOf(control) + sources()).forEach { source ->
            val specs = try {
                source.list()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            specs.forEach { spec -> merged.putIfAbsent(spec.name, spec to source) }
        }
        catalog = merged
    }

    /**
     * Read the screen on the model's behalf, through the same gate and source as its own call.
     * - Contract: null when the phone has no `read_screen` or the read failed; the model then
     *   looks for itself.
     */
    suspend fun observeScreen(turn: TurnState): String? {
        val (spec, source) = catalog[READ_SCREEN] ?: return null
        return HubTool(spec, source, turn).gated(JsonObject(emptyMap())).takeIf { !it.isError }?.text
    }

    /** Every tool, registered so Koog can run any call the model makes. */
    fun registry(turn: TurnState): ToolRegistry = ToolRegistry {
        catalog.values.forEach { (spec, source) -> tool(HubTool(spec, source, turn)) }
    }

    /** The schemas to send with the next request: only the toolsets this turn has loaded. */
    fun visible(turn: TurnState): List<ToolDescriptor> {
        val loaded = turn.loaded()
        return catalog.values.map { it.first }.filter { it.toolset in loaded }.map { it.descriptor }
    }

    /** One line per unloaded toolset, for the system prompt. Stable while the tool set is. */
    fun toolsetMenu(): String = catalog.values.map { it.first }
        .filter { it.toolset != CORE }
        .groupBy { it.toolset }
        .map { (toolset, specs) -> "$toolset: ${specs.joinToString(", ") { it.name }}" }
        .sorted()
        .joinToString("\n")

    private fun toolsetNames(): List<String> = catalog.values.map { it.first.toolset }.distinct().filter { it != CORE }

    private fun loadToolsetTool() = NativeTool(
        ToolSpec(
            descriptor = ToolDescriptor(
                name = "load_toolset",
                description = "Load a group of tools listed under <toolsets> so you can call them.",
                requiredParameters = listOf(
                    ToolParameterDescriptor("name", "The toolset's name, exactly as listed.", ToolParameterType.String),
                ),
                optionalParameters = emptyList(),
            ),
            risk = ToolRisk.READ,
            origin = ToolOrigin.NATIVE,
            toolset = CORE,
            resultOrigin = null,
        ),
    ) { args, turn ->
        val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (name !in toolsetNames()) {
            return@NativeTool SourceResult("No toolset named '$name'. Available: ${toolsetNames().joinToString()}", isError = true)
        }
        turn.load(name)
        val tools = catalog.values.map { it.first }.filter { it.toolset == name }.joinToString(", ") { it.name }
        val guide = if (name == SCREEN) "\n\n" + SystemPrompt.SCREEN_GUIDE else ""
        SourceResult("Loaded $name: $tools.$guide")
    }

    private fun askUserTool() = NativeTool(
        ToolSpec(
            descriptor = ToolDescriptor(
                name = "ask_user",
                description = "Ask the user one question and wait. Use it for a missing detail you cannot " +
                    "safely infer, or to confirm a step that commits them. Never ask what you can read yourself.",
                requiredParameters = listOf(
                    ToolParameterDescriptor("question", "The question, in the user's language.", ToolParameterType.String),
                ),
                optionalParameters = listOf(
                    ToolParameterDescriptor(
                        "options",
                        "Two to four short answers to offer.",
                        ToolParameterType.List(itemsType = ToolParameterType.String),
                    ),
                ),
            ),
            risk = ToolRisk.READ,
            origin = ToolOrigin.NATIVE,
            toolset = CORE,
            resultOrigin = null,
        ),
    ) { args, turn ->
        val question = args["question"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val options = runCatching { args["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }
            .getOrNull().orEmpty().take(MAX_OPTIONS)
        when (val answer = broker.ask(turn, question, options)) {
            null -> SourceResult("The user did not answer. Do not take the step you asked about.", isError = true)
            else -> SourceResult("The user answered: $answer")
        }
    }

    private fun updatePlanTool() = NativeTool(
        ToolSpec(
            descriptor = ToolDescriptor(
                name = "update_plan",
                description = "Show the user your plan for a task with several steps, and update it as steps finish.",
                requiredParameters = listOf(
                    ToolParameterDescriptor(
                        "steps",
                        "Every step in order, each a short action.",
                        ToolParameterType.List(itemsType = ToolParameterType.String),
                    ),
                    ToolParameterDescriptor("done", "How many steps, from the first, are finished.", ToolParameterType.Integer),
                ),
                optionalParameters = emptyList(),
            ),
            risk = ToolRisk.READ,
            origin = ToolOrigin.NATIVE,
            toolset = CORE,
            resultOrigin = null,
        ),
    ) { args, turn ->
        val steps = runCatching { args["steps"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull().orEmpty()
        val done = (args["done"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0).coerceIn(0, steps.size)
        turn.emit(AgentEvent.PlanUpdated(steps, done))
        SourceResult(if (done >= steps.size) "Plan complete." else "Plan saved. Next: ${steps[done]}")
    }

    /** The Koog tool that wraps one [ToolSpec]. See the file header for its contract. */
    @OptIn(InternalAgentsApi::class)
    private inner class HubTool(
        private val spec: ToolSpec,
        private val source: ToolSource,
        private val turn: TurnState,
    ) : Tool<JSONObject, ToolOutcome>(
        argsType = typeToken<JSONObject>(),
        resultType = typeToken<ToolOutcome>(),
        descriptor = spec.descriptor,
        metadata = emptyMap(),
    ) {
        override suspend fun execute(args: JSONObject): ToolOutcome {
            val json = args.toKotlinxJsonObject()
            val callId = UUID.randomUUID().toString()
            val started = System.nanoTime()
            turn.emit(AgentEvent.ToolStarted(callId, spec.name, SecretPatterns.redact(json.toString()).take(PREVIEW_CHARS)))
            val outcome = gated(json)
            turn.emit(
                AgentEvent.ToolFinished(
                    callId = callId,
                    tool = spec.name,
                    ok = !outcome.isError,
                    resultPreview = SecretPatterns.redact(outcome.text).take(PREVIEW_CHARS),
                    durationMs = (System.nanoTime() - started) / 1_000_000,
                ),
            )
            return outcome
        }

        suspend fun gated(args: JsonObject): ToolOutcome {
            val label = if (spec.risk == ToolRisk.ACT) targetLabel(spec.name, args) else null
            val argsText = args.toString()
            // Any call carrying a URL can send data out, whatever the tool was classified as.
            val egress = spec.egress || "://" in argsText
            val request = ToolCallRequest(spec.name, spec.risk, spec.origin, argsText, egress, label)
            if (turn.failuresOf(callKey(args)) >= BLOCK_AFTER_FAILURES) {
                return ToolOutcome(
                    "This exact call has failed $BLOCK_AFTER_FAILURES times. Stop repeating it: try something " +
                        "different or tell the user.",
                    isError = true,
                )
            }
            return when (val verdict = SafetyGate.evaluate(request, turn.mode, turn.taint, settings())) {
                is Verdict.Deny -> ToolOutcome(verdict.modelMessage, isError = true)
                is Verdict.Ask -> {
                    // The model is told to say what a commit will do; the user reads that here.
                    val said = turn.saying.takeIf { it.isNotBlank() && !verdict.unlocksEgress }
                        ?.let { SecretPatterns.redact(it).take(MAX_SAID_IN_QUESTION) + "\n\n" }.orEmpty()
                    if (!broker.confirm(turn, said + verdict.question)) {
                        ToolOutcome("The user declined: ${verdict.question} Do not retry or look for another way.", isError = true)
                    } else {
                        if (verdict.unlocksEgress) turn.taint.allowEgress()
                        dispatch(args)
                    }
                }
                Verdict.Allow -> dispatch(args)
            }
        }

        private suspend fun dispatch(args: JsonObject): ToolOutcome {
            val result = try {
                source.call(spec.name, args, turn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SourceResult("${spec.name} failed: ${e.message ?: e::class.simpleName}", isError = true)
            }
            spec.resultOrigin?.let(turn.taint::mark)
            result.imagePng?.let(turn::stashImage)
            if (!result.isError && spec.risk != ToolRisk.READ && spec.origin == ToolOrigin.PHONE) turn.noteAction()
            val repeat = if (result.isError) turn.noteFailure(callKey(args)) else 0
            val hint = if (repeat >= WARN_AFTER_FAILURES) "\nThis exact call has now failed $repeat times. Do not repeat it." else ""
            val text = ContextAssembler.clamp(result.text, MAX_RESULT_CHARS) + hint
            val body = spec.resultOrigin?.takeIf { !result.isError }?.let { Fence.wrap(it, text) } ?: text
            return ToolOutcome(body, result.isError)
        }

        private fun callKey(args: JsonObject): String = spec.name + args.toString()

        override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): ToolOutcome =
            json.decodeFromJsonElement(ToolOutcome.serializer(), rawResult.toKotlinxJsonElement())

        override fun encodeResult(result: ToolOutcome, serializer: JSONSerializer): JSONElement =
            json.encodeToJsonElement(ToolOutcome.serializer(), result).toKoogJSONElement()

        override fun encodeResultToString(result: ToolOutcome, serializer: JSONSerializer): String =
            if (result.isError) "Error: ${result.text}" else result.text
    }

    companion object {
        /** Always loaded. */
        const val CORE = "core"

        /** Reading and driving the screen. Loading it returns [SystemPrompt.SCREEN_GUIDE]. */
        const val SCREEN = "screen"

        private const val READ_SCREEN = "read_screen"
        private const val MAX_SAID_IN_QUESTION = 400
        private const val MAX_RESULT_CHARS = 12_000
        private const val PREVIEW_CHARS = 240
        private const val MAX_OPTIONS = 4
        private const val WARN_AFTER_FAILURES = 2
        private const val BLOCK_AFTER_FAILURES = 4
        private val json = Json { ignoreUnknownKeys = true }
    }
}
