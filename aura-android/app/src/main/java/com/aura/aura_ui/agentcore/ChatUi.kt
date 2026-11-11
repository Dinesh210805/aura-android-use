package com.aura.aura_ui.agentcore

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The chat screen for the agent core: a drawer with past chats and the agent's panels (scheduled
 * tasks, connections, run log), the conversation, questions from the agent, attachments and stop.
 *
 * - Contract: the screen holds no agent logic. It renders `ThreadStore` rows and forwards the
 *   user's actions to [AgentEngine], [UserBroker], [JobStore] and [ConnectorStore]. Sending while
 *   a turn is running steers that turn instead of starting a second one.
 * - Why plain Material components: this is the working surface for the new agent. Restyle it
 *   with the app's own components before it is the default for every user.
 * - Change together: the route and the drawer links are set up in `AuraAppShell.kt`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AgentCore.get(app)
    private val threadId = MutableStateFlow<String?>(null)
    private var turn: Job? = null

    val threads: StateFlow<List<ThreadRow>> = core.threads.threads().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val current: StateFlow<String?> = threadId
    val items: StateFlow<List<ItemRow>> = threadId
        .flatMapLatest { id -> id?.let(core.threads::items) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val prompts: StateFlow<List<UserBroker.Pending>> = core.broker.pending
    val running = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)
    val plan = MutableStateFlow<AgentEvent.PlanUpdated?>(null)
    val alwaysOn = MutableStateFlow(core.alwaysOn)
    val enabled = MutableStateFlow(core.enabled)
    val connectors = MutableStateFlow(core.connectors.configs())
    val connectorStatus = MutableStateFlow<String?>(null)
    val jobs = MutableStateFlow(emptyList<JobRow>())
    val log = MutableStateFlow(emptyList<LogEntry>())

    init {
        core.start()
    }

    fun setAlwaysOn(on: Boolean) {
        core.alwaysOn = on
        alwaysOn.value = on
    }

    fun setEnabled(on: Boolean) {
        core.enabled = on
        enabled.value = on
    }

    fun loadJobs() = viewModelScope.launch { jobs.value = core.jobs.list() }

    /** [whenText] is a time of day ("08:30") or a number of minutes between runs. */
    fun addJob(prompt: String, whenText: String) = viewModelScope.launch {
        val daily = Schedule.parseDailyAt(whenText)
        core.jobs.add(prompt.trim(), if (daily == null) whenText.trim().toIntOrNull() else null, daily)
        loadJobs()
    }

    fun cancelJob(id: Long) = viewModelScope.launch {
        core.jobs.cancel(id)
        loadJobs()
    }

    fun loadLog() = viewModelScope.launch { log.value = withContext(Dispatchers.IO) { core.telemetry.recent() } }

    fun addConnector(name: String, url: String, token: String) = viewModelScope.launch {
        connectorStatus.value = "Connecting"
        connectorStatus.value = core.connectors.add(name, url, token.ifBlank { null }).fold(
            onSuccess = { "$it tools approved. A tool that changes later stays hidden until you connect again." },
            onFailure = { it.message ?: "Could not connect." },
        )
        connectors.value = core.connectors.configs()
    }

    fun removeConnector(id: String) = viewModelScope.launch {
        core.connectors.remove(id)
        connectors.value = core.connectors.configs()
    }

    fun open(id: String?) {
        threadId.value = id
        plan.value = null
    }

    fun delete(id: String) = viewModelScope.launch {
        if (threadId.value == id) open(null)
        core.threads.delete(id)
    }

    fun answer(promptId: String, answer: String?) = core.broker.answer(promptId, answer)

    fun stop() {
        turn?.cancel()
    }

    fun send(text: String, uris: List<Uri>) {
        if (text.isBlank() && uris.isEmpty()) return
        viewModelScope.launch {
            val id = threadId.value ?: core.threads.create().id.also { threadId.value = it }
            if (running.value && core.engine.steer(id, text)) return@launch
            turn = launch {
                running.value = true
                try {
                    core.engine.runTurn(id, text, uris.mapNotNull(::attachment)).collect { event ->
                        when (event) {
                            is AgentEvent.TurnStarted -> status.value = "Thinking"
                            is AgentEvent.ToolStarted -> status.value = event.tool.replace('_', ' ')
                            is AgentEvent.ToolFinished -> status.value = "Thinking"
                            is AgentEvent.PlanUpdated -> plan.value = event
                            is AgentEvent.AssistantText, is AgentEvent.UserPromptRequested, is AgentEvent.TurnEnded -> Unit
                        }
                    }
                } finally {
                    running.value = false
                    status.value = null
                }
            }
        }
    }

    private fun attachment(uri: Uri): Attachment? = runCatching {
        val resolver = getApplication<Application>().contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            Attachment(
                uri = uri.toString(),
                mimeType = resolver.getType(uri) ?: "application/octet-stream",
                displayName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: "file",
                sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)),
            )
        }
    }.getOrNull()
}

private enum class Panel(val title: String) { SCHEDULES("Scheduled tasks"), CONNECTIONS("Connections"), LOG("Run log") }

/**
 * @param links other places in the app, as label to route, listed at the bottom of the drawer.
 * @param onVoice opens the voice overlay; null hides the mic button.
 * @param onAgentSwitch told when the user turns "use for voice and the overlay" on or off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    links: List<Pair<String, String>> = emptyList(),
    onNavigate: (route: String) -> Unit = {},
    onVoice: (() -> Unit)? = null,
    onAgentSwitch: (Boolean) -> Unit = {},
    vm: ChatViewModel = viewModel(),
) {
    val threads by vm.threads.collectAsState()
    val current by vm.current.collectAsState()
    val items by vm.items.collectAsState()
    val prompts by vm.prompts.collectAsState()
    val running by vm.running.collectAsState()
    val status by vm.status.collectAsState()
    val plan by vm.plan.collectAsState()
    var panel by remember { mutableStateOf<Panel?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    LaunchedEffect(items.size) { if (items.isNotEmpty()) list.animateScrollToItem(items.lastIndex) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Drawer(
                    vm = vm,
                    threads = threads,
                    current = current,
                    links = links,
                    onPanel = { panel = it },
                    onNavigate = onNavigate,
                    onAgentSwitch = onAgentSwitch,
                    close = { scope.launch { drawer.close() } },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, contentDescription = "Chats and tools") }
                Text(
                    threads.firstOrNull { it.id == current }?.title ?: "AURA",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { vm.open(null) }) { Icon(Icons.Default.Add, contentDescription = "New chat") }
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (items.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("What can I do for you?", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Ask a question, hand over a task on this phone, set a reminder, or attach a file.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(items, key = { it.rowid }) { ChatRow(it) }
            }

            AnimatedVisibility(plan != null) { plan?.let { PlanCard(it) } }
            AnimatedVisibility(prompts.isNotEmpty()) { prompts.firstOrNull()?.let { PromptCard(it, vm::answer) } }
            AnimatedVisibility(running) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(status.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            InputBar(running = running, onSend = vm::send, onStop = vm::stop, onVoice = onVoice)
        }
    }

    panel?.let { open ->
        ModalBottomSheet(onDismissRequest = { panel = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(open.title, style = MaterialTheme.typography.titleMedium)
                when (open) {
                    Panel.SCHEDULES -> SchedulesPanel(vm)
                    Panel.CONNECTIONS -> ConnectionsPanel(vm)
                    Panel.LOG -> LogPanel(vm)
                }
            }
        }
    }
}

@Composable
private fun Drawer(
    vm: ChatViewModel,
    threads: List<ThreadRow>,
    current: String?,
    links: List<Pair<String, String>>,
    onPanel: (Panel) -> Unit,
    onNavigate: (String) -> Unit,
    onAgentSwitch: (Boolean) -> Unit,
    close: () -> Unit,
) {
    val alwaysOn by vm.alwaysOn.collectAsState()
    val enabled by vm.enabled.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
        item {
            DrawerRow("New chat", bold = true) {
                vm.open(null)
                close()
            }
        }
        items(threads, key = { it.id }) { thread ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    vm.open(thread.id)
                    close()
                }.padding(start = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    thread.title,
                    Modifier.weight(1f),
                    fontWeight = if (thread.id == current) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { vm.delete(thread.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete chat") }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        items(Panel.entries) { panel ->
            DrawerRow(panel.title) {
                onPanel(panel)
                close()
            }
        }
        item {
            SwitchRow("Use for voice and the overlay", "The mic button and the voice companion run on this agent.", enabled) {
                vm.setEnabled(it)
                onAgentSwitch(it)
            }
        }
        item { SwitchRow("Notification digests", "Urgent ones right away, the rest summarised.", alwaysOn, vm::setAlwaysOn) }
        if (links.isNotEmpty()) item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        items(links) { (label, route) ->
            DrawerRow(label) {
                close()
                onNavigate(route)
            }
        }
    }
}

@Composable
private fun DrawerRow(label: String, bold: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val clock = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())
private val clockSeconds = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** Reminders and repeating tasks: what is scheduled, when it runs next, and a form to add one. */
@Composable
private fun SchedulesPanel(vm: ChatViewModel) {
    val jobs by vm.jobs.collectAsState()
    var prompt by rememberSaveable { mutableStateOf("") }
    var whenText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadJobs() }

    if (jobs.isEmpty()) Text("Nothing scheduled. Add one here, or just ask: \"remind me every day at 8 to check my mail\".")
    jobs.forEach { job ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val every = job.dailyAtMinute?.let { "Daily at %02d:%02d".format(it / 60, it % 60) } ?: "Every ${job.everyMinutes} min"
                Text(
                    "$every · next ${clock.format(Instant.ofEpochMilli(job.nextRunMs))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.cancelJob(job.rowid) }) { Icon(Icons.Default.Delete, contentDescription = "Cancel this task") }
        }
    }
    OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("What to do") })
    OutlinedTextField(whenText, { whenText = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("When: 08:30, or minutes between runs") })
    Button(
        onClick = {
            vm.addJob(prompt, whenText)
            prompt = ""
            whenText = ""
        },
        enabled = prompt.isNotBlank() && (Schedule.parseDailyAt(whenText) != null || whenText.trim().toIntOrNull() != null),
    ) { Text("Schedule") }
    Text(
        "Scheduled tasks run in the background and can only read; they tell you the result in a notification.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Remote MCP servers the agent may use: the list, and a form to add one. */
@Composable
private fun ConnectionsPanel(vm: ChatViewModel) {
    val connectors by vm.connectors.collectAsState()
    val status by vm.connectorStatus.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    connectors.forEach { connector ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${connector.displayName} · ${connector.approvedTools.size} tools", Modifier.weight(1f))
            IconButton(onClick = { vm.removeConnector(connector.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${connector.displayName}")
            }
        }
    }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("MCP server address (https)") })
    OutlinedTextField(
        token,
        { token = it },
        Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Access token (optional)") },
        visualTransformation = PasswordVisualTransformation(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                vm.addConnector(name, url, token)
                token = ""
            },
            enabled = name.isNotBlank() && url.isNotBlank(),
        ) { Text("Connect") }
        TextButton(onClick = {
            name = "GitHub"
            url = GITHUB_MCP_URL
        }) { Text("Use GitHub") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** GitHub's hosted MCP server; it takes a personal access token as the bearer. */
private const val GITHUB_MCP_URL = "https://api.githubcopilot.com/mcp/"

/** The OpenTelemetry spans of recent turns, newest first, with the screenshots the model was sent. */
@Composable
private fun LogPanel(vm: ChatViewModel) {
    val entries by vm.log.collectAsState()
    LaunchedEffect(Unit) { vm.loadLog() }
    if (entries.isEmpty()) Text("No runs logged yet.")
    LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(entries) { entry -> LogRow(entry) }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    var open by remember(entry) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clockSeconds.format(Instant.ofEpochMilli(entry.atMs)), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(entry.name, Modifier.weight(1f), color = if (entry.ok) scheme.onSurface else scheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.image == null) Text("${entry.durationMs} ms", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        entry.image?.let { file ->
            val bitmap = remember(file) { runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
            bitmap?.let { Image(it, contentDescription = "Screenshot sent to the model", Modifier.heightIn(max = if (open) 640.dp else 200.dp)) }
        }
        if (open) {
            entry.details.forEach { (key, value) ->
                Text("$key: $value", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChatRow(item: ItemRow) {
    val scheme = MaterialTheme.colorScheme
    when (item.kind) {
        ItemKind.USER.name -> Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterEnd) {
            Surface(color = scheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(item.text)
                    if (item.attachmentsJson != null) Text("with attachments", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        ItemKind.ASSISTANT.name -> Text(item.text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        ItemKind.TOOL.name -> Text(
            "${if (item.ok == false) "✗" else "✓"} ${item.tool.orEmpty().replace('_', ' ')}",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (item.ok == false) scheme.error else scheme.onSurfaceVariant,
        )
        ItemKind.PROMPT.name -> Text(
            "Asked: ${item.text}",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        else -> Text(item.text, Modifier.padding(horizontal = 16.dp), color = scheme.error)
    }
}

@Composable
private fun PlanCard(plan: AgentEvent.PlanUpdated) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).animateContentSize(),
    ) {
        Column(Modifier.padding(12.dp)) {
            plan.steps.forEachIndexed { index, step ->
                Text("${if (index < plan.done) "✓" else "○"} $step", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PromptCard(prompt: UserBroker.Pending, onAnswer: (String, String?) -> Unit) {
    var typed by rememberSaveable(prompt.id) { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Long questions (a draft or a skill to approve) scroll instead of pushing the buttons off screen.
            Text(prompt.question, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
            if (prompt.options.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), singleLine = true)
                    TextButton(onClick = { onAnswer(prompt.id, typed.ifBlank { null }) }) { Text("Reply") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    prompt.options.forEachIndexed { index, option ->
                        if (index == 0) {
                            Button(onClick = { onAnswer(prompt.id, option) }) { Text(option) }
                        } else {
                            OutlinedButton(onClick = { onAnswer(prompt.id, option) }) { Text(option) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputBar(running: Boolean, onSend: (String, List<Uri>) -> Unit, onStop: () -> Unit, onVoice: (() -> Unit)?) {
    var text by rememberSaveable { mutableStateOf("") }
    var attached by remember { mutableStateOf(emptyList<Uri>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { attached = attached + it }

    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        if (attached.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${attached.size} attached", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { attached = emptyList() }) { Icon(Icons.Default.Close, contentDescription = "Remove attachments") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { runCatching { picker.launch(arrayOf("image/*", "text/*", "application/json")) } }) {
                Icon(Icons.Default.Add, contentDescription = "Attach a file")
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (running) "Add to what AURA is doing" else "Message AURA") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            when {
                running && text.isBlank() -> IconButton(onClick = onStop) { Icon(Icons.Default.Close, contentDescription = "Stop") }
                text.isBlank() && attached.isEmpty() && onVoice != null ->
                    IconButton(onClick = onVoice) { Icon(Icons.Default.Mic, contentDescription = "Talk to AURA") }
                else -> IconButton(
                    onClick = {
                        onSend(text.trim(), attached)
                        text = ""
                        attached = emptyList()
                    },
                    enabled = text.isNotBlank() || attached.isNotEmpty(),
                ) { Icon(Icons.Default.Send, contentDescription = "Send") }
            }
        }
    }
}
