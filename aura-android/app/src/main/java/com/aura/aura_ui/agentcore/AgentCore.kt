package com.aura.aura_ui.agentcore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aura.aura_ui.BuildConfig
import com.aura.aura_ui.agent.AgentStreamEvent
import com.aura.aura_ui.agent.AppLinksPreamble
import com.aura.aura_ui.agent.DestructiveGoal
import com.aura.aura_ui.agent.RunKeepAwake
import com.aura.aura_ui.agent.hitl.AskUserAnswer
import com.aura.aura_ui.agent.hitl.AskUserBroker
import com.aura.aura_ui.agent.llm.ToolCallDebris
import com.aura.aura_ui.agent.memory.profileBlockOrEmpty
import com.aura.aura_ui.agent.readDeviceFacts
import com.aura.aura_ui.agent.research.ResearchText
import com.aura.aura_ui.agent.research.TaskResearch
import com.aura.aura_ui.agent.research.WebResearch
import com.aura.aura_ui.mcp.bridge.AppBrowserBridge
import com.aura.aura_ui.mcp.bridge.AppDeepLinkBridge
import com.aura.aura_ui.mcp.bridge.AppDeviceBridge
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.notifications.AuraNotificationListenerService
import com.aura.aura_ui.overlay.AuraOverlayService
import com.aura.aura_ui.services.ControlLockStore
import com.aura.mcp.server.SensitivePolicy
import java.io.File
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * The one place the agent core is put together. Everything else in the app reaches the agent
 * through [AgentCore.get].
 *
 * - Contract: one instance per process. Building it does no I/O beyond opening the database; the
 *   phone's tool server connects on the first turn that lists tools.
 * - Reads/Writes: `agentcore.db`, `files/agentcore/` (skills, traces), prefs file [PREFS] for the
 *   always-on switch. Posts to notification channel [CHANNEL].
 * - Fails: with notifications denied, a digest or a scheduled result is still saved as a chat
 *   with a card; only the notification is missing.
 * - Change together: a new tool family is added to the hub's `sources` here and nowhere else.
 */
class AgentCore private constructor(private val app: Context) {

    private val dao = AgentCoreDatabase.get(app).dao()
    private val root = File(app.filesDir, "agentcore")
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val phone = PhoneToolSource(app)

    private val models = SavedSettingsModelProvider(app)
    private val keepAwake = RunKeepAwake(app)
    private val foregroundTurns = AtomicInteger()
    private val lock = ControlLockStore.shared

    /** "OnePlus Nord 4, Android 16". Read once: it is part of the system prompt, which must not change. */
    private val deviceLine by lazy { readDeviceFacts(app).let { "${it.name}, Android ${it.androidRelease}" } }

    /** Threads whose running turn was started by the overlay or by voice; their questions go there. */
    private val surfaceThreads = ConcurrentHashMap.newKeySet<String>()

    val threads = ThreadStore(dao)
    val memory = MemoryStore(dao, installed = ::installedApp)
    val skills = SkillStore(File(root, "skills"), installed = { installedApp(it) != null })

    /** The idle-time review; see [Learning]. Its drafts wait in `skill-drafts/` for the user. */
    val learning = Learning(
        dao, memory, skills, SkillStore(File(root, "skill-drafts")),
        ask = { system, input -> models.resolve(ModelRole.FAST)?.ask(system, input) },
    )
    val jobs = JobStore(dao)
    val telemetry = AgentTelemetry(File(root, "traces"), BuildConfig.VERSION_NAME, keepDays = { LogKeep.days(app) })
    val broker = UserBroker { turn, question, options ->
        if (turn.threadId !in surfaceThreads) {
            null
        } else {
            val answer = AskUserBroker.shared.ask(question, options, ASK_TIMEOUT_MS)
            Result.success((answer as? AskUserAnswer.Answered)?.text)
        }
    }
    val connectors = ConnectorStore(prefs, ProviderKeyStore(app))

    private val native = NativeToolSource(
        "native",
        memoryTools(memory, threads, broker::confirm) + skillTools(skills, broker::confirm) + scheduleTools(jobs),
    )

    /** Connectors come last: the first source to claim a tool name keeps it. */
    private val hub = ToolHub(
        sources = { listOf(native, phone) + connectors.sources() },
        broker = broker,
        targetLabel = phone::targetLabel,
        journal = telemetry.journal,
        settings = { SafetySettings(approvalMode, deniedApps, deniedContacts) },
        appLabel = { AppDeviceBridge(app).run { foregroundPackage()?.takeIf { it != app.packageName }?.let(::appLabelFor) } },
        opensInstalledApp = ::opensInstalledApp,
        findTarget = phone::idsFor,
        elementLabel = phone::labelAt,
        onChain = { skill, ok -> skill?.let { withContext(Dispatchers.IO) { skills.noteChain(it, ok) } } },
    )

    /**
     * Whether [url] resolves to an installed app that is not a browser. A browser also claims
     * https links, so "some app handles it" is not enough; the handler must not handle a plain
     * https page as well.
     */
    private fun opensInstalledApp(url: String): Boolean {
        // An intent: link can name its own package, selector and extras, so where it resolves says
        // nothing about where its data goes. It always counts as sending out.
        if (url.startsWith("intent:", ignoreCase = true)) return false
        val pm = app.packageManager
        val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        val handler = pm.resolveActivity(view, 0)?.activityInfo?.packageName ?: return false
        if (handler == "android") return false // the chooser: more than one app, nothing decided
        val browsers = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")), 0)
            .map { it.activityInfo.packageName }.toSet()
        return handler !in browsers
    }

    /** Apps AURA must never open or act in, as the user typed them. Enforced by [SafetyGate.blocked]. */
    var deniedApps: Set<String>
        get() = prefs.getStringSet(KEY_DENIED_APPS, null).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_DENIED_APPS, value.map(String::trim).filter(String::isNotEmpty).toSet()).apply()

    /** People AURA must never message, by name or number. Enforced by [SafetyGate.blocked]. */
    var deniedContacts: Set<String>
        get() = prefs.getStringSet(KEY_DENIED_CONTACTS, null).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_DENIED_CONTACTS, value.map(String::trim).filter(String::isNotEmpty).toSet()).apply()

    /** How often AURA stops to check with the user; see [ApprovalMode]. */
    var approvalMode: ApprovalMode
        get() = prefs.getString(KEY_APPROVAL_MODE, null)?.let { runCatching { ApprovalMode.valueOf(it) }.getOrNull() }
            ?: ApprovalMode.CONFIRM_SENDS
        set(value) = prefs.edit().putString(KEY_APPROVAL_MODE, value.name).apply()

    val engine = AgentEngine(
        models = models,
        hub = hub,
        threads = threads,
        memory = memory,
        device = ::snapshot,
        profile = { profileBlockOrEmpty(app).takeIf { it.isNotBlank() } },
        telemetry = telemetry,
        readAttachment = ::readAttachment,
        beforeTurn = {
            importOldMemories()
            phone.startTask()
        },
        skillIndex = skills::index,
        research = ::research,
        refusal = { request -> DestructiveGoal.REFUSAL.takeIf { DestructiveGoal.isBulkDestruction(request) } },
        onTurn = ::onTurn,
        deviceLine = { deviceLine },
        digest = { dao.insertDigest(it) },
        preamble = ::preamble,
        isPaused = { lock.isPausedByHuman },
        // "drop it" clears the task it was paused on.
        wasDropped = { lock.activeTask == null },
        maxRequests = { ProviderKeyStore(app).run { getRunMaxRequests(getSelectedEndpointId()) } },
        // Arms the pause-on-touch trigger and shows the overlay's Pause pill while AURA drives.
        onDriving = { driving, request ->
            if (driving) {
                if (lock.activeTask == null) lock.noteTaskStarted(request)
                lock.noteLocalRunStarted()
                com.aura.aura_ui.services.AgentStatusRegistry.noteActing()
            } else if (surfaceThreads.isEmpty()) {
                // A run from the overlay or voice is finished by runTask, which owns the whole run.
                lock.noteLocalRunFinished()
            }
        },
        tidy = ToolCallDebris::clean,
        markRunning = { threadId, request ->
            prefs.edit().apply {
                if (request == null) remove(KEY_RUNNING) else putString(KEY_RUNNING, "$threadId\n$request")
            }.apply()
        },
    )

    /**
     * The thread and request of a foreground run the app died in the middle of, or null.
     * - Contract: only meaningful at launch, before any turn starts; a running turn also holds the mark.
     */
    fun interrupted(): Pair<String, String>? =
        prefs.getString(KEY_RUNNING, null)?.split('\n', limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun forgetInterrupted() = prefs.edit().remove(KEY_RUNNING).apply()

    val ambient = AmbientCoordinator(
        prefs = { TriagePrefs() },
        isSensitivePackage = { SensitivePolicy.screenPackage(it) is SensitivePolicy.Decision.Block },
        notifyDigest = ::showDigest,
    )

    private val jobRunner = JobRunner(engine, threads, jobs) { title, text, threadId -> notify(title, text, threadId.hashCode(), threadId) }

    /** Whether AURA watches notifications and sends digests. Off until the user turns it on. */
    var alwaysOn: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_ON, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ALWAYS_ON, value).apply()
            if (value) start()
        }

    /**
     * Feed one posted notification to triage. Called by `AuraNotificationListenerService`.
     * - Contract: a no-op unless [alwaysOn]. AURA's own notifications are skipped, so a digest
     *   never lands in the next digest.
     */
    fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!alwaysOn || sbn.packageName == app.packageName) return
        val extras = sbn.notification.extras
        ambient.onPosted(
            NotificationSignal(
                key = sbn.key,
                packageName = sbn.packageName,
                appLabel = runCatching { AppDeviceBridge(app).appLabelFor(sbn.packageName) }.getOrNull() ?: sbn.packageName,
                title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                category = sbn.notification.category,
                isConversation = extras?.containsKey(Notification.EXTRA_MESSAGES) == true,
                isOngoing = sbn.isOngoing,
                postedAtMs = sbn.postTime,
            ),
        )
    }

    fun onNotificationRemoved(key: String) {
        if (alwaysOn) ambient.onRemoved(key)
    }

    /**
     * A new request from the chat is the user handing the phone back. A pause with no task left to
     * protect is cleared, as the overlay does; a pause on an unfinished task is left alone.
     */
    fun releaseIdlePause() {
        if (lock.isPausedByHuman && lock.activeTask == null) lock.resume()
    }

    /** A request the chat handed to the overlay: the thread it belongs to and its exact text. */
    @Volatile
    private var handedOff: Pair<String, String>? = null

    /**
     * Whether a chat turn should hand screen work to the overlay, where the user sees the
     * transcript and can pause.
     * - Contract: false when the overlay cannot be drawn; the chat then drives the screen itself.
     */
    fun handsOffScreen(): Boolean = Settings.canDrawOverlays(app)

    /**
     * Run [text] on the overlay as the continuation of the chat turn in [threadId].
     * - Reads/Writes: starts `AuraOverlayService` with `ACTION_RUN_TASK`; the overlay calls
     *   [runTask], which picks the thread up from here.
     */
    fun handOff(threadId: String, text: String) {
        handedOff = threadId to text
        app.startService(
            Intent(app, AuraOverlayService::class.java)
                .setAction(AuraOverlayService.ACTION_RUN_TASK)
                .putExtra(AuraOverlayService.EXTRA_TASK_TEXT, text),
        )
    }

    /**
     * Run one request behind the call shape of the agent under `agent/`, so the overlay and the
     * voice companion can use either agent.
     * - Contract: returns what to say to the user. A request the chat handed off ([handOff])
     *   continues in the chat's thread. Otherwise requests made within [SURFACE_THREAD_IDLE_MS]
     *   of each other share one thread, so "now send it to mom" has its context. A request made
     *   while a turn is running is handed to that turn. Questions the agent asks go to
     *   `AskUserBroker.shared`, which the overlay shows and the companion speaks.
     *   With [remember] false (a benchmark or eval task) the request runs in a thread of its
     *   own that is deleted afterwards, so one task never sees another's history.
     * - Reads/Writes: `ControlLockStore`: marks a local run for its whole length, which arms
     *   pause-on-touch and shows the Pause pill, and clears the active task when it ends.
     */
    suspend fun runTask(text: String, remember: Boolean = true, onEvent: (AgentStreamEvent) -> Unit): String {
        val fromChat = handedOff?.takeIf { it.second == text }?.first
        handedOff = null
        val threadId = fromChat ?: if (remember) surfaceThread() else threads.create().id
        if (fromChat == null && remember && engine.steer(threadId, text)) return "Okay, I'll take that into account."
        surfaceThreads += threadId
        if (lock.activeTask == null) lock.noteTaskStarted(text)
        // Before the run is marked, so the overlay's strip starts on "Thinking…".
        com.aura.aura_ui.services.AgentStatusRegistry.startRun()
        lock.noteLocalRunStarted()
        var answer: String? = null
        var outOfTime = false
        var actions = 0
        var failedActions = 0
        val startedAtMs = System.currentTimeMillis()
        try {
            engine.runTurn(threadId, text, continuesHandOff = fromChat != null).collect { event ->
                when (event) {
                    is AgentEvent.ToolStarted -> onEvent(AgentStreamEvent.ToolStarting(event.tool, event.argsPreview))
                        .also {
                            if (drivesPhone(event.tool)) {
                                actions++
                                // The task is acting now: "Thinking…" leaves the status strip.
                                com.aura.aura_ui.services.AgentStatusRegistry.noteActing()
                            }
                        }
                    is AgentEvent.ToolFinished -> onEvent(
                        if (event.ok) {
                            AgentStreamEvent.ToolCompleted(event.tool, event.resultPreview)
                        } else {
                            AgentStreamEvent.ToolFailed(event.tool, event.resultPreview)
                        },
                    ).also { if (!event.ok && drivesPhone(event.tool)) failedActions++ }
                    is AgentEvent.TurnEnded -> {
                        answer = event.message
                        outOfTime = event.end == TurnEnd.LIMIT
                    }
                    is AgentEvent.AssistantText -> if (!event.final) onEvent(AgentStreamEvent.Narration(event.text))
                    is AgentEvent.PlanUpdated -> onEvent(AgentStreamEvent.Plan(event.steps, event.done))
                    is AgentEvent.Card, is AgentEvent.TurnStarted, is AgentEvent.UserPromptRequested -> Unit
                }
            }
        } finally {
            surfaceThreads -= threadId
            // A question still on screen belongs to a run that is over.
            runCatching { AskUserBroker.shared.cancelAll() }
            lock.noteLocalRunFinished()
            if (!remember) withContext(NonCancellable) { runCatching { threads.delete(threadId) } }
        }
        // The thread keeps the task for SURFACE_THREAD_IDLE_MS, so "keep going" picks it up.
        val reply = (answer ?: "I couldn't finish that.") + if (outOfTime) KEEP_GOING_HINT else ""
        if (fromChat != null) {
            com.aura.aura_ui.agent.OnDeviceReport.forTurn(
                reply, failed = answer == null, cancelled = false, paused = false,
                durationMs = System.currentTimeMillis() - startedAtMs, actions = actions, failedActions = failedActions,
                trail = com.aura.aura_ui.services.StepPill.trail(),
            )?.let { runCatching { threads.append(fromChat, ItemKind.CARD, reportCard(it).encode()) } }
        }
        return reply.also { onEvent(AgentStreamEvent.Finished(it)) }
    }

    /**
     * Hand [text] to a turn the overlay is running right now, without ever starting one.
     * - Contract: true when a running turn took it; false when none is running, so the caller
     *   can say so instead of a second task driving the phone alongside the first.
     */
    suspend fun steerRunning(text: String): Boolean = surfaceThreads.toList().any { engine.steer(it, text) }

    private fun drivesPhone(tool: String) =
        com.aura.mcp.server.McpToolScopes.requiredScopeFor(tool) == com.aura.mcp.bridge.McpScope.WRITE

    /**
     * The report of a run the chat handed to the overlay, as a card in that chat.
     * - Change together: `RunReportOverlay.statusOf`, the same outcomes on the overlay card.
     */
    private fun reportCard(report: com.aura.aura_ui.services.RemoteRun.Report): ChatCard.Result {
        val title = when (report.outcome) {
            com.aura.aura_ui.services.RemoteRun.Outcome.DONE -> "Task complete"
            com.aura.aura_ui.services.RemoteRun.Outcome.PARTIAL -> "Partly done"
            com.aura.aura_ui.services.RemoteRun.Outcome.FAILED -> "Couldn't finish"
            com.aura.aura_ui.services.RemoteRun.Outcome.STOPPED -> "Stopped"
            com.aura.aura_ui.services.RemoteRun.Outcome.PAUSED -> "Paused"
            com.aura.aura_ui.services.RemoteRun.Outcome.WENT_QUIET -> "Run ended"
        }
        val seconds = (report.durationMs / 1000).coerceAtLeast(1)
        val steps = if (report.steps == 1) "1 step" else "${report.steps} steps"
        return ChatCard.Result(title, report.recent.map { "• $it" }, source = "Report · ${seconds}s · $steps")
    }

    private suspend fun surfaceThread(): String {
        val recent = prefs.getString(KEY_SURFACE_THREAD, null)?.let { threads.thread(it) }
            ?.takeIf { System.currentTimeMillis() - it.updatedAtMs < SURFACE_THREAD_IDLE_MS }
        return recent?.id ?: threads.create().id.also { prefs.edit().putString(KEY_SURFACE_THREAD, it).apply() }
    }

    private fun onTurn(started: Boolean, mode: RunMode) {
        if (mode != RunMode.FOREGROUND) return
        if (started) {
            foregroundTurns.incrementAndGet()
            keepAwake.acquire()
        } else if (foregroundTurns.decrementAndGet() <= 0) {
            keepAwake.release()
        }
    }

    private fun installedApp(nameOrPackage: String): Pair<String, String?>? {
        val wanted = nameOrPackage.trim()
        return TaskResearch.launcherApps(app)
            .firstOrNull { it.label.equals(wanted, ignoreCase = true) || it.packageName == wanted }
            ?.let { it.label to it.version }
    }

    /**
     * Look the task up on the web, away from the screen the agent is driving.
     * - Fails: returns null on anything: no model, no network, nothing useful found.
     */
    private suspend fun research(request: String): String? = withContext(Dispatchers.IO) {
        val model = models.resolve(ModelRole.FAST) ?: return@withContext null
        val web = WebResearch(AppBrowserBridge.research(app))
        TaskResearch(
            ask = model::ask,
            fetch = { url -> TaskResearch.httpGet(url) },
            web = { question, hints -> web.search(question, hints) },
        ).research(request, TaskResearch.launcherApps(app), readDeviceFacts(app))?.let { "${it.source}: ${it.text}" }
    }

    /**
     * What the app itself knows that saves the model a step: the direct links AURA's catalog has
     * for the app the request names, and the pages already open in AURA's browser.
     * - Contract: trusted text only. Catalog entries ship in the APK (`deeplink_catalog.json`);
     *   of a tab only its address is given, never its title, which the page controls.
     * - Fails: returns null on anything.
     */
    private suspend fun preamble(request: String): String? = withContext(Dispatchers.IO) {
        val links = runCatching {
            ResearchText.matchApp(request, TaskResearch.launcherApps(app))?.let { target ->
                val catalog = app.assets.open(AppDeepLinkBridge.CATALOG_ASSET).bufferedReader().use { it.readText() }
                AppLinksPreamble.forApp(target.label, AppLinksPreamble.catalogEntries(catalog, target.packageName))
            }
        }.getOrNull()
        val tabs = runCatching { AppBrowserBridge.shared(app).openTabsForPreamble() }.getOrNull().orEmpty()
            .take(MAX_TABS)
            .joinToString("\n") { (_, url, active) -> "- " + url.take(MAX_URL).replace(Regex("[<>\\s]"), "") + if (active) " (in front)" else "" }
            .takeIf { it.isNotBlank() }
            ?.let { "Pages already open in AURA's browser. Use one of these instead of opening it again:\n$it" }
        listOfNotNull(links, tabs).joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    /** A digest becomes a chat holding one card, and a notification that opens it. */
    private suspend fun showDigest(digest: Digest) {
        val thread = threads.create(digest.title)
        threads.append(thread.id, ItemKind.CARD, ChatCard.Result(digest.title, digest.lines, "Notification digest").encode())
        notify(digest.title, digest.lines.joinToString("\n"), DIGEST_ID, thread.id)
    }

    /** Start the periodic worker and install the bundled skills. Safe to call on every app start. */
    fun start() {
        skills.seed(BUNDLED_SKILLS.associateWith { name -> { app.assets.open("skills/$name.md").bufferedReader().use { it.readText() } } })
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AgentWorker>(Schedule.MIN_EVERY_MINUTES.toLong(), TimeUnit.MINUTES).build(),
        )
    }

    /** One pass of background work: due scheduled tasks, the notification digest, the skill curator, the idle review. */
    suspend fun tick() {
        jobRunner.runDue()
        if (alwaysOn) ambient.flush()
        withContext(Dispatchers.IO) { runCatching { skills.curate() } }
        if (engine.isIdle() && batteryAllowsReview()) runCatching { learning.review() }
    }

    /**
     * Copy the old agent's memories in, once ([MemoryImport]). Entries past the cap or refused by
     * [MemoryPolicy] are skipped. Fails: when the old store cannot be read (a broken Keystore), it
     * tries again before the next turn.
     * - Why the voice companion is not repointed: it keeps its own store; this is a copy, not a move.
     */
    private suspend fun importOldMemories() {
        if (prefs.getBoolean(KEY_IMPORTED, false)) return
        withContext(Dispatchers.IO) {
            val old = runCatching { com.aura.aura_ui.agent.memory.EncryptedMemoryService(com.aura.aura_ui.agent.memory.memoryStore(app)).allEntries() }
                .getOrNull() ?: return@withContext
            MemoryImport.select(old).forEach { (text, pinned) ->
                val saved = memory.save(MemoryKind.USER, text, source = MemorySource.IMPORTED)
                if (saved is MemoryStore.SaveResult.Saved && pinned) memory.setPinned(saved.id, true)
            }
            prefs.edit().putBoolean(KEY_IMPORTED, true).apply()
        }
    }

    /** The review spends a model call per run: only on charge, or above [REVIEW_MIN_BATTERY] percent. */
    private fun batteryAllowsReview(): Boolean {
        val battery = app.getSystemService(BatteryManager::class.java) ?: return false
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return battery.isCharging || level > REVIEW_MIN_BATTERY
    }

    private fun snapshot(): DeviceSnapshot {
        val battery = app.getSystemService(BatteryManager::class.java)
        val network = app.getSystemService(ConnectivityManager::class.java)
        val bridge = AppDeviceBridge(app)
        return DeviceSnapshot(
            nowMs = System.currentTimeMillis(),
            zoneId = TimeZone.getDefault().id,
            foregroundApp = runCatching {
                bridge.foregroundPackage()?.takeIf { it != app.packageName }?.let(bridge::appLabelFor)
            }.getOrNull(),
            mediaPlaying = app.getSystemService(AudioManager::class.java)?.isMusicActive == true,
            batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
            charging = battery?.isCharging == true,
            online = runCatching {
                network?.getNetworkCapabilities(network.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }.getOrDefault(true),
            waitingNotifications = AuraNotificationListenerService.current?.snapshots()?.count { !it.isOngoing } ?: 0,
        )
    }

    private fun readAttachment(file: Attachment): ByteArray? = runCatching {
        if (file.sizeBytes > MAX_ATTACHMENT_BYTES) return null
        app.contentResolver.openInputStream(android.net.Uri.parse(file.uri))?.use { it.readBytes() }
    }.getOrNull()

    /** [threadId] is the chat the notification opens, or null to open the app as it was. */
    private fun notify(title: String, text: String, id: Int, threadId: String? = null) {
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "AURA assistant", NotificationManager.IMPORTANCE_DEFAULT))
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.apply { threadId?.let { putExtra(EXTRA_THREAD, it) } }
            ?.let { PendingIntent.getActivity(app, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(app.applicationInfo.icon)
            .setContentTitle(title.take(MAX_TITLE))
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(MAX_TEXT)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    companion object {
        private const val PREFS = "agentcore"
        private const val KEY_ALWAYS_ON = "always_on"
        private const val KEY_APPROVAL_MODE = "approval_mode"
        private const val KEY_SURFACE_THREAD = "surface_thread"
        private const val KEY_RUNNING = "running_run"
        private const val REVIEW_MIN_BATTERY = 30
        private const val KEY_IMPORTED = "old_memories_imported"
        private const val KEY_DENIED_APPS = "denied_apps"
        private const val KEY_DENIED_CONTACTS = "denied_contacts"
        private const val SURFACE_THREAD_IDLE_MS = 15 * 60_000L
        private const val KEEP_GOING_HINT = " I stopped before finishing. Say \"keep going\" to continue."
        private const val ASK_TIMEOUT_MS = 120_000L
        private const val CHANNEL = "aura_agentcore"
        private const val WORK = "agentcore-tick"
        private const val DIGEST_ID = 0x41555241
        private const val MAX_TITLE = 60
        private const val MAX_TEXT = 1_000
        private const val MAX_ATTACHMENT_BYTES = 8L * 1024 * 1024
        /**
         * Skills in `assets/skills/` worth installing. `act-without-screen` is left out because its
         * routes are in the system prompt, and `aura-skill` because it describes the other agent's tools.
         */
        private val BUNDLED_SKILLS = listOf("send-email", "whatsapp-message", "text-entry", "research-the-web")
        private const val MAX_TABS = 6
        private const val MAX_URL = 120
        private const val EXTRA_THREAD = "agentcore_thread"

        /** The chat a notification asked to open. The chat screen opens it and sets this back to null. */
        val openThread = MutableStateFlow<String?>(null)

        /** Call from `MainActivity` with every intent it is started or resumed with. */
        fun noteLaunch(intent: Intent?) {
            intent?.getStringExtra(EXTRA_THREAD)?.let { openThread.value = it }
        }

        @Volatile
        private var instance: AgentCore? = null

        fun get(context: Context): AgentCore = instance ?: synchronized(this) {
            instance ?: AgentCore(context.applicationContext).also { instance = it }
        }

        /**
         * The core, only if the user turned always-on on.
         * - Why: the notification listener runs for every user; this keeps it from opening the
         *   agent's database for people who never enabled the feature.
         */
        fun ifAlwaysOn(context: Context): AgentCore? =
            if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALWAYS_ON, false)) get(context) else null
    }
}

/** Wakes about every 15 minutes to run [AgentCore.tick]. */
class AgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { AgentCore.get(applicationContext).tick() }
        return Result.success()
    }
}
