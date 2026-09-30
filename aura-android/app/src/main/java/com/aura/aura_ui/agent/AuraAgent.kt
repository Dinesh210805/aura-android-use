package com.aura.aura_ui.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import com.aura.aura_ui.agent.ledger.ActiveRunRegistry
import com.aura.aura_ui.agent.ledger.LedgerUpdateHook
import com.aura.aura_ui.agent.ledger.PauseCheckpoint
import com.aura.aura_ui.agent.ledger.PlanStepStatus
import com.aura.aura_ui.agent.ledger.RunLedger
import com.aura.aura_ui.agent.ledger.RunLedgerController
import com.aura.aura_ui.agent.ledger.ResumePreamble
import com.aura.aura_ui.agent.ledger.RunLedgerPersister
import com.aura.aura_ui.agent.ledger.RunLedgerStore
import com.aura.aura_ui.agent.hitl.AskUserBroker
import com.aura.aura_ui.agent.hitl.attachAskUserTool
import com.aura.aura_ui.agent.ledger.attachLedgerTools
import com.aura.aura_ui.agent.ledger.seededResume
import com.aura.aura_ui.agent.llm.AgentLlmTap
import com.aura.aura_ui.agent.llm.AgentRetryPolicy
import com.aura.aura_ui.agent.llm.AgentTraceTap
import com.aura.aura_ui.agent.llm.GenerationConfig
import com.aura.aura_ui.agent.llm.LlmEndpoint
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.mcp.bridge.CustomEndpointStore
import com.aura.aura_ui.agent.llm.ProviderRegistry
import com.aura.aura_ui.agent.llm.QuotaExhaustion
import com.aura.aura_ui.agent.llm.ReplySanitizer
import com.aura.aura_ui.agent.llm.ToolCallDebris
import com.aura.aura_ui.agent.mcpbridge.ToolCallOutcome
import com.aura.aura_ui.agent.mcpbridge.buildMcpToolRegistry
import com.aura.aura_ui.agent.mcpbridge.client.attachRemoteMcpIfEnabled
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.agent.memory.HistoryMemory
import com.aura.aura_ui.agent.memory.LearnedHintsEnvelope
import com.aura.aura_ui.agent.memory.agentMemoryBlockOrNull
import com.aura.aura_ui.agent.memory.attachLearningsHintsIfAny
import com.aura.aura_ui.agent.memory.attachLearningsWriteHook
import com.aura.aura_ui.agent.memory.attachMemoryTools
import com.aura.aura_ui.agent.memory.memoryService
import com.aura.aura_ui.agent.memory.profileBlockOrEmpty
import com.aura.aura_ui.agent.skills.attachSkillsIfAny
import com.aura.aura_ui.agent.strategy.AgentContextConfig
import com.aura.aura_ui.agent.strategy.BUDGET_ABORT_MESSAGE
import com.aura.aura_ui.agent.strategy.RunBudgetExceeded
import com.aura.aura_ui.agent.strategy.RunBudget
import com.aura.aura_ui.agent.strategy.auraVisionToolLoopStrategy
import com.aura.aura_ui.data.deeplink.DeepLinkManager
import com.aura.aura_ui.mcp.bridge.AppContactsBridge
import com.aura.aura_ui.mcp.bridge.AppDeepLinkBridge
import com.aura.aura_ui.services.AgentStatusRegistry
import com.aura.aura_ui.services.ControlLockStore
import com.aura.aura_ui.mcp.bridge.AppBrowserBridge
import com.aura.aura_ui.mcp.bridge.AppFilesBridge
import com.aura.aura_ui.mcp.bridge.AppDeviceBridge
import com.aura.aura_ui.mcp.bridge.AppMediaBridge
import com.aura.aura_ui.mcp.bridge.AppNotificationBridge
import com.aura.aura_ui.mcp.bridge.AppPerceptionBridge
import com.aura.aura_ui.mcp.bridge.AppScreenshotBridge
import com.aura.aura_ui.mcp.bridge.AppSystemIntentBridge
import com.aura.aura_ui.mcp.bridge.AppUiTreeBridge
import com.aura.aura_ui.mcp.bridge.AppWebSearchBridge
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.mcp.bridge.TavilyKeyStore
import com.aura.aura_ui.mcp.log.AgentRunLogger
import com.aura.mcp.server.InProcessMcpServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * First-party on-device agent (Phase 0 spike).
 *
 * Wires the pieces end-to-end: a dedicated in-process MCP server (sharing the
 * app bridges, enforcing SensitivePolicy + audit) → an in-memory transport →
 * an MCP [Client] → a Koog [ToolRegistry] of all 36 device tools → a Koog
 * [AIAgent] driven by the user's provider/model (Groq + Llama 4 Scout here).
 *
 * [runSpike] now drives the vision-aware tool loop
 * ([auraVisionToolLoopStrategy]): the model perceives the screen, the SoM image
 * is re-injected so it can SEE it, and it acts on numbered `som_id`s. The
 * provider picker and voice UI remain later phases.
 */
class AuraAgent(private val context: Context) {

    /**
     * The browser plane's live state — an actual `WebView` and, while visible, a real
     * `WindowManager` overlay window — held for the lifetime of this [AuraAgent], NOT
     * rebuilt per [runSpike]/[runFromSavedSettings] call the way every other bridge
     * below is.
     *
     * Every other bridge is deliberately per-call (see [runSpike]'s doc): stateless,
     * cheap, torn down in `finally` with nothing lost. The browser bridge broke that
     * assumption — a WebView/overlay window is not free to throw away. Constructing a
     * fresh [AppBrowserBridge] on every call (the previous shape here) meant every new
     * voice command got an empty tab list and opened a SECOND browser window on top of
     * whatever the last command left floating on screen — the first one now orphaned,
     * with nothing left that could ever poll its `handoffState()` again. That silently
     * broke the "I'm done — carry on" button too: tapping it just sets a flag on the
     * abandoned instance, which nothing is left to read. Since [AuraAgent] itself is
     * already held for the whole session by its callers (e.g. `AuraOverlayService`'s
     * `by lazy` instance), hoisting the browser bridge here — instead of the
     * `AppBrowserBridge(context)` inline construction this replaced — is what actually
     * keeps one browser tab, and one live handoff, continuous across turns.
     */
    private val browserBridge by lazy { AppBrowserBridge.shared(context) }

    /**
     * Run a single goal against the on-device tools. Logs tool discovery and the
     * final result to Logcat (tag [TAG]). Returns the agent's text answer.
     */
    suspend fun runSpike(
        apiKey: String,
        goal: String,
        endpoint: LlmEndpoint = LlmEndpointCatalog.BUILTINS.first(),
        modelId: String? = null,
        generation: GenerationConfig = GenerationConfig(),
        onEvent: (AgentStreamEvent) -> Unit = {},
        // Build 3 (resume): when non-null, this run continues an interrupted one — the controller
        // starts from the seeded ledger and [resumePreamble] is prepended to the model's first input.
        seedLedger: RunLedger? = null,
        resumePreamble: String? = null,
        // Write this run into the diary (ACTION_LOG). Off unless the caller knows a PERSON asked:
        // the eval suite and AndroidWorld reach runSpike through the same doors, and a benchmark
        // sweep filed as history would leak earlier tasks into later ones' recall.
        remember: Boolean = false,
    ): String {
        // Hoisted (was inline) so the same bridge answers the turn-0 "where am I"
        // preamble below — one accessibility read, no extra tool call.
        val deviceBridge = AppDeviceBridge(context)
        val handle = InProcessMcpServer.connect(
            deviceBridge = deviceBridge,
            screenshotBridge = AppScreenshotBridge(context),
            uiTreeBridge = AppUiTreeBridge(),
            perceptionBridge = AppPerceptionBridge(context),
            webSearchBridge = AppWebSearchBridge(TavilyKeyStore(context)),
            deepLinkBridge = AppDeepLinkBridge(context, DeepLinkManager(context)),
            notificationBridge = AppNotificationBridge(context),
            mediaBridge = AppMediaBridge(context),
            systemIntentBridge = AppSystemIntentBridge(context),
            contactsBridge = AppContactsBridge(context),
            filesBridge = AppFilesBridge(context),
            browserBridge = browserBridge,
            ownPackageName = context.packageName,
            // Spec 2026-07-31 — control lock. The local agent yields to the human too:
            // the ordering is human > local agent > MCP client, so a pause that only
            // reached remote clients would leave the on-device agent still driving.
            pausedByHuman = { ControlLockStore.shared.isPausedByHuman },
            // Every server gate and post step lands in this run's log (spec 2026-09-23 §3.2).
            trailSink = com.aura.mcp.server.ToolTrailSink { tool, stage, name, verdict, detail, ms ->
                AgentTraceTap.reportStep(tool, stage, name, verdict, detail, ms)
            },
            readScreenImage = { com.aura.aura_ui.data.preferences.ReadScreenImageStore.enabled.value },
        )

        val mcpClient = Client(clientInfo = Implementation(name = CLIENT_NAME, version = CLIENT_VERSION))

        // Forensic logger for this run — writes a LangSmith-style trace (command,
        // tool calls + coords + screenshots, LLM round-trips) into the shared
        // mcp_logs store so the in-app "Logs" screen and Claude Code can review it.
        val runLogger = AgentRunLogger(context, AppScreenshotBridge(context))

        // Spec 2026-07-31 — control lock, the ARMING half. A human touch only means
        // "give me my phone back" while something is driving it; outside a run it is just
        // somebody using their phone, and treating it as a pause is what left AURA
        // reporting "Paused" before it had been asked to do anything. Set here rather
        // than at the call sites because this is the one funnel every local run passes
        // through — fresh runs and resumed ones alike.
        ControlLockStore.shared.noteLocalRunStarted()
        // Name the task at the SAME funnel, for the same reason. noteTaskStarted was wired
        // only into the overlay's text entry, so every other way in — voice, the Gemini Live
        // handoff, a resumed run, the debug harness — left activeTask null. That silently
        // degrades both things it exists for: the refusal that should say "I'm paused on
        // messaging mum" cannot name the task, and neither can the pause checkpoint. Measured
        // on device 2026-08-04: a real harness run reported activeTask=null throughout.
        ControlLockStore.shared.noteTaskStarted(goal)

        var endReason = "completed"
        // What the run said last, for the diary line written in the finally.
        var finalReply: String? = null
        var toolStartedAt = 0L
        // Held so the run's finally can record the meters: both are built deep inside the try,
        // after provider resolution, but the run that trips a ceiling is exactly the one whose
        // numbers must not be lost.
        var budgetForTrace: RunBudget? = null
        var contextConfigForTrace: AgentContextConfig? = null
        var contextWindowForTrace: Int? = null
        // Sub-project #2: enabled remote MCP servers are attached here, additively and behind a
        // gate. Held so the run's finally can release the remote HTTP client.
        var remoteAttachment: com.aura.aura_ui.agent.mcpbridge.client.RemoteMcpAttachment? = null

        // Resolved early (pure lookups) so the run ledger can capture provider/model identity.
        // Fall back to THIS endpoint's default, not a global one: the global constant is a Groq
        // model id, so a user who configured Gemini/Anthropic but never opened the model picker
        // used to have a Groq id posted to their provider, failing every run with a 400.
        // P1: the vision capability computed at model-pick time finally reaches the wire. Null
        // (nothing published, or a custom endpoint) still declares vision — see modelFor.
        val selectedVisionCapable = ProviderKeyStore(context).getSelectedModelVisionCapable(endpoint.id)
        val llmModel = ProviderRegistry.modelFor(
            endpoint,
            modelId ?: endpoint.defaultModelId.ifBlank { LlmEndpointCatalog.DEFAULT_MODEL_ID },
            selectedVisionCapable,
        )

        // The Run Ledger — harness-owned working memory for this run (goal, plan checklist,
        // executed-step digest, facts, dead ends). Re-rendered into context every turn by the
        // vision strategy, so history eviction/compaction can never lose the plot. Plan progress
        // is mirrored to the trace tap so the Logs screen shows live "k/n done".
        //
        // Build 3 (resumable runs): mutations write through to the encrypted [RunLedgerStore],
        // debounced by [RunLedgerPersister]. A resume seeds the controller from the prior ledger
        // ([seedLedger]); the provider/model are refreshed to whatever we actually run with now.
        val ledgerStore = RunLedgerStore(EncryptedJsonStore(context, RunLedgerStore.STORE_NAME))
        val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ledgerPersister = RunLedgerPersister(ledgerStore, persistScope)
        val initialLedger = (
            seedLedger ?: RunLedger(
                runId = newRunId(),
                goal = goal,
                provider = endpoint.id,
                modelId = llmModel.id,
                startedAtMs = System.currentTimeMillis(),
            )
            ).copy(provider = endpoint.id, modelId = llmModel.id)
        val ledgerController = RunLedgerController(
            initialLedger,
            onMutated = { ledger ->
                if (ledger.planSteps.isNotEmpty()) {
                    AgentTraceTap.reportPlanUpdate(
                        done = ledger.planSteps.count { it.status == PlanStepStatus.DONE },
                        total = ledger.planSteps.size,
                        steps = ledger.planSteps.map { "${it.status.name.lowercase()}: ${it.text}" },
                    )
                }
                ledgerPersister.onMutated(ledger)
            },
        )

        // Publish this run so the conversation plane can steer it by voice mid-task. The ledger
        // is the ONLY channel into a running agent's context (the vision strategy re-renders it
        // before every LLM request), and until now nothing outside runSpike could reach the
        // controller at all. Registered here — after the controller exists, before the first
        // turn — and released in the finally below, keyed by run id.
        ActiveRunRegistry.shared.register(initialLedger.runId, ledgerController)

        // Spec 2026-07-31 — the checkpoint half of "pause must checkpoint, not merely stop".
        //
        // Writing a fact (rather than setting a status field) is deliberate twice over: facts
        // are re-rendered into the model's context every turn, so a resumed run is *told* the
        // user interrupted it instead of silently re-deriving that; and mutating the ledger is
        // what drives the write-through. flush() then forces it to disk synchronously —
        // RunLedgerPersister debounces by 500ms, and the whole point of this checkpoint is to
        // survive a force-stop that can land inside that window.
        //
        // Lives on persistScope so it dies with the run; a collector outliving its run would
        // checkpoint a ledger nobody is writing to any more.
        persistScope.launch {
            var checkpointed = false
            ControlLockStore.shared.pausedByHuman.collect { paused ->
                if (!paused) {
                    // Re-arm, so a second interruption in the same run checkpoints again.
                    checkpointed = false
                    return@collect
                }
                if (checkpointed) return@collect
                checkpointed = true
                runCatching {
                    ledgerController.noteFact(
                        PauseCheckpoint.checkpointNote(ControlLockStore.shared.activeTask),
                    )
                    ledgerPersister.flush()
                }
            }
        }

        // Screen keep-awake for the whole run: gestures and screen capture both fail on a
        // non-interactive display, so the phone sleeping mid-task kills the run. Acquired
        // inside the try so the finally below is guaranteed to release it. Goes through the
        // shared ScreenAwakeController so an agent run and an MCP session ref-count one
        // mechanism instead of fighting over the display.
        var keepAwake: com.aura.aura_ui.services.keepawake.ScreenAwakeController.Lease? = null

        // E6 — RunEnd lane: hooks needing an end-of-run flush (learnings) register here;
        // the finally below fires them once, NonCancellable, whatever the outcome.
        var runEndHooks: List<com.aura.aura_ui.agent.mcpbridge.hooks.RunEndHook> = emptyList()

        // Pre-task research runs beside the run, never awaited; cancelled in the finally below.
        var researchScope: CoroutineScope? = null

        return try {
            keepAwake = com.aura.aura_ui.services.keepawake.ScreenAwake
                .controller(context).acquire("agent_run")
            mcpClient.connect(handle.clientTransport)

            // Sub-project #4: gated learnings write-hook threaded into the tool-call chain. With
            // learnings disabled this is null → extraPostHooks empty → byte-for-byte today's chain.
            // The hook accumulates a PII-scrubbed step trail and writes a verified path ONLY on an
            // allowed end_session (ActionGuard's faithful-completion gate = the success signal).
            // The ledger hook rides the same seam: every actionable step + dead end feeds the ledger.
            val learningsHook = attachLearningsWriteHook(context, goal)
            // Proof-backed completion (spec 2026-09-15). ObservedText collects what the run
            // actually reads so `record_finding` can check a quote against it; the gate hook
            // then refuses a success claim that the collected proof does not support.
            val observedText = com.aura.aura_ui.agent.proof.ObservedText()
            val completionJudge = com.aura.aura_ui.agent.proof.CompletionJudge(context)
            // The overseer: every `mark_step done` is checked against the screen the run is on,
            // and so is a no-plan run's success claim at end_session.
            val stepChecker = com.aura.aura_ui.agent.proof.StepChecker(context, observedText)
            val completionGate = com.aura.aura_ui.agent.proof.CompletionGateHook(
                ledgerController,
                completionJudge,
                observedText,
                stepChecker,
            )
            val gated = buildMcpToolRegistry(
                mcpClient,
                extraPostHooks = listOfNotNull(learningsHook, LedgerUpdateHook(ledgerController), observedText),
                extraPreHooks = listOf(completionGate),
                // T12 — Confirm decisions surface as an ask_user question (chips in
                // the overlay, spoken on the Live plane); silence declines.
                confirm = run {
                    val ask = com.aura.aura_ui.agent.hitl.confirmViaAskUser(AskUserBroker.shared)
                    // The strip says why the run is waiting — the one moment the user MUST look.
                    val held: suspend (String) -> Boolean = { question ->
                        // About the call waiting on this answer — it is the open one right now.
                        narrate(AgentStatusRegistry.Kind.HOLD, "Waiting for you: $question", ledgerController, aboutCall = true)
                        ask(question)
                    }
                    held
                },
                headline = { planHeadline(ledgerController) },
                // Names apps in the status strip's sentences ("Reading the Apple Music screen").
                // runCatching because a package can vanish between the observation and this read.
                appLabel = { pkg ->
                    runCatching {
                        val pm = context.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrNull()
                },
            )
            // E6 — collect the RunEnd participants (today: the learnings flush).
            runEndHooks = listOfNotNull(learningsHook)
                .filterIsInstance<com.aura.aura_ui.agent.mcpbridge.hooks.RunEndHook>()
            Log.i(TAG, "Discovered ${gated.registry.tools.size} MCP tools:")
            gated.registry.tools.forEach { Log.i(TAG, "  • ${it.name}") }

            // OFF by default: with no enabled remote server (or none reaching Connected) this is
            // null and `toolRegistry`/`SYSTEM_PROMPT` below are byte-for-byte today's behaviour.
            remoteAttachment = attachRemoteMcpIfEnabled(context, mcpClient, gated.registry, gated.chain)
            val toolRegistry = remoteAttachment?.registry ?: gated.registry
            // Who the user is (name + the languages AURA may speak). Human-authored in
            // Settings, never model- or screen-written, so it is the one piece of user data
            // that belongs at SYSTEM authority - the language rule has to bind, not advise.
            // Empty string on a fresh install, leaving the prompt byte-for-byte unchanged.
            val profileBlock = profileBlockOrEmpty(context)
            // WHAT it works on, beside who it works for. `get_device_status` carries the same
            // facts but costs a turn to fetch and reports "OnePlus CPH2661" where a help page
            // says "OnePlus Nord 4" — so the searchable name rides the prompt instead. Factory
            // properties and Build constants only, so like the profile it is safe at SYSTEM
            // authority; fail-soft to "" on a device that answers none of them.
            val deviceBlock = runCatching { DeviceBlock.render(readDeviceFacts(context)) }.getOrDefault("")
            // The doctrine opens with who AURA is. The Live plane's AuraCapabilities paragraph is
            // not sent here: this agent's capabilities ARE its tool list, generated per run. The
            // doctrine names no conditionally-registered tool, so it needs no per-run gating.
            val identityPrompt = SYSTEM_PROMPT +
                (if (profileBlock.isBlank()) "" else "\n\n" + profileBlock) +
                (if (deviceBlock.isBlank()) "" else "\n\n" + deviceBlock)
            val effectiveSystemPrompt = remoteAttachment?.let { identityPrompt + "\n\n" + it.instructions } ?: identityPrompt
            remoteAttachment?.let {
                Log.i(TAG, "Remote MCP attached — ${toolRegistry.tools.size} total tools (incl. proxy)")
            }

            // Skills (Sub-project #3): additive + gated. With no bundled/user skill this is null and
            // `finalRegistry`/`finalSystemPrompt` are exactly `toolRegistry`/`effectiveSystemPrompt`.
            // A skill is prompt text only — it can never grant a tool or bypass the hook chain.
            // Ledger plan tools (set_plan/mark_step) are client-side pure bookkeeping — always on.
            val skillAttachment = attachSkillsIfAny(context, toolRegistry)
            // ask_user (HITL): client-side rendezvous with the overlay/Live UI — always on,
            // like the ledger tools. Question/answer text never enters learnings.
            // Memory (recall/save/forget): client-side, always on, like the ledger tools.
            // This is what lets the agent lane CORRECT what it knows instead of only reading
            // it - before this, only the (off-by-default) Live plane could touch memory.
            // One store handle for the run: the tools and the injected block below share it,
            // rather than opening the Keystore-backed prefs twice.
            val memory = memoryService(context)
            // Aux model calls for web research (pre-task and look_up) — their own client, so they
            // can never spend the run's retry or server-wait budget.
            val researchClient = ProviderRegistry.clientFor(endpoint, apiKey, llmModel.id)
            val researchAsk: suspend (String, String) -> String? = { instruction, input ->
                val reply = researchClient.execute(
                    prompt("aura-research") {
                        system(instruction)
                        user { text(input) }
                    },
                    llmModel,
                )
                (reply as? Message.Assistant)?.textContent()
            }
            val researchWeb = com.aura.aura_ui.agent.research.WebResearch(AppBrowserBridge.research(context))
            val deviceFacts = readDeviceFacts(context)
            val attachedRegistry = com.aura.aura_ui.agent.research.attachLookUpTool(
                com.aura.aura_ui.agent.research.LookUp(
                    search = { q -> researchWeb.search(q, listOf(deviceFacts.maker)) },
                    ask = researchAsk,
                    setting = "${deviceFacts.name}, Android ${deviceFacts.androidRelease}",
                ),
                attachAskUserTool(
                    AskUserBroker.shared,
                    attachMemoryTools(
                        memory,
                        attachLedgerTools(
                            ledgerController,
                            com.aura.aura_ui.agent.proof.attachProofTools(
                                ledgerController,
                                observedText,
                                skillAttachment?.registry ?: toolRegistry,
                            ),
                            checkStep = stepChecker::check,
                        ),
                    ),
                ),
            )
            // A1 — every tool stays registered; requests carry schemas only for the core set plus
            // what the model loads. The rest are menu lines in the inventory below.
            val deferred = DeferredTools(attachedRegistry.tools.map { it.name } + LoadToolsKoogTool.NAME)
            // The goal's app and its catalog links, known before turn 1 (see AppLinksPreamble).
            // Installed apps are read once here and handed to research below.
            val installedApps = com.aura.aura_ui.agent.research.TaskResearch.launcherApps(context)
            val goalApp = runCatching { com.aura.aura_ui.agent.research.ResearchText.matchApp(goal, installedApps) }.getOrNull()
            val appLinksNote = goalApp?.let { app ->
                runCatching {
                    val catalog = context.assets.open(AppDeepLinkBridge.CATALOG_ASSET).bufferedReader().use { it.readText() }
                    AppLinksPreamble.forApp(app.label, AppLinksPreamble.catalogEntries(catalog, app.packageName))
                }.getOrNull()
            }
            // The note tells the model to call open_deeplink — so its schema is sent from turn 1
            // rather than costing a load_tools round trip first (F4).
            if (appLinksNote != null) deferred.load(listOf("open_deeplink"))
            val finalRegistry = attachedRegistry + ai.koog.agents.core.tools.ToolRegistry {
                tool(LoadToolsKoogTool(deferred))
            }
            val onDemand = deferred.onDemand().toSet()
            val toolMenu = finalRegistry.tools.filter { it.name in onDemand }
                .associate { it.name to ToolInventorySection.hint(it.name, it.descriptor.description) }
            Log.i(TAG, "Deferred tools: ${toolMenu.size} of ${finalRegistry.tools.size} behind the menu")
            val skillAwarePrompt = skillAttachment?.let {
                effectiveSystemPrompt + "\n\n# Skills — if one matches the goal, load it with use_skill before you start\n" + it.listing
            } ?: effectiveSystemPrompt
            // Tool inventory LAST and generated from the final registry, so the prompt's tool
            // awareness includes everything attached above (remote proxy, skills, ledger,
            // ask_user) and can never go stale as tools are added or renamed.
            val finalSystemPrompt = skillAwarePrompt + "\n\n" +
                ToolInventorySection.render(finalRegistry.tools.map { it.name }, toolMenu)
            skillAttachment?.let {
                Log.i(TAG, "Skills attached — ${finalRegistry.tools.size} total tools (incl. list_skills/use_skill)")
            }

            // Sub-project #4 (read side): learned hints for this goal. Null when nothing is
            // learned → unchanged input. M1: hints are screen-derived (attacker-controllable)
            // so they ride the USER/goal channel inside an explicit untrusted-data fence —
            // never the system prompt, which would give them durable system authority.
            val learningsHints = attachLearningsHintsIfAny(context, goal)
            val baseInput = LearnedHintsEnvelope.forGoal(goal, learningsHints) ?: goal
            // F7 — tell the model which app is on screen BEFORE its first move, so a
            // task about the app the user is already in doesn't start with a relaunch
            // that discards their current screen. Screen-derived (so user channel, like
            // hints); null when accessibility is off or AURA itself is in front.
            val foregroundNote = runCatching {
                val fg = deviceBridge.foregroundPackage()
                ForegroundPreamble.forForeground(
                    packageName = fg,
                    appLabel = fg?.let { deviceBridge.appLabelFor(it) },
                    ownPackage = context.packageName,
                )
            }.getOrNull()
            val withForeground = foregroundNote?.let { "$it\n\n$baseInput" } ?: baseInput
            // Same idea as the foreground note, for the browser (2026-08-05). A voice command
            // starts a fresh run with no memory of the last one, so "search for an item" had
            // no reason to know amazon.in was already open and called browser_open again,
            // spawning a second tab. Page titles are page-controlled, so this rides the USER
            // channel next to the goal exactly like the foreground note.
            val browserNote = runCatching {
                BrowserPreamble.forTabs(
                    browserBridge.openTabsForPreamble().map { (title, url, active) ->
                        BrowserPreamble.OpenTab(title = title, url = url, active = active)
                    },
                )
            }.getOrNull()
            val withBrowser = browserNote?.let { "$it\n\n$withForeground" } ?: withForeground
            // What AURA remembers about the user. Model-written (save_memory can be talked
            // into anything by a hostile screen), so it rides the USER channel inside an
            // explicit untrusted fence - never the system prompt, exactly like hints (M1).
            val memoryBlock = agentMemoryBlockOrNull(memory)
            val withLinks = appLinksNote?.let { "$it\n\n$withBrowser" } ?: withBrowser
            val withMemory = memoryBlock?.let { "$withLinks\n\n$it" } ?: withLinks
            // On resume, lead with the preamble so the model re-verifies the live screen before
            // trusting the ledger's prior-step effects (the ledger itself is injected per-turn).
            val runInput = resumePreamble?.let { "$it\n\n$withMemory" } ?: withMemory
            learningsHints?.let { Log.i(TAG, "Learned hints attached to the goal (user channel, fenced)") }

            // Context discipline (provider/model-agnostic): bound token growth + pace the run.
            // Scaled to THIS model's context window when the provider published one at model-pick
            // time, rather than the old fixed 12k — which was a Groq rate-limit number doing duty
            // as a context threshold, and so was too tight on a large-window model and too loose
            // on a small one. Unknown window falls back to exactly the previous behaviour.
            // Derived BEFORE the client so the retry policy has one source rather than defaulting
            // to AgentContextConfig.DEFAULT behind the run's back (F2).
            val providerKeyStore = ProviderKeyStore(context)
            val selectedContextWindow = providerKeyStore.getSelectedModelContextWindow(endpoint.id)
            val contextConfig = AgentContextConfig.forContextWindow(selectedContextWindow)
                // Per-endpoint, user-set, and null unless they asked for it: a free key with a
                // daily request quota wants a cap, a paid key does not, and the app cannot tell
                // which this is. See AgentContextConfig.runMaxRequests.
                .copy(runMaxRequests = providerKeyStore.getRunMaxRequests(endpoint.id))
            contextConfigForTrace = contextConfig
            contextWindowForTrace = selectedContextWindow
            val llmClient = ProviderRegistry.clientFor(endpoint, apiKey, llmModel.id, generation, contextConfig)

            // Pre-task research (spec 2026-09-23 §6): official help for this task on this phone,
            // looked up in parallel with turn 1 and dropped into the ledger if and when it lands.
            // Its own LLM client, so the phrase and page-reading calls can never spend the run's
            // retry or server-wait budget; pages are fetched over plain HTTPS, outside the hook
            // chain, so ActionGuard cannot mistake research for the agent touching the screen.
            // Logging starts first: research runs beside turn 1 and its events need a sink to land in.
            runLogger.startRun(goal, agentLabel = "${endpoint.displayName} / ${llmModel.id}")
            researchScope = CoroutineScope(
                kotlinx.coroutines.currentCoroutineContext() +
                    SupervisorJob(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) +
                    Dispatchers.IO,
            ).also { scope ->
                scope.launch {
                    val note = com.aura.aura_ui.agent.research.TaskResearch(
                        ask = researchAsk,
                        fetch = { url -> com.aura.aura_ui.agent.research.TaskResearch.httpGet(url) },
                        web = { question, hints -> researchWeb.search(question, hints) },
                        onTrace = AgentTraceTap::reportResearch,
                    ).research(
                        goal,
                        installedApps,
                        readDeviceFacts(context),
                    )
                    note?.let {
                        ledgerController.setResearch(it)
                        narrate(
                            AgentStatusRegistry.Kind.LEARN,
                            "Read ${it.source}'s help — ${firstSentence(it.text)}",
                            ledgerController,
                            aboutCall = false,
                        )
                    }
                }
            }

            // Phase 2: Report the run assembly to the trace tap (after startRun installs it).
            // RemoteMcpAttachment composes all enabled servers behind a proxy (it has no per-server
            // name); report it as one entry, with the proxy tool count = total minus the local base.
            val remoteServers = remoteAttachment?.let { ra ->
                val proxyToolCount = (ra.registry.tools.size - gated.registry.tools.size).coerceAtLeast(0)
                listOf(AgentTraceTap.RemoteServerInfo("remote MCP (proxied)", proxyToolCount, ra.instructions))
            } ?: emptyList()
            val skills = skillAttachment?.listing?.lines()?.map { line ->
                line.substringBefore(" —").substringBefore(" —").trim()
            }?.filter { it.isNotBlank() } ?: emptyList()
            AgentTraceTap.reportAssembly(
                localToolCount = gated.registry.tools.size,
                remoteServers = remoteServers,
                skills = skills,
                injectedHints = learningsHints,
            )

            // Wall-clock always; requests only if the user opted into a cap. Tokens are counted
            // and reported but end nothing — see RunBudget's KDoc for the bench evidence (8/25
            // runs killed by a cumulative token sum that re-counted the same prompt every turn).
            val tokenBudget = RunBudget(
                maxRequests = contextConfig.runMaxRequests,
                maxWallClockMs = contextConfig.runMaxWallClockMs,
            )
            // F1+F3: every LLM call passes the tap, so this is where requests are counted and the
            // provider's own token totals land. The strategy cannot do it — it never sees turn 0 or
            // a nudge re-request. Delivered on an OkHttp thread; RunBudget synchronizes for that.
            // cachedTokens rides along because the budget does not charge for a prompt slice the
            // provider served from its own cache — see RunBudget.recordWireCall for why.
            AgentLlmTap.usageSink = { usage ->
                tokenBudget.recordWireCall(usage.totalTokens, usage.cachedTokens)
            }
            // NOTE: the model's raw reply is deliberately NOT piped to the status strip.
            // `message.content` is whatever prose the model wrote — planning, thinking-model
            // reasoning echo, multi-paragraph commentary — not the one line the doctrine asked
            // for, and it outranked the clean tool-derived line. The strip is fed by
            // `StatusNarrationHook` alone, from what tools actually returned.
            budgetForTrace = tokenBudget

            val agent = AIAgent(
                // Executor key = the model's own provider, so OpenAI-compat models
                // (Groq/OpenRouter → LLMProvider.OpenAI) and future Google models
                // each route to the matching client.
                promptExecutor = DeferredToolsExecutor(deferred, llmModel.provider to llmClient),
                llmModel = llmModel,
                // Vision-aware loop: re-injects perceive_screen's SoM image so the
                // model actually SEES the screen (the stock loop strips it to text).
                strategy = auraVisionToolLoopStrategy(
                    contextConfig,
                    tokenBudget,
                    ledgerController,
                    // Both halves of P1 move together: withholding the capability while still
                    // attaching screenshots would 400 every turn.
                    visionEnabled = selectedVisionCapable != false,
                    // A6 — the harness looks after each gesture. Through the run's ONE chain, like
                    // set_plan's search, so ActionGuard grounds on it and SensitivePolicy still refuses.
                    // F17: logged as its own read_screen step, marked as AURA's, so the trace shows the
                    // read, its drawing and its time — and its hook steps stop landing under research (F6).
                    observeScreen = {
                        val startedAt = System.currentTimeMillis()
                        runLogger.onToolStart("read_screen", "{}", origin = "aura (after action)")
                        var outcome = ToolCallOutcome.Outcome(success = false, summary = "read after action failed")
                        try {
                            gated.chain.runGatedToolCall(
                                "read_screen",
                                kotlinx.serialization.json.JsonObject(emptyMap()),
                                com.aura.aura_ui.agent.mcpbridge.hooks.HookContext(confirm = { false }),
                            ) { effective -> mcpClient.callTool(name = "read_screen", arguments = effective) }
                                .also { outcome = ToolCallOutcome.from(it) }
                        } finally {
                            runLogger.onToolEnd("read_screen", outcome.success, outcome.summary, System.currentTimeMillis() - startedAt)
                        }
                    },
                ),
                toolRegistry = finalRegistry,
                systemPrompt = finalSystemPrompt,
                // Koog counts NODE executions, not turns, and this strategy's steady-state loop is
                // two nodes per turn (nodeExecuteTool → nodeSendToolResultsWithVision) plus a third
                // on a defective turn. So 50 was ~25 turns — with the token ceiling gone it would
                // have become the new thing that kills long tasks. Kept deliberately looser than
                // any user-set request cap so RunBudget owns the ending, which is the path that
                // speaks honestly and gets a closing turn. Claude Code caps its own unattended
                // subagents at 200 turns; this is the same posture, in node units.
                maxIterations = 500,
            ) {
                // Stream the live agent process (tool calls + outputs) to [onEvent]
                // AND persist it via [runLogger]. Co-exists with the custom strategy
                // above (zero graph changes). [toolStartedAt] tracks per-tool timing
                // since Koog's tool events don't carry a duration.
                handleEvents {
                    onToolCallStarting { e ->
                        // A model that calls a menu tool without loading it still gets it executed
                        // (it is registered); from here on its schema rides every request too.
                        deferred.load(listOf(e.toolName))
                        toolStartedAt = System.currentTimeMillis()
                        val args = e.toolArgs.toString()
                        runLogger.onToolStart(e.toolName, args)
                        onEvent(AgentStreamEvent.ToolStarting(e.toolName, args))
                    }
                    onToolCallCompleted { e ->
                        // O1: MCP failures don't throw — they complete with isError=true —
                        // so success is read from the result, not hardcoded. O3: the
                        // summary elides image payloads instead of stringifying them.
                        val outcome = ToolCallOutcome.from(e.toolResult)
                        runLogger.onToolEnd(
                            e.toolName,
                            success = outcome.success,
                            outputSummary = outcome.summary,
                            durationMs = System.currentTimeMillis() - toolStartedAt,
                        )
                        if (outcome.success) {
                            onEvent(AgentStreamEvent.ToolCompleted(e.toolName, outcome.summary.take(RESULT_SUMMARY_CAP)))
                        } else {
                            onEvent(AgentStreamEvent.ToolFailed(e.toolName, outcome.summary.take(RESULT_SUMMARY_CAP)))
                        }
                    }
                    onToolCallFailed { e ->
                        val err = e.message ?: "error"
                        runLogger.onToolEnd(
                            e.toolName,
                            success = false,
                            outputSummary = err,
                            durationMs = System.currentTimeMillis() - toolStartedAt,
                        )
                        onEvent(AgentStreamEvent.ToolFailed(e.toolName, err))
                    }
                }
            }

            Log.i(TAG, "▶ Running goal on ${endpoint.id}/${llmModel.id}: $goal")
            // B13: reasoning models inline <think>…</think> in content — strip it here,
            // at the one seam every consumer (chat bubble, TTS, drive_phone feedback)
            // shares. Reasoning stays available in the trace via AgentLlmTap.
            val raw = agent.run(runInput)
            // Then, at the same seam: a turn where the model wrote its tool call as TEXT rather
            // than calling it. Four runs in the 2026-08-26 suite spoke raw markup aloud — three
            // of them scored PASS, because every truth check reads the screen and none reads the
            // utterance. ToolCallDebris salvages the sentence buried in the debris where there
            // is one (usually the fake call's `reason`) and yields nothing where there is not.
            val result = if (raw.isBlank()) {
                raw
            } else {
                ToolCallDebris.clean(ReplySanitizer.stripThinking(raw)).ifBlank { NO_FINAL_ANSWER_MESSAGE }
            }
            // Proof-backed completion (spec 2026-09-15). A run that returned prose never called
            // end_session, so it claimed nothing and the gate hook holds no verdict — ask the
            // judge here instead of letting the old default "completed" stand (problem D1). Then
            // append the harness's correction when it disagrees, so the user hears "I only got 7
            // of the 10" rather than a confident summary of work that did not happen.
            val outcome = completionGate.outcome ?: runCatching {
                completionJudge.judge(ledgerController.snapshot(), claimed = null, answer = result, observed = observedText)
                    .also { ledgerController.setOutcome(it) }
            }.getOrNull()
            // Prepended, not appended: the Live plane caps this string at
            // McpPayloadGuard.MAX_DESC_CHARS by taking the HEAD, so a tail footnote would be
            // dropped from exactly the long summaries this feature exists to correct.
            // Then the model's answer, then the per-step ✓/✗ list built from what the step checker
            // accepted. The list goes last: the Live plane keeps the HEAD of this string, and on a
            // lookup task the answer itself ("your caption says …") must be what survives the cut.
            val verified = listOfNotNull(
                outcome?.let { com.aura.aura_ui.agent.proof.CompletionGate.correctionFor(it) },
                result.takeIf { it.isNotBlank() },
                com.aura.aura_ui.agent.proof.CompletionGate.stepReport(ledgerController.snapshot()),
            ).joinToString("\n\n")
            Log.i(
                TAG,
                "✅ Agent result: $verified (verdict=${outcome?.verdict ?: "none"}, " +
                    "proof ${ledgerController.snapshot().findings.size}/${ledgerController.snapshot().targetCount ?: 0}, " +
                    "guard blocked ${gated.guard.blindBlocks} blind, ${gated.guard.loopBlocks} looping gesture(s))",
            )
            onEvent(AgentStreamEvent.Finished(verified))
            finalReply = verified
            verified
        } catch (t: Throwable) {
            // Clean budget abort: the loop throws RunBudgetExceeded (possibly wrapped by Koog)
            // when the per-run token budget is spent. Return an honest, spoken message instead
            // of surfacing a crash — the run did real work, it just stopped before finishing.
            val causes = generateSequence(t as Throwable?) { it.cause }.toList()
            val budgetHit = causes.any { it is RunBudgetExceeded }
            // Koog's own ceiling. Until the token budget was removed this was unreachable — the
            // budget always tripped first — so it fell through to the `throw t` below and the user
            // got a crash instead of a sentence. It is now a real ending and must speak like one.
            val iterationsHit = causes.any { it is AIAgentMaxNumberOfIterationsReachedException }
            // Spec 2026-08-05: the provider's own retry ladder (RetryingLLMClient, 6 attempts,
            // honours Retry-After) is already spent by the time this lands. Grinding further
            // will not help, and ending silently leaves the user watching a frozen phone with
            // no idea their free-tier limit is the reason — which is how 2 of 5 captured runs
            // ended on device.
            val quotaReason = QuotaExhaustion.spokenReasonFor(t)
            if (budgetHit || iterationsHit) {
                Log.i(TAG, "⏹ Run ceiling reached (${if (iterationsHit) "iterations" else "budget"}) — clean abort")
                endReason = if (iterationsHit) "iterations" else "budget"
                // RunBudgetExceeded carries the meter-specific line ("ran out of time" vs "used up
                // my budget"); Koog's exception carries a stack trace, so it borrows the generic one.
                val spoken = causes.filterIsInstance<RunBudgetExceeded>()
                    .firstOrNull()?.message ?: BUDGET_ABORT_MESSAGE
                onEvent(AgentStreamEvent.Finished(spoken))
                spoken
            } else if (quotaReason != null) {
                Log.i(TAG, "⏹ Provider quota exhausted — clean abort")
                endReason = "quota"
                onEvent(AgentStreamEvent.Finished(quotaReason))
                quotaReason
            } else {
                Log.e(TAG, "❌ Agent run failed: ${t.message}", t)
                endReason = if (t is kotlinx.coroutines.CancellationException) "cancelled" else "failed"
                throw t
            }
        } finally {
            // Stop advertising this run for voice steering. Keyed by run id, so a slow teardown
            // arriving after a newer run registered cannot unregister that newer run. First in
            // the finally: a steer landing on a run that is already unwinding is pointless, and
            // every line below can suspend.
            runCatching { ActiveRunRegistry.shared.unregister(initialLedger.runId) }
            researchScope?.cancel()
            // Record what the run spent against what it was allowed to spend, BEFORE any sink is
            // cleared — AgentRunLogger.endRun() nulls the trace sink, and the interesting case (a
            // ceiling tripped) is exactly the one that must not go unrecorded. None of these
            // numbers is observable any other way: the context window lives in
            // EncryptedSharedPreferences and the meters live only in this run's memory.
            runCatching {
                val b = budgetForTrace
                val cfg = contextConfigForTrace
                if (b != null && cfg != null) {
                    val retry = AgentRetryPolicy.configFor(cfg)
                    AgentTraceTap.reportBudget(
                        AgentTraceTap.BudgetEvent(
                            requests = b.requests(),
                            maxRequests = cfg.runMaxRequests,
                            tokensSpent = b.spentTokens(),
                            tokensReported = b.tokensAreReported(),
                            tokensEstimated = b.estimatedTokens(),
                            tokensCached = b.cachedTokens(),
                            elapsedMs = b.elapsedMs(),
                            maxWallClockMs = cfg.runMaxWallClockMs,
                            compactThresholdTokens = cfg.compactThresholdTokens,
                            contextWindow = contextWindowForTrace,
                            retryMaxAttempts = retry.maxAttempts,
                            retryWorstCaseSleepMs = AgentRetryPolicy.worstCaseSleepMs(retry),
                            endedByLimit = b.exceededLimit()?.name,
                        ),
                    )
                }
            }
            // Stop charging this run's budget for calls that outlive it. Only one run is active
            // at a time, so a single nullable sink is enough — same contract as the trace sink.
            AgentLlmTap.usageSink = null
            // Let the screen sleep again the moment the run ends, whatever the outcome.
            keepAwake?.close()
            // NonCancellable: finalize must land even when the run died to a cancellation —
            // an un-finalized ledger is (Build 3) the app-death signal, so a clean stop must
            // always stamp its end reason. flush() then forces the FINALIZED snapshot to disk
            // synchronously: a debounced write would be lost when persistScope is cancelled,
            // leaving a completed run looking app-dead (wrongly resumable).
            withContext(NonCancellable) {
                // Spec 2026-07-31 — "pause must checkpoint, not merely stop". A run that is
                // still paused cannot honestly claim it completed: end_session is refused
                // along with every other tool while the human holds the lock, so the only
                // way to reach the default "completed" from here is the model giving up and
                // returning prose. Stamped BEFORE the hooks so the learnings pipeline also
                // sees "paused" and does not bank a working route it never verified.
                endReason = PauseCheckpoint.endReasonFor(
                    computed = endReason,
                    pausedByHuman = ControlLockStore.shared.pausedByHuman.value,
                )
                // E6 RunEnd — before the ledger finalizes: the learnings flush replaces
                // the old "post-hook keyed on end_session" hack, so a completed run
                // flushes even when end_session reached the server via another site.
                runEndHooks.forEach { hook -> runCatching { hook.onRunEnd(endReason) } }
                runCatching { ledgerController.finalize(endReason) }
                runCatching { ledgerPersister.flush() }
                // A local encrypted write, no network — safe here, unlike anything needing a model.
                if (remember) {
                    runCatching {
                        HistoryMemory.actionLog(goal, finalReply, endReason)?.let { (task, outcome) ->
                            memoryService(context).logAction(task, outcome)
                        }
                    }
                }
            }
            // Resolve any question still waiting on the user so nothing hangs
            // past the run (the UI observes pending → it clears immediately).
            runCatching { AskUserBroker.shared.cancelAll() }
            // Disarm the pause trigger — nothing is driving any more, so a touch is just
            // a person using their phone. In the `finally` because a run that died to a
            // crash or a cancellation must disarm too: leaving it armed would let the
            // next stray event pause an agent that no longer exists, with a Pause pill
            // offering to resume nothing.
            runCatching { ControlLockStore.shared.noteLocalRunFinished() }
            runCatching { persistScope.cancel() }
            // The verdict rides the log beside the mechanical end reason, so a review — and the
            // eval harness — can tell "stopped cleanly" from "actually did what was asked".
            runLogger.endRun(endReason, proofVerdictFor(ledgerController.snapshot()))
            remoteAttachment?.close()
            runCatching { mcpClient.close() }
            handle.close()
        }
    }

    /**
     * Run a goal using the provider/key/model the user saved in
     * [ProviderSettingsScreen] (persisted in [ProviderKeyStore]). This is the
     * real entry point the voice/overlay surfaces will call in Phase 3 — no
     * hardcoded key, no adb extras.
     *
     * Fails **loudly** (never silently falls back to a default provider/model) if
     * the user hasn't finished configuring: a missing key or unselected model is a
     * setup error the caller should surface, not paper over. In particular,
     * [runSpike]'s `modelId=null → Llama 4 Scout` fallback is a *Groq* id, so
     * letting an unconfigured OpenRouter/Gemini selection through would silently
     * run the wrong model — hence the explicit model check here.
     */
    suspend fun runFromSavedSettings(
        goal: String,
        remember: Boolean = false,
        onEvent: (AgentStreamEvent) -> Unit = {},
    ): String {
        // Refused before a model ever sees it. Deliberately not routed through the agent as a
        // "please confirm": eval 2026-08-26 task 23 did exactly that, the human said yes, and
        // the photos went. See [DestructiveGoal] for why a model-authored confirmation is not a
        // control. Placed here, not in the loop, so no run, ledger or token is spent on it.
        if (DestructiveGoal.isBulkDestruction(goal)) {
            Log.w(TAG, "⛔ Refusing bulk-destructive goal before dispatch: $goal")
            onEvent(AgentStreamEvent.Finished(DestructiveGoal.REFUSAL))
            return DestructiveGoal.REFUSAL
        }
        val saved = savedCredentials()
        Log.i(TAG, "▶ runFromSavedSettings: ${saved.endpoint.id} / ${saved.modelId}")
        return runSpike(
            apiKey = saved.apiKey,
            goal = goal,
            endpoint = saved.endpoint,
            modelId = saved.modelId,
            generation = saved.generation,
            onEvent = onEvent,
            remember = remember,
        )
    }

    /**
     * Resume an interrupted run (Build 3). Loads the persisted ledger by id, forks a FRESH run
     * seeded from it (same goal/plan/facts/dead-ends; new run id, clocks reset), and dismisses the
     * old ledger so it is never offered again on its own.
     *
     * Credentials come from saved settings — the API key is deliberately never persisted in a
     * ledger (BYOK), so resume fails loudly on an unconfigured provider exactly like
     * [runFromSavedSettings]. There is no chat-history replay: replaying stale som_ids/screens
     * would mislead the model. The ledger IS the reconstruction, and [RESUME_PREAMBLE] tells the
     * model to re-verify the live screen before trusting any prior step's effect.
     */
    suspend fun resumeRun(
        ledgerId: String,
        // Same meaning as runSpike's: the overlay's resume offer is a person asking; Live logs its own.
        remember: Boolean = false,
        onEvent: (AgentStreamEvent) -> Unit = {},
    ): String {
        val store = RunLedgerStore(EncryptedJsonStore(context, RunLedgerStore.STORE_NAME))
        val prior = store.get(ledgerId)
            ?: error("No run to resume for id '$ledgerId' — it may have expired or been completed.")
        val saved = savedCredentials()
        val seed = prior.seededResume(newRunId(), System.currentTimeMillis())
        // Once chosen for resume, the old ledger must not be independently re-offered. If THIS
        // resume dies too, the fresh seeded ledger (newer, un-finalized) becomes the resumable one.
        runCatching { store.dismiss(ledgerId) }
        Log.i(TAG, "▶ resumeRun '$ledgerId' → ${seed.runId}: ${prior.goal}")
        return runSpike(
            apiKey = saved.apiKey,
            goal = prior.goal,
            endpoint = saved.endpoint,
            modelId = saved.modelId,
            generation = saved.generation,
            onEvent = onEvent,
            seedLedger = seed,
            // Seed the rendered ledger into turn 1 — the per-turn injector only fires after a
            // tool result, so without this the model wouldn't see the carried plan on its first
            // turn and might re-plan from scratch.
            resumePreamble = ResumePreamble.forResume(seed),
            remember = remember,
        )
    }

    /** The user's saved run config: endpoint, key, model, and generation controls. */
    private data class SavedRunConfig(
        val endpoint: LlmEndpoint,
        val apiKey: String,
        val modelId: String,
        val generation: GenerationConfig,
    )

    /** Resolves the user's saved endpoint/key/model/generation, failing loudly if setup is incomplete. */
    private fun savedCredentials(): SavedRunConfig {
        val store = ProviderKeyStore(context)
        val id = store.getSelectedEndpointId()
        val endpoint = LlmEndpointCatalog.builtinById(id)
            ?: CustomEndpointStore(EncryptedJsonStore(context, CustomEndpointStore.STORE_NAME))
                .allEndpoints().firstOrNull { it.id == id }
            ?: LlmEndpointCatalog.BUILTINS.first()
        val apiKey = if (endpoint.requiresKey) {
            store.getKey(id)
                ?: error("No API key configured for ${endpoint.displayName} — open Settings → Brain.")
        } else {
            ""
        }
        val modelId = store.getSelectedModel(id)
            ?: error("No model selected for ${endpoint.displayName} — fetch and pick one in Settings → Brain.")
        return SavedRunConfig(endpoint, apiKey, modelId, store.getGeneration(id))
    }

    private fun newRunId() =
        "${System.currentTimeMillis()}-${UUID.randomUUID().toString().substringBefore('-')}"

    /**
     * The run's verdict as the log records it, or null when this run never had a contract, proof
     * or verdict to record — a plain "open WhatsApp" writes nothing here, and should not.
     */
    private fun proofVerdictFor(ledger: com.aura.aura_ui.agent.ledger.RunLedger): com.aura.aura_ui.mcp.log.RunVerdict? {
        val outcome = ledger.outcome
        if (outcome == null && ledger.findings.isEmpty() && ledger.targetCount == null) return null
        return com.aura.aura_ui.mcp.log.RunVerdict(
            verdict = outcome?.verdict ?: com.aura.aura_ui.agent.ledger.RunOutcome.UNVERIFIED,
            claimed = outcome?.claimed,
            why = outcome?.why,
            proven = ledger.findings.size,
            target = ledger.targetCount,
            deliverable = ledger.deliverable,
            findings = ledger.findings.map {
                com.aura.aura_ui.mcp.log.ProofItem(item = it.item, quote = it.quote, afterReads = it.afterReads)
            },
        )
    }

    /**
     * Phase 0 vision de-risk (#80). Proves the *pivotal* unknown: can the chosen
     * vision model, through Koog on Android, actually SEE the SoM-annotated
     * screenshot and reason over pixels — not just the text element list?
     *
     * Flow: connect MCP → call `perceive_screen` → pull the base64 PNG out of the
     * [ImageContent] (which the agent path strips from text) → attach it as a Koog
     * `image()` and ask a pixel-only question via a direct LLM call. If the model
     * answers correctly from the image, the vision path is proven and can be wired
     * into a strategy node for the full agent loop.
     */
    suspend fun runVisionSpike(apiKey: String, question: String): String {
        val handle = InProcessMcpServer.connect(
            deviceBridge = AppDeviceBridge(context),
            screenshotBridge = AppScreenshotBridge(context),
            uiTreeBridge = AppUiTreeBridge(),
            perceptionBridge = AppPerceptionBridge(context),
            webSearchBridge = AppWebSearchBridge(TavilyKeyStore(context)),
            deepLinkBridge = AppDeepLinkBridge(context, DeepLinkManager(context)),
            notificationBridge = AppNotificationBridge(context),
            mediaBridge = AppMediaBridge(context),
            systemIntentBridge = AppSystemIntentBridge(context),
            contactsBridge = AppContactsBridge(context),
            filesBridge = AppFilesBridge(context),
            browserBridge = browserBridge,
            ownPackageName = context.packageName,
            // Spec 2026-07-31 — control lock. The local agent yields to the human too:
            // the ordering is human > local agent > MCP client, so a pause that only
            // reached remote clients would leave the on-device agent still driving.
            pausedByHuman = { ControlLockStore.shared.isPausedByHuman },
        )
        val mcpClient = Client(clientInfo = Implementation(name = CLIENT_NAME, version = CLIENT_VERSION))
        return try {
            mcpClient.connect(handle.clientTransport)

            Log.i(TAG, "🖼️ Vision spike — calling perceive_screen…")
            val result = mcpClient.callTool(
                name = "perceive_screen",
                arguments = mapOf("description" to "Vision check: capture the annotated screen"),
            )
            val imageB64 = result?.content?.filterIsInstance<ImageContent>()?.firstOrNull()?.data
                ?: error("perceive_screen returned no image — is screen-capture granted?")
            val elementsText = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            val bytes = Base64.decode(imageB64, Base64.DEFAULT)
            Log.i(TAG, "🖼️ Got SoM image: ${bytes.size} bytes; element text ${elementsText.length} chars")

            val visionPrompt = prompt("aura-vision-spike") {
                system(
                    "You are a vision checker. The user gives you an annotated Android " +
                        "screenshot with numbered red Set-of-Marks (SoM) boxes and a question. " +
                        "Answer ONLY from what you can see in the image.",
                )
                user {
                    text("Annotated screenshot follows. Numbered red boxes are som_id values.")
                    image(AttachmentSource.Image(AttachmentContent.Binary.Bytes(bytes), "png", "image/png", "screen.png"))
                    text(question)
                }
            }

            val groq = LlmEndpointCatalog.builtinById(LlmEndpointCatalog.GROQ_ID)!!
            val client = ProviderRegistry.clientFor(groq, apiKey, LlmEndpointCatalog.DEFAULT_MODEL_ID)
            val response = client.execute(visionPrompt, ProviderRegistry.modelFor(groq, LlmEndpointCatalog.DEFAULT_MODEL_ID))
            val answer = (response as? Message.Assistant)?.textContent() ?: response.toString()
            Log.i(TAG, "🖼️ Vision answer: $answer")
            answer
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Vision spike failed: ${t.message}", t)
            throw t
        } finally {
            runCatching { mcpClient.close() }
            handle.close()
        }
    }

    /** The status strip's headline: the plan step in progress, or null before a plan. */
    private fun planHeadline(ledger: RunLedgerController): String? =
        com.aura.aura_ui.agent.mcpbridge.hooks.StatusNarrationHook.headlineFor(
            ledger.snapshot().planSteps.map {
                it.text to (it.status == PlanStepStatus.DONE || it.status == PlanStepStatus.SKIPPED)
            },
        )

    /** A strip line the harness itself has to say (research landed, waiting on the user). */
    private fun narrate(kind: AgentStatusRegistry.Kind, text: String, ledger: RunLedgerController, aboutCall: Boolean) {
        val beat = AgentStatusRegistry.Beat(kind, text, planHeadline(ledger))
        AgentStatusRegistry.setBeat(beat)
        AgentTraceTap.reportNarration(kind.name.lowercase(), text, beat.headline, aboutCall)
    }

    private fun firstSentence(text: String): String =
        text.trim().split(Regex("(?<=[.!?])\\s")).first().take(90)

    companion object {
        private const val TAG = "AuraAgentSpike"
        private const val CLIENT_NAME = "aura-agent"
        private const val CLIENT_VERSION = "1.0.0"
        /** Cap on a streamed tool-result summary so the trace stays readable. */
        private const val RESULT_SUMMARY_CAP = 240

        /**
         * B13: spoken when the model emitted ONLY reasoning (no answer survived the
         * strip). Speaking raw chain-of-thought is the bug; an honest miss beats it.
         */
        private const val NO_FINAL_ANSWER_MESSAGE =
            "I finished working, but the model didn't produce a final answer. Please try again."
        /**
         * The agent's system prompt: opening line + tagged doctrine sections from
         * [com.aura.mcp.server.Doctrine]. `IdentityPromptGoldenTest` pins the exact text.
         */
        internal val SYSTEM_PROMPT: String = com.aura.mcp.server.Doctrine.render()
    }
}
