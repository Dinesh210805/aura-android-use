package com.aura.aura_ui.agentcore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
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
import com.aura.aura_ui.agent.DestructiveGoal
import com.aura.aura_ui.agent.RunKeepAwake
import com.aura.aura_ui.agent.hitl.AskUserAnswer
import com.aura.aura_ui.agent.hitl.AskUserBroker
import com.aura.aura_ui.agent.memory.profileBlockOrEmpty
import com.aura.aura_ui.agent.readDeviceFacts
import com.aura.aura_ui.agent.research.TaskResearch
import com.aura.aura_ui.agent.research.WebResearch
import com.aura.aura_ui.mcp.bridge.AppBrowserBridge
import com.aura.aura_ui.mcp.bridge.AppDeviceBridge
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.notifications.AuraNotificationListenerService
import com.aura.mcp.server.SensitivePolicy
import java.io.File
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one place the agent core is put together. Everything else in the app reaches the agent
 * through [AgentCore.get].
 *
 * - Contract: one instance per process. Building it does no I/O beyond opening the database; the
 *   phone's tool server connects on the first turn that lists tools.
 * - Reads/Writes: `agentcore.db`, `files/agentcore/` (skills, traces), prefs file [PREFS] for the
 *   always-on switch. Posts to notification channel [CHANNEL].
 * - Fails: with notifications denied, digests and scheduled results are dropped silently.
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

    /** Threads whose running turn was started by the overlay or by voice; their questions go there. */
    private val surfaceThreads = ConcurrentHashMap.newKeySet<String>()

    val threads = ThreadStore(dao)
    val memory = MemoryStore(dao, installed = ::installedApp)
    val skills = SkillStore(File(root, "skills"))
    val jobs = JobStore(dao)
    val telemetry = AgentTelemetry(File(root, "traces"), BuildConfig.VERSION_NAME)
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
    )

    val engine = AgentEngine(
        models = models,
        hub = hub,
        threads = threads,
        memory = memory,
        device = ::snapshot,
        profile = { profileBlockOrEmpty(app).takeIf { it.isNotBlank() } },
        telemetry = telemetry,
        readAttachment = ::readAttachment,
        beforeTurn = phone::startTask,
        skillIndex = skills::index,
        research = ::research,
        refusal = { request -> DestructiveGoal.REFUSAL.takeIf { DestructiveGoal.isBulkDestruction(request) } },
        onTurn = ::onTurn,
    )

    val ambient = AmbientCoordinator(
        prefs = { TriagePrefs() },
        isSensitivePackage = { SensitivePolicy.screenPackage(it) is SensitivePolicy.Decision.Block },
        notifyNow = { notify("${it.appLabel}: ${it.title.orEmpty()}", it.text.orEmpty(), it.key.hashCode()) },
        notifyDigest = { notify(it.title, it.lines.joinToString("\n"), DIGEST_ID) },
    )

    private val jobRunner = JobRunner(engine, threads, jobs) { title, text, threadId -> notify(title, text, threadId.hashCode()) }

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

    /** Whether the overlay and the voice companion run on this agent instead of the one under `agent/`. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Run one request behind the call shape of the agent under `agent/`, so the overlay and the
     * voice companion can use either agent.
     * - Contract: returns what to say to the user. Requests made within [SURFACE_THREAD_IDLE_MS]
     *   of each other share one thread, so "now send it to mom" has its context. A request made
     *   while a turn is running is handed to that turn. Questions the agent asks go to
     *   `AskUserBroker.shared`, which the overlay shows and the companion speaks.
     */
    suspend fun runTask(text: String, onEvent: (AgentStreamEvent) -> Unit): String {
        val threadId = surfaceThread()
        if (engine.steer(threadId, text)) return "Okay, I'll take that into account."
        surfaceThreads += threadId
        var answer: String? = null
        try {
            engine.runTurn(threadId, text).collect { event ->
                when (event) {
                    is AgentEvent.ToolStarted -> onEvent(AgentStreamEvent.ToolStarting(event.tool, event.argsPreview))
                    is AgentEvent.ToolFinished -> onEvent(
                        if (event.ok) {
                            AgentStreamEvent.ToolCompleted(event.tool, event.resultPreview)
                        } else {
                            AgentStreamEvent.ToolFailed(event.tool, event.resultPreview)
                        },
                    )
                    is AgentEvent.TurnEnded -> answer = event.message
                    is AgentEvent.AssistantText, is AgentEvent.PlanUpdated,
                    is AgentEvent.TurnStarted, is AgentEvent.UserPromptRequested -> Unit
                }
            }
        } finally {
            surfaceThreads -= threadId
        }
        return (answer ?: "I couldn't finish that.").also { onEvent(AgentStreamEvent.Finished(it)) }
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

    /** Start the periodic worker. Safe to call on every app start. */
    fun start() {
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AgentWorker>(Schedule.MIN_EVERY_MINUTES.toLong(), TimeUnit.MINUTES).build(),
        )
    }

    /** One pass of background work: due scheduled tasks, then the digest. */
    suspend fun tick() {
        jobRunner.runDue()
        if (alwaysOn) ambient.flush()
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

    private fun notify(title: String, text: String, id: Int) {
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "AURA assistant", NotificationManager.IMPORTANCE_DEFAULT))
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.let { PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_IMMUTABLE) }
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
        private const val KEY_ENABLED = "use_for_overlay_and_voice"
        private const val KEY_SURFACE_THREAD = "surface_thread"
        private const val SURFACE_THREAD_IDLE_MS = 15 * 60_000L
        private const val ASK_TIMEOUT_MS = 120_000L
        private const val CHANNEL = "aura_agentcore"
        private const val WORK = "agentcore-tick"
        private const val DIGEST_ID = 0x41555241
        private const val MAX_TITLE = 60
        private const val MAX_TEXT = 1_000
        private const val MAX_ATTACHMENT_BYTES = 8L * 1024 * 1024

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
        /** The core, only if the user switched the overlay and voice to it. Cheap to call on every run. */
        fun ifEnabled(context: Context): AgentCore? =
            if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)) get(context) else null

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
