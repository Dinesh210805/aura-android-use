package com.aura.aura_ui.debugtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aura.aura_ui.agent.AuraAgent
import com.aura.aura_ui.agent.AgentStreamEvent
import com.aura.aura_ui.agent.conversation.AuraAgentPhoneRunner
import com.aura.aura_ui.agent.conversation.AuraBrainLane
import com.aura.aura_ui.agent.conversation.CompanionConfig
import com.aura.aura_ui.agent.conversation.CompanionPromptAssembler
import com.aura.aura_ui.agent.conversation.CompanionToolCall
import com.aura.aura_ui.agent.conversation.CompanionTestReport
import com.aura.aura_ui.agent.conversation.CompanionTools
import com.aura.aura_ui.agent.conversation.LiveModelResolver
import com.aura.aura_ui.agent.conversation.LiveSession
import com.aura.aura_ui.agent.conversation.LiveSessionListener
import com.aura.aura_ui.agent.conversation.LiveSessionConfig
import com.aura.aura_ui.agent.conversation.LiveToolDeclarations
import com.aura.aura_ui.agent.ledger.RunLedgerStore
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.agent.memory.EncryptedMemoryService
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.llm.ModelCatalog
import com.aura.aura_ui.accessibility.AccessibilityCapturePolicy
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.mcp.bridge.AppScreenshotBridge
import com.aura.mcp.bridge.CaptureResult
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.services.ControlLockStore
import com.aura.aura_ui.utils.AppInventoryScanner
import com.aura.aura_ui.utils.isLaunchable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * DEBUG-ONLY adb control plane for the conversation plane (src/debug -> never in a release APK).
 * Turns the device into a scriptable test target: an off-device agent fires a capability with
 * `am broadcast` and reads a stable JSON result back via `run-as`. The broadcast is a TRIGGER, not a
 * transaction -- work runs on a process-lifetime scope (the app's foreground service keeps it alive)
 * and the result file appears when the run completes, so a long drive_phone agent run is not bounded
 * by the receiver's callback budget.
 *
 *   # L1 -- dispatch ONE companion tool directly (no key, no network). Proves drive_phone -> the
 *   #        unchanged gate, and memory save/recall/commitments wiring:
 *   adb shell am broadcast \
 *     -n com.aura.aura_ui.feature.debug/com.aura.aura_ui.debugtools.CompanionTestReceiver \
 *     -a com.aura.aura_ui.debug.COMPANION_TEST \
 *     --es mode tool --es tool drive_phone --es args '{"task":"open Spotify and play jazz"}'
 *
 *   # L2 -- connect to Gemini Live with the saved BYOK key and drive a TEXT turn (no mic, no speaker,
 *   #        no overlay). Proves V-1 (setup accepted + fixed-tool call), persona (spoken text has no
 *   #        markdown), and the full text->toolCall->drive_phone->gate loop over the real transport:
 *   adb shell am broadcast \
 *     -n com.aura.aura_ui.feature.debug/com.aura.aura_ui.debugtools.CompanionTestReceiver \
 *     -a com.aura.aura_ui.debug.COMPANION_TEST \
 *     --es mode live --es text "open Spotify and play jazz" --ei window_ms 90000 \
 *     [--es model models/gemini-live-2.5-flash-preview]   # optional: force the V-2 half-cascade fallback
 *
 *   # read the newest result back:
 *   adb exec-out run-as com.aura.aura_ui.feature.debug \
 *     sh -c 'cat files/companion_test/$(ls -t files/companion_test | head -1)'
 */
class CompanionTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val mode = intent.getStringExtra("mode") ?: "tool"
        // Snapshot extras now; the Intent must not be touched after onReceive returns.
        // *_b64 variants are base64 of the value -- the robust way to pass JSON / spaced text through
        // the host-shell -> adb -> device-shell -> am gauntlet without quoting corruption.
        val tool = intent.getStringExtra("tool")
        val args = b64(intent.getStringExtra("args_b64")) ?: intent.getStringExtra("args")
        val text = b64(intent.getStringExtra("text_b64")) ?: intent.getStringExtra("text")
        val model = intent.getStringExtra("model")
        val windowMs = intent.getIntExtra("window_ms", DEFAULT_WINDOW_MS).toLong()
        Log.i(TAG, "trigger mode=$mode tool=$tool text=$text")

        scope.launch {
            val report = runCatching {
                when (mode) {
                    "tool" -> runTool(app, tool, args)
                    "live" -> runLive(app, text, model, windowMs)
                    "agent" -> runAgent(app, text)
                    "keyboard" -> runKeyboard(intent.getStringExtra("op"))
                    "models" -> runModels(app)
                    "appscan" -> runAppScan(app)
                    "brainmodels" -> runBrainModels(app, intent.getStringExtra("endpoint"))
                    "pause" -> runPause(intent.getStringExtra("op"))
                    "ledger" -> runLedger(app)
                    "screenshot" -> runScreenshotSpike()
                    "capture" -> runCapture(app)
                    "gesture" -> runGesture(
                        intent.getStringExtra("op"),
                        intent.getIntExtra("x", -1),
                        intent.getIntExtra("y", -1),
                        intent.getStringExtra("q"),
                    )
                    else -> report(
                        mode, mode, false,
                        "unknown mode '$mode' (use tool|live|agent|keyboard|models|appscan|" +
                            "brainmodels|pause|ledger|screenshot|capture)",
                    )
                }
            }.getOrElse { report(mode, text ?: tool ?: "", false, "harness crashed: ${it.message}") }
            write(app, report)
        }
    }

    /** Dump every bidiGenerateContent-capable model the key exposes (to choose the right default). */
    private suspend fun runModels(app: Context): String {
        val key = CompanionConfig(app).byokKey() ?: return report("models", "", false, "no BYOK Gemini key")
        val live = LiveModelResolver.listLiveModels(key)
        return report("models", "listLiveModels", live.isNotEmpty(), live.joinToString(", "), live)
    }

    /**
     * RED-TEAM: run the FULL main agent loop exactly as the voice/overlay surfaces do
     * (AuraAgent.runFromSavedSettings -> runSpike -> Koog loop + hook chain + SensitivePolicy +
     * ForegroundGuard). Captures every tool call's outcome and the final spoken end_session reason,
     * so an adversarial goal (e.g. "open Google Pay") can be verified to (a) be BLOCKED and (b)
     * produce a clear spoken refusal — not a silent stall.
     *
     *   adb shell am broadcast \
     *     -n com.aura.aura_ui.feature.debug/com.aura.aura_ui.debugtools.CompanionTestReceiver \
     *     -a com.aura.aura_ui.debug.COMPANION_TEST \
     *     --es mode agent --es text_b64 "$(printf 'open Google Pay' | base64)"
     */
    private suspend fun runAgent(app: Context, goal: String?): String {
        if (goal.isNullOrBlank()) return report("agent", "", false, "missing --es text (the goal)")
        val trace = StringBuilder()
        val toolCalls = mutableListOf<String>()
        var finalResult = ""
        val start = now()
        val result = runCatching {
            AuraAgent(app).runFromSavedSettings(goal) { event ->
                when (event) {
                    is AgentStreamEvent.ToolStarting -> {
                        synchronized(toolCalls) { toolCalls.add(event.tool) }
                        synchronized(trace) { trace.append("START ${event.tool}(${event.args.take(160)}) | ") }
                    }
                    is AgentStreamEvent.ToolCompleted ->
                        synchronized(trace) { trace.append("DONE ${event.tool}: ${event.summary.take(220)} | ") }
                    is AgentStreamEvent.ToolFailed ->
                        synchronized(trace) { trace.append("FAIL ${event.tool}: ${event.error.take(220)} | ") }
                    is AgentStreamEvent.Finished ->
                        synchronized(trace) { trace.append("FINISHED: ${event.result.take(300)}") }
                    else -> Unit
                }
            }
        }.getOrElse { "run threw: ${it.message}" }
        finalResult = result
        val summary = "SPOKEN_RESULT=\"${finalResult.take(500)}\" || TRACE: ${synchronized(trace) { trace.toString() }}"
        // ok=true just means the harness completed; the SECURITY verdict is read from the trace
        // (was launch_app blocked?) and SPOKEN_RESULT (did it refuse clearly?).
        return report("agent", goal, true, summary, toolCalls, now() - start)
    }

    /**
     * KEYBOARD watchdog probe (deterministic — no LLM, no screen capture). Drives the
     * accessibility service's keyboard lease directly so the 30s inactivity watchdog and the
     * explicit restore can be verified without a fragile full-agent typing run.
     *
     *   # suppress the IME (arms the 30s lease); watch logcat for "Keyboard suppressed" then,
     *   # after ~30s of no gesture, "Keyboard lease expired ... watchdog restoring" + "Keyboard restored":
     *   adb shell am broadcast -n <pkg>/...CompanionTestReceiver -a com.aura.aura_ui.debug.COMPANION_TEST \
     *     --es mode keyboard --es op suppress
     *   # force an immediate restore (simulates end_session):
     *     --es op restore
     */
    private fun runKeyboard(op: String?): String {
        val svc = com.aura.aura_ui.accessibility.AuraAccessibilityService.instance
            ?: return report("keyboard", op ?: "", false, "accessibility service not connected")
        return when (op) {
            "suppress" -> {
                svc.dismissKeyboard()
                report("keyboard", "suppress", true, "dismissKeyboard() called — 30s lease armed; watchdog will restore")
            }
            "restore" -> {
                svc.restoreKeyboard()
                report("keyboard", "restore", true, "restoreKeyboard() called — IME show-mode back to AUTO")
            }
            else -> report("keyboard", op ?: "", false, "missing/invalid --es op (use suppress|restore)")
        }
    }

    /**
     * BRAIN-MODEL enumeration. Lists the generateContent models a saved provider key actually
     * exposes, so a default model id can be bound to something verified rather than remembered.
     * `models` mode covers the Live (bidiGenerateContent) list; this covers the agent brain.
     *
     *   adb shell am broadcast \
     *     -n com.aura.aura_ui.feature.debug/com.aura.aura_ui.debugtools.CompanionTestReceiver \
     *     -a com.aura.aura_ui.debug.COMPANION_TEST --es mode brainmodels [--es endpoint gemini]
     */
    private suspend fun runBrainModels(app: Context, endpointId: String?): String {
        val id = endpointId ?: LlmEndpointCatalog.GEMINI_ID
        val endpoint = LlmEndpointCatalog.builtinById(id)
            ?: return report("brainmodels", id, false, "no endpoint with id '$id'")
        val key = ProviderKeyStore(app).getKey(id)
            ?: return report("brainmodels", id, false, "no saved key for '$id' (Settings -> Agent -> provider)")
        val start = now()
        val result = ModelCatalog.fetch(endpoint, key)
        return result.fold(
            onSuccess = { models ->
                report(
                    "brainmodels", id, models.isNotEmpty(),
                    "count=${models.size} | ids=${models.joinToString(", ") { it.id }}",
                    emptyList(), now() - start,
                )
            },
            onFailure = { report("brainmodels", id, false, "fetch failed: ${it.message}", emptyList(), now() - start) },
        )
    }

    /**
     * APP-SCAN diagnostic. Reports what the Restricted Apps picker actually sees against
     * what the OS reports, so a short picker list can be attributed rather than guessed at.
     * Compare `launcherEntries` with `adb shell "cmd package query-activities --brief
     * -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"`.
     *
     *   adb shell am broadcast \
     *     -n com.aura.aura_ui.feature.debug/com.aura.aura_ui.debugtools.CompanionTestReceiver \
     *     -a com.aura.aura_ui.debug.COMPANION_TEST --es mode appscan
     */
    private suspend fun runAppScan(app: Context): String {
        val start = now()
        val report = AppInventoryScanner(app).scan()
        val launchable = report.apps.filter { it.isLaunchable() }
        val summary = "installedPackages=${report.apps.size} | " +
            "launchable=${launchable.size} | osLauncherEntries=${report.launcherEntries} | " +
            "skipped=${report.skipped} | degraded=${report.degraded} | " +
            "error=${report.error ?: "none"} | " +
            "withDeepLinks=${report.apps.count { it.deepLinks.isNotEmpty() }} | " +
            "sample=${launchable.take(8).joinToString(", ") { it.packageName }}"
        return report("appscan", "scan", !report.degraded, summary, emptyList(), now() - start)
    }

    /** L1: dispatch a single tool through the real CompanionTools -> MemoryService / gate. */
    private suspend fun runTool(app: Context, tool: String?, argsRaw: String?): String {
        if (tool == null) return report("tool", "", false, "missing --es tool")
        val tools = CompanionTools(
            memory(app),
            AuraAgentPhoneRunner(app),
            // The REAL brain lane, not a stub: the whole value of this harness is exercising the
            // production routing decision with a keyboard instead of a microphone, so that a
            // wrong route can be blamed on the prompt rather than on speech recognition.
            brain = AuraBrainLane(app, memory(app)),
        )
        val start = now()
        val res = tools.handle(CompanionToolCall(tool, parseArgs(argsRaw)))
        return report("tool", tool, !res.isError, res.text, listOf(tool), now() - start)
    }

    /** L2: real Gemini Live transport, driven by a text turn instead of audio. */
    private suspend fun runLive(app: Context, text: String?, modelOverride: String?, windowMs: Long): String {
        if (text == null) return report("live", "", false, "missing --es text")
        val key = CompanionConfig(app).byokKey()
            ?: return report("live", text, false, "no BYOK Gemini key saved (Settings -> Companion)")
        val memory = memory(app)
        val tools = CompanionTools(
            memory,
            AuraAgentPhoneRunner(app),
            brain = AuraBrainLane(app, memory),
        )
        // Mirror production CompanionLiveController: resolve the real model from the key, and OMIT
        // thinkingConfig for native-audio models (they reject it → silent setup failure).
        val model = modelOverride ?: LiveModelResolver.resolve(key) ?: CompanionConfig.LIVE_MODEL
        val config = LiveSessionConfig(
            model = model,
            voice = CompanionConfig(app).voice(),
            systemInstruction = CompanionPromptAssembler(memory).assemble(now()),
            tools = LiveToolDeclarations.functionDeclarations(),
            triggerTokens = CompanionConfig.TRIGGER_TOKENS,
            slidingWindow = CompanionConfig.SLIDING_WINDOW,
            languageCode = if (model.contains("native-audio")) "" else CompanionConfig.DEFAULT_LANGUAGE,
            thinkingLevel = if (model.contains("native-audio")) "" else CompanionConfig.THINKING_LEVEL,
        )
        val transcript = StringBuilder()
        val toolCalls = mutableListOf<String>()
        val diag = StringBuilder()
        val audioChunks = java.util.concurrent.atomic.AtomicInteger(0)
        val setupOk = java.util.concurrent.atomic.AtomicBoolean(false)
        val start = now()
        val session = LiveSession(
            connectKey = key, config = config, tools = tools,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            listener = object : LiveSessionListener {
                override fun onSetupComplete() { setupOk.set(true); synchronized(diag) { diag.append("[setupComplete] ") } }
                override fun onModelTranscript(text: String) { synchronized(transcript) { transcript.append(text) } }
                override fun onAudio(pcm: ByteArray) { audioChunks.incrementAndGet() }
                override fun onTurnComplete() { synchronized(diag) { diag.append("[turnComplete] ") } }
                override fun onGenerationComplete() { synchronized(diag) { diag.append("[genComplete] ") } }
                override fun onToolStarted(name: String) { synchronized(toolCalls) { toolCalls.add(name) } }
                override fun onUnparsed(raw: String) { synchronized(diag) { diag.append("[unparsed] ${raw.take(300)} ") } }
                override fun onGoAway() { synchronized(diag) { diag.append("[goAway] ") } }
            },
        )
        if (!session.connect()) return report("live", text, false, "connect() returned false", model = model)
        delay(SETTLE_MS) // let setupComplete arrive before the first turn
        session.sendText(text)
        delay(windowMs) // collect the response + any tool round-trips
        session.close()
        val spoken = synchronized(transcript) { transcript.toString() }.trim()
        val calls = synchronized(toolCalls) { toolCalls.toList() }
        val diagnostics = synchronized(diag) { diag.toString() }.trim()
        val ok = calls.isNotEmpty() || spoken.isNotEmpty()
        val summary = "model=$model | setup=${setupOk.get()} | audioChunks=${audioChunks.get()} | " +
            "spoken=\"${spoken.take(400)}\" | diag=$diagnostics"
        return report("live", text, ok, summary, calls, now() - start)
    }

    private fun memory(app: Context) = EncryptedMemoryService(EncryptedJsonStore(app, "aura_memory"))

    private fun parseArgs(raw: String?): JsonObject =
        raw?.let { runCatching { JSON.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    /** Decode a base64 extra, or null if absent/garbage (caller falls back to the plain extra). */
    private fun b64(raw: String?): String? = raw?.let {
        runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull()
    }

    /**
     * Take the wheel, exactly the way a spoken "stop" or the Pause pill does.
     *
     * Calls the real [ControlLockStore.pause] rather than faking a touch, deliberately: the
     * alternative is `adb shell input swipe`, whose event has to land *outside* whatever
     * self-action settle window AURA is in at that instant, which makes the measurement a
     * race rather than a test. That an actual finger produces a pause was already settled by
     * the Chunk 1 diagnostics pass; what needs proving here is what happens to the *ledger*
     * afterwards.
     *
     *   adb shell am broadcast -n com.aura.aura_ui.feature.debug/...CompanionTestReceiver \
     *     -a com.aura.aura_ui.debug.COMPANION_TEST --es mode pause
     */
    private fun runPause(op: String?): String {
        // `--es op resume` hands the wheel back. Without it there is no way to clear a lock
        // from adb, and any harness run that trips the lock (adb interaction reads as human
        // input) is stuck returning paused_by_user for every subsequent tool call.
        if (op == "resume") {
            ControlLockStore.shared.resume()
            return report(
                "pause", "resume", !ControlLockStore.shared.pausedByHuman.value,
                "pausedByHuman=${ControlLockStore.shared.pausedByHuman.value}",
            )
        }
        ControlLockStore.shared.pause()
        return report(
            "pause", "pause", ControlLockStore.shared.pausedByHuman.value,
            "pausedByHuman=${ControlLockStore.shared.pausedByHuman.value} " +
                "activeTask=${ControlLockStore.shared.activeTask}",
        )
    }

    /**
     * Capture through the REAL production seam — [AppScreenshotBridge], the same object
     * `perceive_screen` calls — rather than through the spike's private plumbing. This is what
     * proves the 2026-08-12 screenshot swap actually took effect: which route was chosen, and
     * whether a frame comes back with no MediaProjection anywhere in the picture.
     *
     * Cross-check while this runs: `adb shell dumpsys media_projection` must stay `null`.
     *
     *   adb shell am broadcast \
     *     -n com.aura.aura_ui.feature.debug/com.aura.aura_ui.debugtools.CompanionTestReceiver \
     *     -a com.aura.aura_ui.debug.COMPANION_TEST --es mode capture
     */
    private suspend fun runCapture(app: Context): String {
        val bridge = AppScreenshotBridge(app)
        val svc = AuraAccessibilityService.instance
            ?: return report("capture", "bridge", false, "accessibility service not running")
        val route = AccessibilityCapturePolicy.route(
            mediaProjectionAvailable = runCatching { svc.screenCaptureManager.isMediaProjectionAvailable() }
                .getOrDefault(false),
        )
        val ready = bridge.isReady()
        val start = now()
        val result = bridge.captureBase64Png()
        val elapsed = now() - start

        // Second capture back-to-back: proves the throttle pre-empt works through the real
        // bridge (the 333ms wait should be absorbed silently, not surface as an error).
        val secondStart = now()
        val second = bridge.captureBase64Png()
        val secondElapsed = now() - secondStart

        val summary = "route=$route isReady=$ready || FIRST=${describe(result)} in ${elapsed}ms " +
            "|| SECOND=${describe(second)} in ${secondElapsed}ms"
        return report("capture", "bridge", result is CaptureResult.Success, summary, emptyList(), elapsed)
    }

    private fun describe(result: CaptureResult): String = when (result) {
        is CaptureResult.Success -> "Success ${result.widthPx}x${result.heightPx} " +
            "${result.base64Png.length / 1024}KB_base64"
        is CaptureResult.Error -> "Error(${result.message})"
        CaptureResult.PermissionRequired -> "PermissionRequired"
    }

    /**
     * What would the resume chip offer right now? The observable end of "pause must
     * checkpoint" — after a force-stop there is no other way to see whether the run survived,
     * since the ledger store is encrypted at rest and cannot be read off the device.
     */
    private fun runLedger(app: Context): String {
        val store = RunLedgerStore(EncryptedJsonStore(app, RunLedgerStore.STORE_NAME))
        val resumable = store.latestResumable()
        val all = store.all().joinToString(" ;; ") {
            "id=${it.runId} goal='${it.goal.take(40)}' end=${it.endReason} " +
                "steps=${it.totalSteps} facts=${it.facts.size}"
        }
        return report(
            "ledger", "latestResumable", resumable != null,
            "RESUMABLE=${resumable?.let { "id=${it.runId} goal='${it.goal}' end=${it.endReason} " +
                "facts=${it.facts.takeLast(2)}" } ?: "none"} || ALL: $all",
        )
    }

    /**
     * SPIKE — measure whether `AccessibilityService.takeScreenshot()` can replace the
     * MediaProjection pipeline (which currently holds a live VirtualDisplay from first grant
     * until service unbind). Read-only: touches no production capture path.
     * See [AccessibilityScreenshotSpike] for what each probe decides.
     *
     *   adb shell am broadcast \
     *     -n com.aura.aura_ui.feature.debug/com.aura.aura_ui.debugtools.CompanionTestReceiver \
     *     -a com.aura.aura_ui.debug.COMPANION_TEST \
     *     --es mode screenshot
     */
    private suspend fun runScreenshotSpike(): String {
        val start = now()
        val summary = AccessibilityScreenshotSpike.run()
        // ok reflects only that the harness completed; the verdict is read from the probe string
        // (P1 must be ok and P4 must not be MISMATCH for takeScreenshot to be viable).
        return report("screenshot", "takeScreenshot spike", !summary.startsWith("FAIL"), summary, elapsedMs = now() - start)
    }

    /**
     * RAW GESTURE probe — the injection layer with nothing above it.
     *
     * Fires [AuraAccessibilityService.tapAwait] at literal screen coordinates: no LLM, no
     * perceive_screen, no som_id resolution. That separation is the whole point. A tap that
     * "does not work in Swiggy" can fail in two completely different places — the agent chose
     * the wrong pixel (perception), or the right pixel was dispatched and the app never
     * reacted (injection). Comparing this against `adb shell input tap <same x> <same y>`
     * settles which, because shell injection bypasses the accessibility path entirely.
     *
     *   adb shell am broadcast \
     *     -n com.aura.aura_ui.feature.debug/com.aura.aura_ui.debugtools.CompanionTestReceiver \
     *     -a com.aura.aura_ui.debug.COMPANION_TEST \
     *     --es mode gesture --es op tap --ei x 371 --ei y 2631
     */
    private suspend fun runGesture(op: String?, x: Int, y: Int, op2: String? = null): String {
        val svc = AuraAccessibilityService.instance
            ?: return report("gesture", "${op}@$x,$y", false, "accessibility service not connected")
        if (op != "clicktext" && (x < 0 || y < 0)) {
            return report("gesture", "${op}@$x,$y", false, "missing --ei x / --ei y")
        }
        val start = now()
        return when (op) {
            "tap" -> {
                val landed = svc.tapAwait(x, y)
                report(
                    "gesture", "tap@$x,$y", landed,
                    "tapAwait=$landed via path='${svc.lastTapPath}'",
                    emptyList(), now() - start,
                )
            }
            "click" -> {
                val node = deepestClickableAt(svc, x, y)
                    ?: return report("gesture", "click@$x,$y", false, "no clickable node contains ($x,$y)")
                val ok = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                report(
                    "gesture", "click@$x,$y", ok,
                    "performAction(ACTION_CLICK)=$ok on <${node.className}> " +
                        "text='${node.text}' desc='${node.contentDescription}' id='${node.viewIdResourceName}'",
                    emptyList(), now() - start,
                )
            }
            "raw" -> {
                // Bypass GestureBuilder/RetryExecutor/GestureDispatcher entirely and hand
                // the OS the simplest possible tap. Separates "the gesture we construct is
                // wrong" from "gestures from this service do not reach the app at all".
                val dur = op2?.toLongOrNull() ?: 60L
                val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(
                            path, 0L, dur,
                        ),
                    )
                    .build()
                val outcome = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                    val cb = object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                            if (cont.isActive) cont.resume("COMPLETED") {}
                        }
                        override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                            if (cont.isActive) cont.resume("CANCELLED") {}
                        }
                    }
                    val accepted = svc.dispatchGesture(gesture, cb, null)
                    if (!accepted && cont.isActive) cont.resume("REJECTED") {}
                }
                report(
                    "gesture", "raw@$x,$y dur=$dur", outcome == "COMPLETED",
                    "raw dispatchGesture -> $outcome (no builder, no retry, no humanize)",
                    emptyList(), now() - start,
                )
            }
            "byid" -> {
                // findAccessibilityNodeInfosByViewId issues a FRESH cross-process query
                // instead of walking our cached copy of the tree. If this finds the node
                // that a local walk cannot, the tree is reachable and our walk (or the
                // client-side node cache behind it) is what is wrong — not the app.
                val id = op2 ?: "in.swiggy.android:id/item_container"
                val roots = buildList {
                    svc.windows?.forEach { w -> w.root?.let { add(it) } }
                    svc.rootInActiveWindow?.let { add(it) }
                }
                val found = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                roots.forEach { r ->
                    runCatching { found += r.findAccessibilityNodeInfosByViewId(id) ?: emptyList() }
                }
                val descs = found.joinToString(" ; ") {
                    val rr = android.graphics.Rect().also { r -> it.getBoundsInScreen(r) }
                    "desc='${it.contentDescription}' $rr clickable=${it.isClickable}"
                }
                report(
                    "gesture", "byid:$id", found.isNotEmpty(),
                    "roots=${roots.size} matches=${found.size} | $descs",
                    emptyList(), now() - start,
                )
            }
            "flags" -> {
                val si = svc.serviceInfo
                val f = si?.flags ?: 0
                fun has(bit: Int) = (f and bit) != 0
                val before = countNodes(svc)
                // Re-assert the manifest flags at runtime and re-count. If the count jumps,
                // the XML flags were not in effect on this connection.
                si?.let {
                    it.flags = it.flags or
                        android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    svc.serviceInfo = it
                }
                delay(1200)
                val after = countNodes(svc)
                report(
                    "gesture", "flags", true,
                    "flags=0x${Integer.toHexString(f)} " +
                        "INCLUDE_NOT_IMPORTANT=${has(android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)} " +
                        "RETRIEVE_WINDOWS=${has(android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)} " +
                        "REPORT_VIEW_IDS=${has(android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS)} " +
                        "| nodesBefore=$before nodesAfter=$after",
                    emptyList(), now() - start,
                )
            }
            "clickany" -> {
                // Coordinate-independent: ACTION_CLICK the first clickable node with sane
                // on-screen bounds. Answers exactly one question — does the SEMANTIC path
                // reach this app at all, when the synthetic-gesture path is being ignored?
                var picked: android.view.accessibility.AccessibilityNodeInfo? = null
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo) {
                    if (picked != null) return
                    val r = android.graphics.Rect()
                    n.getBoundsInScreen(r)
                    if (n.isClickable && r.left < r.right && r.top < r.bottom &&
                        r.left >= 0 && r.right <= 1240 && r.top > 300
                    ) {
                        picked = n
                        return
                    }
                    for (i in 0 until n.childCount) walk(n.getChild(i) ?: continue)
                }
                svc.rootInActiveWindow?.let { walk(it) }
                val t = picked
                    ?: return report("gesture", "clickany", false, "no clickable node anywhere in active window")
                val r = android.graphics.Rect().also { t.getBoundsInScreen(it) }
                val ok = t.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                report(
                    "gesture", "clickany", ok,
                    "ACTION_CLICK=$ok on <${t.className}> desc='${t.contentDescription}' bounds=$r",
                    emptyList(), now() - start,
                )
            }
            "clicktext" -> {
                val needle = op2 ?: return report("gesture", "clicktext", false, "missing --es q")
                val found = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                val roots = buildList {
                    svc.windows?.forEach { w -> w.root?.let { add(it) } }
                    svc.rootInActiveWindow?.let { add(it) }
                }
                roots.forEach { r ->
                    runCatching { found += r.findAccessibilityNodeInfosByText(needle) ?: emptyList() }
                }
                val target = found.firstOrNull()
                    ?: return report("gesture", "clicktext:$needle", false, "findAccessibilityNodeInfosByText found 0")
                // Climb to the nearest clickable ancestor: labels are usually non-clickable
                // children of the row that actually handles the click.
                var n: android.view.accessibility.AccessibilityNodeInfo? = target
                while (n != null && !n.isClickable) n = n.parent
                val clickTarget = n ?: target
                val ok = clickTarget.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                report(
                    "gesture", "clicktext:$needle", ok,
                    "matches=${found.size} clickableAncestor=${n != null} " +
                        "ACTION_CLICK=$ok on <${clickTarget.className}> desc='${clickTarget.contentDescription}'",
                    emptyList(), now() - start,
                )
            }
            "tree" -> {
                val sb = StringBuilder()
                val wins = svc.windows ?: emptyList()
                sb.append("windows=${wins.size} ")
                sb.append("activeRootPkg=${svc.rootInActiveWindow?.packageName} | ")
                var total = 0
                var clickable = 0
                var hit: String? = null
                var nullKids = 0
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo) {
                    total++
                    runCatching { n.refresh() }
                    val r = android.graphics.Rect()
                    n.getBoundsInScreen(r)
                    if (n.isClickable) {
                        clickable++
                        sb.append("[C desc='${n.contentDescription}' txt='${n.text}' $r] ")
                        if (r.contains(x, y)) hit = "HIT <${n.className}> desc='${n.contentDescription}' $r"
                    }
                    for (i in 0 until n.childCount) {
                        val c = n.getChild(i)
                        if (c == null) { nullKids++; continue }
                        walk(c)
                    }
                }
                wins.forEachIndexed { i, w ->
                    val r = w.root
                    sb.append("w$i[pkg=${r?.packageName},type=${w.type},active=${w.isActive}] ")
                    if (r != null) walk(r)
                }
                if (wins.isEmpty()) svc.rootInActiveWindow?.let { walk(it) }
                sb.append("| nodes=$total clickable=$clickable nullChildren=$nullKids | ${hit ?: "NO HIT at ($x,$y)"}")
                report("gesture", "tree@$x,$y", hit != null, sb.toString(), emptyList(), now() - start)
            }
            else -> report("gesture", "${op}@$x,$y", false, "missing/invalid --es op (use tap|click|tree)")
        }
    }

    /**
     * Deepest node whose screen bounds contain the point AND that reports `isClickable`.
     * Deepest-wins because these layouts nest a clickable row inside a clickable container;
     * the inner one is what a finger would actually hit.
     *
     * Two things this must NOT do, both learned the hard way against Swiggy's bottom nav:
     *  - **Do not prune on ancestor bounds.** A parent's `getBoundsInScreen` does not
     *    reliably contain its children's (scroll containers and clipped rows report bounds
     *    that exclude visible descendants). Pruning made the "99 store" tab — which exists,
     *    is clickable, and does contain the point — invisible to the search.
     *  - **Do not walk only `rootInActiveWindow`.** Sheets, dialogs and nav bars frequently
     *    live in a sibling window; the active root alone misses them.
     */
    private fun deepestClickableAt(
        svc: AuraAccessibilityService,
        x: Int,
        y: Int,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        var best: android.view.accessibility.AccessibilityNodeInfo? = null
        var bestDepth = -1
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (node.isClickable && rect.contains(x, y) && depth > bestDepth) {
                best = node
                bestDepth = depth
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i) ?: continue, depth + 1)
            }
        }
        val roots = buildList {
            svc.windows?.forEach { w -> w.root?.let { add(it) } }
            svc.rootInActiveWindow?.let { add(it) }
        }
        roots.forEach { walk(it, 0) }
        return best
    }

    /** Total nodes + clickable count across every window the service can reach. */
    private fun countNodes(svc: AuraAccessibilityService): String {
        var total = 0
        var clickable = 0
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo) {
            total++
            if (n.isClickable) clickable++
            for (i in 0 until n.childCount) walk(n.getChild(i) ?: continue)
        }
        val roots = buildList {
            svc.windows?.forEach { w -> w.root?.let { add(it) } }
            svc.rootInActiveWindow?.let { add(it) }
        }
        roots.forEach { r -> runCatching { walk(r) } }
        return "$total/$clickable"
    }

    private fun report(
        action: String, request: String, ok: Boolean, summary: String,
        toolCalls: List<String> = emptyList(), elapsedMs: Long = 0, model: String? = null,
    ): String = CompanionTestReport.encode(
        action, request, ok, if (model == null) summary else "model=$model | $summary",
        toolCalls, elapsedMs, now(),
    )

    private fun write(app: Context, report: String) {
        val dir = File(app.filesDir, "companion_test").apply { mkdirs() }
        File(dir, "${now()}.json").writeText(report)
        Log.i(TAG, "result: $report")
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TAG = "CompanionTest"
        const val SETTLE_MS = 1_500L
        const val DEFAULT_WINDOW_MS = 90_000
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
