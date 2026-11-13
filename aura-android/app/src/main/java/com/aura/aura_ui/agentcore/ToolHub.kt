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
import kotlinx.serialization.json.JsonPrimitive
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
    /** For the run log: what a gesture pressed. Never shown to the model. */
    val shot: StepShot? = null,
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
    /** True when this turn must not drive the screen itself: asking for the `screen` toolset ends it. */
    val handsOffScreen: Boolean = false,
    private val sink: suspend (AgentEvent) -> Unit,
) {
    /** Names this turn in the run log ([RunJournal]). */
    val runId: String = UUID.randomUUID().toString()

    /** The user's own words for this turn. A link may carry only these off to an app; see `ToolHub`. */
    @Volatile
    var request: String = ""

    /** Set when the turn asked for the screen and [handsOffScreen] is true. */
    @Volatile
    var handedOff: Boolean = false

    val taint = TurnTaint(inheritedTaint)

    /** Every screen this task saw; read by the `screens`, `screen` and `screen_diff` tools. */
    val screens = ScreenHistory()

    /** What the run has done, for the ledger the engine shows the model on long tasks. */
    val ledger = RunLedger()

    /** The text this turn last typed into a field; see [SafetyGate.covered]. */
    @Volatile
    var lastTyped: String? = null

    /** Last ten digits of every number `resolve_contact` returned this turn: recipients the user can mean. */
    val contactNumbers: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Estimated prompt size right after the last compaction; see `AgentEngine.compactIfOver`. */
    @Volatile
    var compactedAt: Int = 0
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

    data class Pending(val id: String, val question: String, val options: List<String>) {
        /** True for an Allow or Deny prompt in front of a step that commits the user. */
        val isConfirm: Boolean get() = options == listOf(ALLOW, DENY)
    }

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
        internal val YES = Regex("(?i)(allow|yes|yeah|yep|ok|okay|sure|confirm|go ahead|do it|send it|looks good)[.! ]*")
    }
}

class ToolHub(
    /** Read at every [refresh], so a connector added mid-session shows up on the next turn. */
    private val sources: () -> List<ToolSource>,
    private val broker: UserBroker,
    private val settings: () -> SafetySettings = { SafetySettings() },
    /** Label of the element a gesture call targets, read from the last screen. Null when unknown. */
    private val targetLabel: suspend (tool: String, args: JsonObject) -> String? = { _, _ -> null },
    /** The readable run log. Null in tests. */
    private val journal: RunJournal? = null,
    /** The app in front, by its label, for the verification card's title. Null when unknown. */
    private val appLabel: suspend () -> String? = { null },
    /**
     * True when [url] opens in an installed app other than a browser (`https://wa.me/…` → WhatsApp).
     * Such a link hands text to an app on this phone, which still needs its own Send, so it is not
     * a send-out. Fails closed: false when unsure.
     */
    private val opensInstalledApp: (url: String) -> Boolean = { false },
) {
    private val control = NativeToolSource("control", listOf(loadToolsetTool(), askUserTool(), updatePlanTool(), proposeActionTool()) + screenHistoryTools())

    /**
     * What the user approved with `propose_action`, per chat. In memory: it survives a hand-off to
     * the overlay (same thread) but not an app restart, after which the user is asked again.
     */
    private val grants = java.util.concurrent.ConcurrentHashMap<String, List<Grant>>()

    private fun grantsOf(threadId: String): List<Grant> = grants[threadId].orEmpty()

    private fun grant(threadId: String, vararg add: Grant) {
        grants.merge(threadId, add.toList()) { old, new -> old + new }
    }

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
        // Only what changed when the screen is the same one; the server sends the whole screen
        // when the app changed or most of it did. `ContextAssembler.evictStaleScreens` keeps the
        // full screen a diff is relative to.
        val args = JsonObject(mapOf("changes_only" to JsonPrimitive(true)))
        return HubTool(spec, source, turn).gated(args).takeIf { !it.isError }?.text
    }

    /** Every tool, registered so Koog can run any call the model makes. */
    fun registry(turn: TurnState): ToolRegistry = ToolRegistry {
        catalog.values.forEach { (spec, source) -> tool(HubTool(spec, source, turn)) }
    }

    /** Every tool name the model could call. */
    fun names(): Set<String> = catalog.keys

    /**
     * The schemas to send with the next request: only the toolsets this turn has loaded.
     * - Why in load order: loading a toolset then appends to the tools field, so the provider's
     *   cached prefix (system prompt plus the tools already sent) still matches.
     */
    fun visible(turn: TurnState): List<ToolDescriptor> {
        val specs = catalog.values.map { it.first }
        return turn.loaded().flatMap { toolset -> specs.filter { it.toolset == toolset } }.map { it.descriptor }
    }

    /**
     * True when every call in a reply may run at once: reads that do not touch the screen and
     * neither ask the user nor change what the turn has loaded. Screen work stays in order.
     */
    fun parallelSafe(names: List<String>): Boolean = names.size > 1 && names.all { name ->
        val spec = catalog[name]?.first ?: return@all false
        spec.risk == ToolRisk.READ && spec.toolset != SCREEN && name !in SEQUENTIAL
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
        journal?.ask(turn.runId, question)
        when (val answer = broker.ask(turn, question, options)) {
            null -> SourceResult("The user did not answer. Do not take the step you asked about.", isError = true)
            else -> SourceResult("The user answered: $answer")
        }
    }

    /** `screens`, `screen`, `screen_diff`: the task's screen history, see [ScreenHistory]. */
    private fun screenHistoryTools(): List<NativeTool> {
        fun spec(name: String, description: String, params: List<ToolParameterDescriptor>, optional: List<ToolParameterDescriptor> = emptyList()) = ToolSpec(
            descriptor = ToolDescriptor(name, description, requiredParameters = params, optionalParameters = optional),
            risk = ToolRisk.READ,
            origin = ToolOrigin.NATIVE,
            toolset = SCREEN,
            resultOrigin = Origin.SCREEN,
        )
        fun arg(args: JsonObject, name: String) = args[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        return listOf(
            NativeTool(
                spec(
                    "screens",
                    "List the screens seen in this task, one line each (S3 a new screen, S3.1 a change on it). Use it to find something you saw earlier instead of going back.",
                    emptyList(),
                    listOf(ToolParameterDescriptor("query", "Only screens whose text contains this.", ToolParameterType.String)),
                ),
            ) { args, turn -> SourceResult(turn.screens.log(arg(args, "query"))) },
            NativeTool(
                spec("screen", "Show an earlier screen exactly as you saw it.", listOf(ToolParameterDescriptor("id", "Its id from screens, e.g. S3.", ToolParameterType.String))),
            ) { args, turn ->
                turn.screens.show(arg(args, "id"))?.let { SourceResult(it) } ?: SourceResult("No screen ${arg(args, "id")}. Call screens to list them.", isError = true)
            },
            NativeTool(
                spec(
                    "screen_diff",
                    "What changed between two earlier screens: lines only in the first as -, only in the second as +.",
                    listOf(
                        ToolParameterDescriptor("from", "The earlier screen's id.", ToolParameterType.String),
                        ToolParameterDescriptor("to", "The later screen's id.", ToolParameterType.String),
                    ),
                ),
            ) { args, turn ->
                turn.screens.diff(arg(args, "from"), arg(args, "to"))?.let { SourceResult(it) }
                    ?: SourceResult("Unknown screen id. Call screens to list them.", isError = true)
            },
        )
    }

    /**
     * `propose_action`: one card with the exact message before a send; an approval becomes a
     * [Grant] that covers the matching link, connector call and Send tap (see [SafetyGate.covered]).
     * - Contract: hands-free mode with nothing untrusted read approves without a card. "Don't ask
     *   again in this chat" is offered only while nothing untrusted was read this turn. A typed
     *   answer that is not a yes is the user's edit, returned for the model to propose again.
     */
    private fun proposeActionTool() = NativeTool(
        ToolSpec(
            descriptor = ToolDescriptor(
                name = SafetyGate.PROPOSE_ACTION,
                description = PROPOSE_DESCRIPTION,
                requiredParameters = listOf(
                    ToolParameterDescriptor("app", "The app it goes out through.", ToolParameterType.String),
                    ToolParameterDescriptor("to", "Who receives it, as the user would say it.", ToolParameterType.String),
                    ToolParameterDescriptor("message", "The exact final text, word for word.", ToolParameterType.String),
                ),
                optionalParameters = listOf(ToolParameterDescriptor("summary", "One line, optional.", ToolParameterType.String)),
            ),
            risk = ToolRisk.READ,
            origin = ToolOrigin.NATIVE,
            toolset = CORE,
            resultOrigin = null,
        ),
    ) { args, turn ->
        val app = args["app"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val to = args["to"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val message = args["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (app.isEmpty() || to.isEmpty() || message.isBlank()) {
            return@NativeTool SourceResult("Give app, to and the exact message.", isError = true)
        }
        if (turn.mode == RunMode.BACKGROUND) {
            return@NativeTool SourceResult("Nothing can be sent in the background. Tell the user what you would send.", isError = true)
        }
        val s = settings()
        SafetyGate.blocked(ToolCallRequest(SafetyGate.PROPOSE_ACTION, ToolRisk.READ, ToolOrigin.NATIVE, args.toString()), s)
            ?.let { return@NativeTool SourceResult(it, isError = true) }
        if (SecretPatterns.containsSecret(message)) {
            return@NativeTool SourceResult("policy_blocked: the message contains a password, key, card number or code.", isError = true)
        }
        val approved = "Approved. Send exactly this text to $to in $app; the steps that match it will not ask again."
        val expires = System.currentTimeMillis() + GRANT_MS
        if (s.mode == ApprovalMode.HANDS_FREE && !turn.taint.tainted) {
            grant(turn.threadId, Grant(app, to, message, expires))
            return@NativeTool SourceResult(approved)
        }
        val options = listOfNotNull(SEND, SEND_IN_CHAT.takeIf { !turn.taint.tainted }, CANCEL)
        val card = "Send in $app to $to?\n\n\u201C$message\u201D"
        journal?.ask(turn.runId, card)
        val answer = broker.ask(turn, card, options)?.trim()
        journal?.note(turn.runId, "You answered the send card: ${answer ?: "no answer"}", ok = answer != null && answer != CANCEL)
        when {
            answer == null -> SourceResult("The user did not answer. Do not send.", isError = true)
            answer == CANCEL || answer.equals(UserBroker.DENY, true) -> SourceResult(SafetyGate.DECLINED, isError = true)
            answer == SEND_IN_CHAT -> {
                grant(turn.threadId, Grant(app, to, message, expires), Grant(app, to, null, Long.MAX_VALUE))
                SourceResult("$approved The user will not be asked again in this chat for messages to $to in $app.")
            }
            answer == SEND || UserBroker.YES.matches(answer) -> {
                grant(turn.threadId, Grant(app, to, message, expires))
                SourceResult(approved)
            }
            else -> SourceResult("The user changed the message to: \u201C$answer\u201D. Call propose_action again with that text.")
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
        turn.ledger.plan(steps, done)
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
        /** What the call now running pressed. Calls in a turn run one at a time. */
        private var shot: StepShot? = null

        override suspend fun execute(args: JSONObject): ToolOutcome {
            val json = args.toKotlinxJsonObject()
            val callId = UUID.randomUUID().toString()
            val started = System.nanoTime()
            shot = null
            turn.emit(AgentEvent.ToolStarted(callId, spec.name, SecretPatterns.redact(json.toString()).take(PREVIEW_CHARS)))
            val outcome = gated(json)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            journal?.tool(turn.runId, spec.name, json.toString(), outcome.text, !outcome.isError, tookMs, shot)
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
            val wantsScreen = spec.toolset == SCREEN ||
                (spec.name == LOAD_TOOLSET && args["name"]?.jsonPrimitive?.contentOrNull == SCREEN)
            if (turn.handsOffScreen && wantsScreen) {
                turn.handedOff = true
                return ToolOutcome("Handing this task to the overlay.")
            }
            val label = if (spec.risk == ToolRisk.ACT) targetLabel(spec.name, args) else null
            val argsText = args.toString()
            val request = ToolCallRequest(
                spec.name, spec.risk, spec.origin, argsText, sendsOut(argsText), label,
                appLabel = if ((label != null && CommitLabels.isCommit(label)) || (spec.risk != ToolRisk.READ && settings().deniedApps.isNotEmpty())) {
                    runCatching { appLabel() }.getOrNull()
                } else {
                    null
                },
                screenTitle = if (label != null && CommitLabels.isCommit(label)) turn.screens.latestCaption() else null,
                typed = turn.lastTyped,
            )
            if (turn.failuresOf(callKey(args)) >= BLOCK_AFTER_FAILURES) {
                journal?.note(turn.runId, "Blocked a repeated call to ${spec.name}", argsText, ok = false)
                return ToolOutcome(
                    "This exact call has failed $BLOCK_AFTER_FAILURES times. Stop repeating it: try something " +
                        "different or tell the user.",
                    isError = true,
                )
            }
            val verdict = SafetyGate.evaluate(request, turn.mode, turn.taint, settings())
            if (verdict !is Verdict.Allow) journal?.note(turn.runId, gateTitle(verdict), gateDetail(request, verdict, turn.taint.origins), ok = verdict !is Verdict.Deny)
            return when (verdict) {
                is Verdict.Deny -> ToolOutcome(verdict.modelMessage, isError = true)
                is Verdict.Ask -> SafetyGate.covered(
                    request, verdict, grantsOf(turn.threadId), turn.screens.latest(), System.currentTimeMillis(), turn.contactNumbers.toSet(),
                )?.let { used ->
                    // Only this call goes through; a grant never unlocks the turn's other sends.
                    journal?.note(turn.runId, "Already approved on the send card: ${spec.name}", ok = true)
                    grants.computeIfPresent(turn.threadId) { _, list -> SafetyGate.used(list, used, request) }
                    // The box now holds the link's text; anything typed after must match it again.
                    if (SafetyGate.effectiveRisk(request) != ToolRisk.COMMIT) turn.lastTyped = null
                    dispatch(args)
                } ?: run {
                    // The card: the gate's title ("Send in WhatsApp?"), then the model's own line
                    // saying who it goes to and what, which the system prompt tells it to write.
                    val said = turn.saying.takeIf { it.isNotBlank() && !verdict.unlocksEgress }
                        ?.let { "\n\n" + SecretPatterns.redact(it).take(MAX_SAID_IN_QUESTION) }.orEmpty()
                    val card = verdict.question + said
                    journal?.ask(turn.runId, card)
                    val allowed = broker.confirm(turn, card)
                    journal?.note(turn.runId, if (allowed) "You allowed ${spec.name}" else "You declined ${spec.name}, or did not answer", ok = allowed)
                    if (!allowed) {
                        ToolOutcome(SafetyGate.DECLINED, isError = true)
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
            }.let { if (it.isError && PhoneAccess.OFF_ERROR in it.text) SourceResult(PhoneAccess.MODEL_MESSAGE, isError = true) else it }
            spec.resultOrigin?.let(turn.taint::mark)
            result.imagePng?.let(turn::stashImage)
            shot = result.shot
            if (!result.isError && turn.mode == RunMode.FOREGROUND) {
                ChatCard.fromToolResult(spec.name, result.text)?.let { turn.emit(AgentEvent.Card(it)) }
            }
            if (!result.isError && spec.risk != ToolRisk.READ && spec.origin == ToolOrigin.PHONE) turn.noteAction()
            val repeat = if (result.isError) turn.noteFailure(callKey(args)) else 0
            val hint = if (repeat >= WARN_AFTER_FAILURES) "\nThis exact call has now failed $repeat times. Do not repeat it." else ""
            // Each look gets an id in the task's screen history, so the model can come back to it.
            if (!result.isError && spec.name in TYPING) {
                (args["text"] ?: args["value"])?.jsonPrimitive?.contentOrNull?.let { turn.lastTyped = it }
            }
            if (!result.isError && spec.name == RESOLVE_CONTACT) {
                PHONE_IN_TEXT.findAll(result.text).forEach { turn.contactNumbers += it.value.filter(Char::isDigit).takeLast(PHONE_TAIL) }
            }
            val screenId = if (!result.isError && spec.name in ContextAssembler.SCREEN_TOOLS) turn.screens.record(result.text) else null
            val text = (screenId?.let { "[$it] " }.orEmpty()) + ContextAssembler.clamp(result.text, MAX_RESULT_CHARS) + hint
            val body = spec.resultOrigin?.takeIf { !result.isError }?.let { Fence.wrap(it, text) } ?: text
            return ToolOutcome(body, result.isError)
        }

        private fun callKey(args: JsonObject): String = spec.name + args.toString()

        /**
         * Whether this call's arguments can leave the phone. Any URL can, whatever the tool is
         * classified as, except a link or intent that lands in an installed app: that app's own
         * Send is the commit, and it is gated there.
         */
        private fun sendsOut(argsText: String): Boolean {
            // Found in the serialised arguments, so a URL nested in an object or array counts too.
            val urls = URL.findAll(argsText).map { it.value }.toList()
            // No URL: an intent or file opened on this phone hands its text to an app that still
            // needs its own Send. A URL anywhere must land in an installed, non-browser app, or a
            // browser could carry private text off the phone in the address.
            if (urls.isEmpty()) return if (spec.name in OPENS_LOCALLY) false else spec.egress || "://" in argsText
            // An app can report the link it was opened with to its own servers, so a link counts
            // as staying on the phone only when its query and fragment hold nothing but the
            // user's own words: "send hi to Amma" may open wa.me/<number>?text=hi; text read off a
            // screen or notification may not ride along unchecked.
            val local = spec.name == OPEN_DEEPLINK || spec.name in OPENS_LOCALLY
            return !(local && urls.all { url -> dictatedOnly(url, turn.request, turn.contactNumbers.toSet()) && runCatching { opensInstalledApp(url) }.getOrDefault(false) })
        }

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
        private const val LOAD_TOOLSET = "load_toolset"
        private const val OPEN_DEEPLINK = "open_deeplink"

        /**
         * True when [url] carries nothing but routing and the user's own words, so handing it to an
         * installed app cannot leak what the turn read.
         *
         * - Every path segment must be a known routing word, a phone-length number (10 to 15
         *   digits: an OTP or PIN is shorter) or a run of the request's words.
         * - Every query name must be a known routing name; every value and the fragment must be a
         *   whole run of the request's words (token equality, not substring), or a number the
         *   request itself contains, or a phone-length number.
         * - Anything holding a secret shape (`SecretPatterns`) fails, whatever else holds.
         * - Fails closed: anything unparsable is not dictated.
         */
        internal fun dictatedOnly(url: String, request: String, contacts: Set<String> = emptySet()): Boolean {
            if (SecretPatterns.containsSecret(decode(url))) return false
            val said = tokens(request)
            fun spoken(text: String): Boolean {
                val t = tokens(text)
                // Punctuation alone ("...-..") carries data the user never said.
                if (t.isEmpty()) return text.isBlank()
                return (0..said.size - t.size).any { start -> said.subList(start, start + t.size) == t }
            }
            // A phone number is routing only where an app expects a recipient; anywhere else a long
            // number could be an account number read off the screen.
            // ...and only a number the user said or `resolve_contact` returned: an injected wa.me/<number>
            // would otherwise carry the user's words to a stranger without a card.
            val sayable = PHONE_IN_TEXT.findAll(request).map { it.value.filter(Char::isDigit).takeLast(PHONE_TAIL) }.toSet() + contacts
            fun recipient(text: String) = text.trimStart('+').let {
                it.all(Char::isDigit) && it.length in PHONE_DIGITS && it.takeLast(PHONE_TAIL) in sayable
            }
            val scheme = url.substringBefore(':').lowercase()
            val afterScheme = url.substringAfter("://", url.substringAfter(':'))
            val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
            val web = scheme == "http" || scheme == "https"
            // The host is data unless it is a known destination: a web link must point at a known
            // app's domain; a custom scheme's host must be a routing word or the user's words.
            val hostOk = '@' !in authority && ':' !in authority && when {
                web -> authority.split('.').let { labels ->
                    labels.takeLast(2).joinToString(".") in KNOWN_WEB_DOMAINS &&
                        (labels.size == 2 || (labels.size == 3 && labels[0] in KNOWN_SUBDOMAINS))
                }
                else -> authority.split('.').filter(String::isNotBlank).all { it in ROUTING_WORDS || spoken(it) }
            }
            val recipientSlot = scheme in PHONE_SCHEMES || (web && authority.split('.').takeLast(2).joinToString(".") in RECIPIENT_DOMAINS)
            val path = afterScheme.substringBefore('?').substringBefore('#').split('/').drop(1).filter(String::isNotBlank).map(::decode)
            val query = afterScheme.substringAfter('?', "").substringBefore('#')
            val fragment = afterScheme.substringAfter('#', "")
            val pathOk = path.withIndex().all { (i, seg) ->
                seg.lowercase() in ROUTING_WORDS || (i == 0 && recipientSlot && recipient(seg)) || spoken(seg)
            }
            val queryOk = query.split('&', ';').filter(String::isNotBlank).all { pair ->
                val name = decode(pair.substringBefore('=')).lowercase()
                val value = decode(pair.substringAfter('=', ""))
                name in ROUTING_NAMES && ((name in RECIPIENT_NAMES && recipient(value)) || spoken(value))
            }
            return hostOk && pathOk && queryOk && spoken(decode(fragment))
        }

        private fun decode(text: String): String = runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

        private fun tokens(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)

        private val PHONE_DIGITS = 10..15
        private const val PHONE_TAIL = 10
        private const val RESOLVE_CONTACT = "resolve_contact"
        private val TYPING = setOf("type_text", "set_value")

        /** A phone number as people write it: digits with spaces or hyphens, optionally +. */
        internal val PHONE_IN_TEXT = Regex("\\+?\\d[\\d\\s-]{8,}\\d")
        private const val GRANT_MS = 10 * 60_000L
        private const val SEND = "Send"
        private const val SEND_IN_CHAT = "Send, don't ask again in this chat"
        private const val CANCEL = "Cancel"
        private const val PROPOSE_DESCRIPTION = """Ask the user to approve a message or email before it is sent or posted. Call once, when the app, recipient and final text are settled. app: the app it goes out through, e.g. "WhatsApp". to: who receives it, as the user would say it. message: the exact final text, word for word. summary: optional one line. The result says approved, cancelled, or edited with the user's new text."""
        private val RECIPIENT_NAMES = setOf("phone", "to")

        /** Schemes whose first path part is a phone number by definition. */
        private val PHONE_SCHEMES = setOf("sms", "smsto", "mms", "mmsto", "tel")

        /** The only subdomains of a known domain that pass; any other could carry data. */
        private val KNOWN_SUBDOMAINS = setOf("www", "m", "api", "open", "music", "maps", "mobile")

        /** Web domains whose first path part is a phone number (`wa.me/<number>`). */
        private val RECIPIENT_DOMAINS = setOf("wa.me", "whatsapp.com")

        /**
         * Web domains of apps people commonly reach by link. A link elsewhere is checked after a
         * private read, because its host could carry data. Compared on the last two labels.
         */
        private val KNOWN_WEB_DOMAINS = setOf(
            "wa.me", "whatsapp.com", "t.me", "telegram.me", "m.me", "google.com", "youtube.com", "youtu.be",
            "spotify.com", "instagram.com", "x.com", "twitter.com", "apple.com", "amazon.com", "amazon.in",
            "flipkart.com", "zomato.com", "swiggy.com", "uber.com", "olacabs.com",
        )

        /** Path words apps use to route a link; they carry no user data. */
        private val ROUTING_WORDS = setOf(
            "send", "search", "chat", "open", "play", "user", "u", "profile", "track", "album", "playlist",
            "artist", "message", "compose", "share", "home", "app", "dl", "go", "dir", "maps", "place",
        )

        /** Query names apps use for routing or for the text to fill in; the values are checked separately. */
        private val ROUTING_NAMES = setOf(
            "text", "q", "query", "phone", "body", "message", "msg", "subject", "search", "term", "to", "destination", "daddr",
        )

        /** A URL or intent link inside JSON text: up to the next quote, backslash or space. */
        private val URL = Regex("""(?i)(?:[a-z][a-z0-9+.\-]*://|intent:)[^\s"\\]+""")

        /** Open something on this phone; their text reaches an app that still needs its own Send. */
        private val OPENS_LOCALLY = setOf("system_intent", "open_file", "launch_app")
        private const val MAX_SAID_IN_QUESTION = 400
        /** Reads that still run one at a time: they wait on the user or change the turn's tools. */
        private val SEQUENTIAL = setOf("ask_user", "load_toolset", "update_plan")
        private const val MAX_RESULT_CHARS = 12_000
        private const val PREVIEW_CHARS = 240
        private const val MAX_OPTIONS = 4
        private const val WARN_AFTER_FAILURES = 2
        private const val BLOCK_AFTER_FAILURES = 4
        private val json = Json { ignoreUnknownKeys = true }

        /** The run log's one-line title for a gate verdict that was not a plain allow. */
        internal fun gateTitle(verdict: Verdict): String = when (verdict) {
            is Verdict.Deny -> "Safety gate refused this call"
            is Verdict.Ask -> if (verdict.unlocksEgress) "Safety gate asked you: private data read, now sending out" else "Safety gate asked you before a commit"
            Verdict.Allow -> "Safety gate allowed this call"
        }

        /** Everything the gate decided on, for the run log: the call as classified, the turn's taint, the verdict. */
        internal fun gateDetail(request: ToolCallRequest, verdict: Verdict, taint: Set<Origin>): String = buildString {
            appendLine("tool: ${request.tool}")
            appendLine("risk: ${request.risk} (effective ${SafetyGate.effectiveRisk(request)})")
            appendLine("origin: ${request.origin}")
            appendLine("sends data out: ${request.egress}")
            request.targetLabel?.let { appendLine("pressed label: $it") }
            appendLine("private or untrusted content this turn: ${taint.joinToString().ifEmpty { "none" }}")
            appendLine(
                when (verdict) {
                    is Verdict.Deny -> "told the model: ${verdict.modelMessage}"
                    is Verdict.Ask -> "asked: ${verdict.question}"
                    Verdict.Allow -> "allowed"
                },
            )
            append("arguments: ${request.argsText}")
        }
    }
}
