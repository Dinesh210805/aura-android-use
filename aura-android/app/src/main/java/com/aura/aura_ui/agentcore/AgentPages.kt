package com.aura.aura_ui.agentcore

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.TextButton
import com.aura.aura_ui.presentation.components.AuraRow
import com.aura.aura_ui.ui.theme.auraCard
import com.aura.aura_ui.ui.theme.auraCardStrong
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aura.aura_ui.presentation.components.AuraButton
import com.aura.aura_ui.presentation.components.AuraDivider
import com.aura.aura_ui.presentation.components.AuraEmpty
import com.aura.aura_ui.presentation.components.AuraHint
import com.aura.aura_ui.presentation.components.AuraPage
import com.aura.aura_ui.presentation.components.AuraSection
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for the agent's own pages: chats, run logs, scheduled tasks, memory, skills, connectors.
 *
 * - Contract: no agent logic; it forwards to the stores on [AgentCore]. It never collects
 *   `AgentCore.openThread`, which belongs to `ChatViewModel` alone.
 */
class AgentPagesViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AgentCore.get(app)

    val threads: StateFlow<List<ThreadRow>> = core.threads.threads().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val connectors = MutableStateFlow(core.connectors.configs())
    val connectorStatus = MutableStateFlow<String?>(null)
    val jobs = MutableStateFlow(emptyList<JobRow>())
    val memories = MutableStateFlow(emptyList<MemoryRow>())
    val skills = MutableStateFlow(emptyList<Skill>())
    val drafts = MutableStateFlow(emptyList<Skill>())
    val staleSkills = MutableStateFlow(emptySet<String>())
    val log = MutableStateFlow<List<RunLog>?>(null)

    fun deleteThread(id: String) = viewModelScope.launch { core.threads.delete(id) }

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

    fun loadMemories() = viewModelScope.launch { memories.value = core.memory.all() }

    fun forget(ids: Set<Long>) = viewModelScope.launch {
        ids.forEach { core.memory.forget(it) }
        loadMemories()
    }

    /** Saves an edit; returns why it was refused, or null. */
    suspend fun editMemory(id: Long, text: String, pinned: Boolean): String? =
        core.memory.edit(id, text).also {
            if (it == null) core.memory.setPinned(id, pinned)
            loadMemories()
        }

    /** Keeps staged memories; returns the first refusal (a full memory), or null. */
    suspend fun approve(ids: Collection<Long>): String? =
        ids.firstNotNullOfOrNull { core.memory.approve(it) }.also { loadMemories() }

    fun loadSkills() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val all = core.skills.list()
            skills.value = all
            staleSkills.value = all.filter(core.skills::isStale).map { it.name }.toSet()
            drafts.value = core.learning.drafts.list()
        }
    }

    suspend fun draftBody(name: String): String = withContext(Dispatchers.IO) { core.learning.drafts.read(name).orEmpty() }

    /** Keeps a drafted skill; returns why it could not, or null. */
    suspend fun keepDraft(name: String): String? = withContext(Dispatchers.IO) { core.learning.keepDraft(name) }.also { loadSkills() }

    fun discardDraft(name: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { core.learning.drafts.delete(name) }
        loadSkills()
    }

    fun pinSkill(name: String, pinned: Boolean) = viewModelScope.launch {
        withContext(Dispatchers.IO) { core.skills.setPinned(name, pinned) }
        loadSkills()
    }

    suspend fun skillBody(name: String): String = withContext(Dispatchers.IO) { core.skills.read(name).orEmpty() }

    fun deleteSkill(name: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { core.skills.delete(name) }
        loadSkills()
    }

    fun loadLog() = viewModelScope.launch { log.value = withContext(Dispatchers.IO) { core.telemetry.recent() } }

    suspend fun runDetail(id: String): RunDetail = withContext(Dispatchers.IO) {
        val run = core.telemetry.run(id)
        RunDetail(run, run?.let(core.telemetry::systemPrompt))
    }

    /** Zips [ids]' raw log files (all logs when null) into the cache folder, for [shareLogZip]. */
    suspend fun exportLog(ids: Set<String>?): java.io.File = withContext(Dispatchers.IO) {
        core.telemetry.export(ids, java.io.File(getApplication<Application>().cacheDir, "exports"))
    }

    suspend fun deleteRuns(ids: Set<String>) {
        core.telemetry.delete(ids)
        loadLog()
    }

    val keepDays = MutableStateFlow(LogKeep.days(app))

    /** Saves the retention setting and prunes at once, so a shorter setting frees space now. */
    fun setKeepDays(days: Long) = viewModelScope.launch {
        LogKeep.set(getApplication(), days)
        keepDays.value = days
        core.telemetry.prune()
    }

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
}

// ── Chats ────────────────────────────────────────────────────────────────────

/** Every chat, grouped by day. Tapping one opens it in the chat on Home. */
@Composable
fun AllChatsPage(onOpen: (String) -> Unit, vm: AgentPagesViewModel = viewModel()) {
    val threads by vm.threads.collectAsState()
    val groups = remember(threads) { groupByDay(threads) }
    AuraPage("Chats", scrollable = false) { padding ->
        if (threads.isEmpty()) Box(Modifier.fillMaxSize()) { AuraEmpty("No chats yet", "Start one from Home.") }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            groups.forEach { (label, rows) ->
                item(key = "h-$label") {
                    Text(label, Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp), style = AuraType.footnote, color = LocalAura.current.secondary)
                }
                items(rows, key = { it.id }) { thread ->
                    DeletableRow(thread.title, clock.format(Instant.ofEpochMilli(thread.updatedAtMs)), "Delete ${thread.title}", { vm.deleteThread(thread.id) }) {
                        onOpen(thread.id)
                    }
                }
            }
        }
    }
}

internal fun groupByDay(threads: List<ThreadRow>): List<Pair<String, List<ThreadRow>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return threads.groupBy { thread ->
        val day = Instant.ofEpochMilli(thread.updatedAtMs).atZone(zone).toLocalDate()
        when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today.minusDays(7)) -> "Previous 7 days"
            else -> "Older"
        }
    }.toList()
}

// ── Run logs ─────────────────────────────────────────────────────────────────

/**
 * What the agent did, run by run, newest first. Tap a run for its page; long-press to select
 * runs to delete. The first row sets how long logs are kept. Export shares every raw log file
 * on the phone as one zip.
 */
@Composable
fun RunLogsPage(onOpen: (String) -> Unit, onKeep: () -> Unit, vm: AgentPagesViewModel = viewModel()) {
    val runs by vm.log.collectAsState()
    val keep by vm.keepDays.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadLog() }
    val all = runs.orEmpty()
    Box(Modifier.fillMaxSize()) {
        AuraPage("Run logs", scrollable = false, actions = {
            if (all.isNotEmpty()) {
                com.aura.aura_ui.presentation.components.CircleButton(
                    Icons.Outlined.IosShare,
                    "Export all raw logs",
                    { scope.launch { shareLogZip(context, vm.exportLog(null)) } },
                )
            }
        }) { padding ->
            RunLogList(
                runs = all,
                selected = selected,
                onOpen = onOpen,
                onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                item(key = "keep") {
                    Box(Modifier.fillMaxWidth().auraCard()) {
                        AuraRow("Keep logs", icon = Icons.Outlined.History, value = LogKeep.label(keep), onClick = onKeep)
                    }
                }
                if (runs != null && all.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 280.dp)) {
                            AuraEmpty("No runs yet", "Each task AURA runs shows up here, step by step.")
                        }
                    }
                }
            }
        }
        SelectBar(selected.size, all.size, Modifier.align(Alignment.BottomCenter), { selected = all.map { it.id }.toSet() }, { selected = emptySet() }) { confirm = true }
    }
    if (confirm) {
        val count = selected.size
        ConfirmDelete(if (count == 1) "Delete 1 run?" else "Delete $count runs?", "Their steps, screenshots and model calls are removed from this phone.", { confirm = false }) {
            confirm = false
            val ids = selected
            selected = emptySet()
            scope.launch { vm.deleteRuns(ids) }
        }
    }
}

/**
 * Hands a log zip to the share sheet (Drive, Gmail, Files, a PC over Nearby Share).
 * - Reads/Writes: the cache-dir path declared in `file_paths.xml`, through the app's FileProvider.
 */
internal fun shareLogZip(context: android.content.Context, file: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Export run log"))
}

/** How long run logs stay on the phone. */
@Composable
fun LogKeepPage(vm: AgentPagesViewModel = viewModel()) {
    val aura = LocalAura.current
    val keep by vm.keepDays.collectAsState()
    AuraPage("Keep run logs") {
        AuraSection(footer = "Older logs, their screenshots and model calls are deleted. Logs leave this phone only when you export them.") {
            LogKeep.CHOICES.forEachIndexed { i, days ->
                if (i > 0) AuraDivider(16.dp)
                AuraRow(LogKeep.label(days), onClick = { vm.setKeepDays(days) }, trailing = {
                    if (days == keep) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = aura.label, modifier = Modifier.size(20.dp))
                })
            }
        }
    }
}

/**
 * The bar that appears while rows are selected: the count, Select all or Clear, and Delete.
 * Draws nothing when [count] is 0.
 */
@Composable
internal fun SelectBar(count: Int, total: Int, modifier: Modifier, onAll: () -> Unit, onClear: () -> Unit, onDelete: () -> Unit) {
    if (count == 0) return
    val aura = LocalAura.current
    Row(
        modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth().auraCardStrong().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count selected", Modifier.padding(start = 10.dp).weight(1f), style = AuraType.headline, color = aura.label, maxLines = 1)
        TextButton(onClick = if (count < total) onAll else onClear) {
            Text(if (count < total) "Select all" else "Clear", style = AuraType.callout, color = aura.label)
        }
        TextButton(onClick = onDelete) { Text("Delete", style = AuraType.headline, color = aura.danger) }
    }
}

// ── Scheduled tasks ──────────────────────────────────────────────────────────

private val clock = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

/** Tasks the agent runs on a timer. */
@Composable
fun SchedulesPage(vm: AgentPagesViewModel = viewModel()) {
    val jobs by vm.jobs.collectAsState()
    var prompt by rememberSaveable { mutableStateOf("") }
    var whenText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadJobs() }
    val valid = prompt.isNotBlank() && (Schedule.parseDailyAt(whenText) != null || whenText.trim().toIntOrNull() != null)

    AuraPage("Scheduled tasks") {
        AuraSection(title = "Scheduled", footer = "Scheduled tasks run in the background and can only read. The result arrives as a notification and a chat.") {
            if (jobs.isEmpty()) AuraHint("Nothing scheduled. Add one below, or just ask: \"every day at 8, tell me what's on my calendar\".")
            jobs.forEachIndexed { i, job ->
                if (i > 0) AuraDivider(16.dp)
                val every = job.dailyAtMinute?.let { "Daily at %02d:%02d".format(it / 60, it % 60) } ?: "Every ${job.everyMinutes} min"
                DeletableRow(job.prompt, "$every · next ${clock.format(Instant.ofEpochMilli(job.nextRunMs))}", "Cancel this task", { vm.cancelJob(job.rowid) })
            }
        }
        AuraSection(title = "New task") {
            Field(prompt, { prompt = it }, "What to do")
            AuraDivider(16.dp)
            Field(whenText, { whenText = it }, "When: 08:30, or minutes between runs")
        }
        Spacer(Modifier.height(16.dp))
        AuraButton("Schedule", {
            vm.addJob(prompt, whenText)
            prompt = ""
            whenText = ""
        }, Modifier.fillMaxWidth(), enabled = valid)
    }
}

// ── Memory ───────────────────────────────────────────────────────────────────

/**
 * What the agent remembers about the user. Tap a memory to edit or pin it; long-press to select
 * several to forget. Memories learned from a task that read outside content wait at the top
 * until the user keeps or discards them.
 */
@Composable
fun MemoryPage(vm: AgentPagesViewModel = viewModel()) {
    val all by vm.memories.collectAsState()
    val (staged, memories) = all.partition { it.staged }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf<Set<Long>?>(null) }
    var editing by remember { mutableStateOf<MemoryRow?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.loadMemories() }
    Box(Modifier.fillMaxSize()) {
        AuraPage("Memory") {
            if (staged.isNotEmpty()) {
                AuraSection(
                    title = "AURA learned ${staged.size} ${if (staged.size == 1) "thing" else "things"}",
                    footer = notice ?: "From tasks that read screens, pages or messages. Keep only what is true.",
                ) {
                    staged.forEachIndexed { i, memory ->
                        if (i > 0) AuraDivider(16.dp)
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(memory.text, style = AuraType.body, color = LocalAura.current.label, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.forget(setOf(memory.rowid)) }) { Text("Discard") }
                            TextButton(onClick = { scope.launch { notice = vm.approve(listOf(memory.rowid)) } }) { Text("Keep") }
                        }
                    }
                    if (staged.size > 1) {
                        AuraDivider(16.dp)
                        AuraRow("Keep all", onClick = { scope.launch { notice = vm.approve(staged.map { it.rowid }) } })
                    }
                }
            }
            AuraSection(footer = "AURA uses these in later chats. Tap one to edit or pin it.") {
                if (memories.isEmpty()) AuraHint("Nothing saved yet. Tell AURA something about yourself, like \"I'm vegetarian\".")
                memories.forEachIndexed { i, memory ->
                    if (i > 0) AuraDivider(16.dp)
                    val id = memory.rowid
                    DeletableRow(
                        memory.text,
                        memoryDetail(memory),
                        "Forget this",
                        onDelete = { confirm = setOf(id) },
                        onOpen = { editing = memory },
                        selected = if (selected.isEmpty()) null else id in selected,
                        onSelect = { selected = if (id in selected) selected - id else selected + id },
                    )
                }
            }
            if (selected.isNotEmpty()) Spacer(Modifier.height(72.dp))
        }
        SelectBar(selected.size, memories.size, Modifier.align(Alignment.BottomCenter), { selected = memories.map { it.rowid }.toSet() }, { selected = emptySet() }) {
            confirm = selected
        }
    }
    confirm?.let { ids ->
        ConfirmDelete(if (ids.size == 1) "Forget this?" else "Forget ${ids.size} memories?", "AURA stops using them in chats.", { confirm = null }) {
            vm.forget(ids)
            selected = selected - ids
            confirm = null
        }
    }
    editing?.let { memory ->
        MemoryEditDialog(memory, onDismiss = { editing = null }) { text, pinned -> vm.editMemory(memory.rowid, text, pinned).also { if (it == null) editing = null } }
    }
}

/** "You said · User · Pinned": who the memory came from and what it is about. */
private fun memoryDetail(memory: MemoryRow): String = listOfNotNull(
    when (memory.source) {
        MemorySource.LEARNED.name -> "Learned from a task"
        MemorySource.IMPORTED.name -> "Imported"
        else -> "You said"
    },
    memory.kind.lowercase().replaceFirstChar { it.uppercase() },
    memory.app,
    "Pinned".takeIf { memory.pinned },
).joinToString(" · ")

/** Edits one memory's text and pin. [onSave] returns why the text was refused, or null. */
@Composable
private fun MemoryEditDialog(memory: MemoryRow, onDismiss: () -> Unit, onSave: suspend (String, Boolean) -> String?) {
    var text by remember { mutableStateOf(memory.text) }
    var pinned by remember { mutableStateOf(memory.pinned) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit memory") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it; error = null }, isError = error != null)
                error?.let { Text(it, style = AuraType.footnote, color = LocalAura.current.secondary) }
                TextButton(onClick = { pinned = !pinned }) { Text(if (pinned) "Pinned: kept when you clear learned memories" else "Pin this memory") }
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch { error = onSave(text, pinned) } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Skills ───────────────────────────────────────────────────────────────────

/** Saved how-tos the agent reads before a matching task. */
@Composable
fun SkillsPage(vm: AgentPagesViewModel = viewModel()) {
    val aura = LocalAura.current
    val skills by vm.skills.collectAsState()
    val drafts by vm.drafts.collectAsState()
    val stale by vm.staleSkills.collectAsState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadSkills() }
    AuraPage("Skills") {
        if (drafts.isNotEmpty()) {
            AuraSection(
                title = "AURA drafted ${drafts.size} ${if (drafts.size == 1) "skill" else "skills"}",
                footer = notice ?: "From tasks that worked. Read the steps; keep only what you want AURA to follow.",
            ) {
                drafts.forEachIndexed { i, draft ->
                    if (i > 0) AuraDivider(16.dp)
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            open = if (open == "draft:" + draft.name) null else "draft:" + draft.name
                            scope.launch { body = vm.draftBody(draft.name) }
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(draft.name, style = AuraType.body, color = aura.label)
                        Text(draft.description, style = AuraType.footnote, color = aura.secondary)
                        if (open == "draft:" + draft.name) RichText(body, AuraType.footnote, aura.label)
                        Row {
                            TextButton(onClick = { vm.discardDraft(draft.name) }) { Text("Discard") }
                            TextButton(onClick = { scope.launch { notice = vm.keepDraft(draft.name) } }) { Text("Keep") }
                        }
                    }
                }
            }
        }
        AuraSection(footer = "After a task works, ask AURA to save it as a skill, and it follows the same steps next time.") {
            if (skills.isEmpty()) AuraHint("No skills yet.")
            skills.forEachIndexed { i, skill ->
                if (i > 0) AuraDivider(16.dp)
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (open == skill.name) {
                                open = null
                            } else {
                                open = skill.name
                                scope.launch { body = vm.skillBody(skill.name) }
                            }
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, style = AuraType.body, color = aura.label)
                            Text(skill.description, style = AuraType.footnote, color = aura.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val state = listOfNotNull(
                                "Pinned".takeIf { skill.usage.pinned },
                                "Steps stopped working".takeIf { skill.usage.needsRelearning },
                                "Unused for a month; archived after two".takeIf { skill.name in stale },
                            )
                            if (state.isNotEmpty()) Text(state.joinToString(" · "), style = AuraType.footnote, color = aura.tertiary)
                        }
                        FoldArrow(open == skill.name, aura.tertiary)
                    }
                    if (open == skill.name) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            RichText(body, AuraType.footnote, aura.label)
                            TextButton(onClick = { vm.pinSkill(skill.name, !skill.usage.pinned) }) {
                                Text(if (skill.usage.pinned) "Unpin" else "Pin: never archive this skill")
                            }
                            AuraButton("Delete skill", {
                                vm.deleteSkill(skill.name)
                                open = null
                            }, destructive = true)
                        }
                    }
                }
            }
        }
    }
}

// ── Connectors ───────────────────────────────────────────────────────────────

/** Remote MCP servers whose tools the agent may use. */
@Composable
fun ConnectorsPage(vm: AgentPagesViewModel = viewModel()) {
    val connectors by vm.connectors.collectAsState()
    val status by vm.connectorStatus.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AuraPage("Connectors") {
        AuraSection(title = "Connected") {
            if (connectors.isEmpty()) AuraHint("Connect a service that speaks MCP, such as GitHub, and AURA can use its tools.")
            connectors.forEachIndexed { i, connector ->
                if (i > 0) AuraDivider(16.dp)
                DeletableRow(connector.displayName, "${connector.approvedTools.size} tools", "Remove ${connector.displayName}", { vm.removeConnector(connector.id) })
            }
        }
        AuraSection(title = "Add a connector", footer = status) {
            Field(name, { name = it }, "Name")
            AuraDivider(16.dp)
            Field(url, { url = it }, "MCP server address (https)")
            AuraDivider(16.dp)
            Field(token, { token = it }, "Access token (optional)", secret = true)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuraButton("Use GitHub", {
                name = "GitHub"
                url = GITHUB_MCP_URL
            }, Modifier.weight(1f), primary = false)
            AuraButton("Connect", {
                vm.addConnector(name, url, token)
                token = ""
            }, Modifier.weight(1f), enabled = name.isNotBlank() && url.isNotBlank())
        }
    }
}

/** GitHub's hosted MCP server; it takes a personal access token as the bearer. */
private const val GITHUB_MCP_URL = "https://api.githubcopilot.com/mcp/"

// ── Pieces ───────────────────────────────────────────────────────────────────

/**
 * A table row with a trash button; tapping the row itself runs [onOpen] when given.
 * - Contract: with [onSelect] set, a long press selects the row. [selected] non-null means the
 *   page is selecting: the row shows a check instead of the trash and a tap toggles it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeletableRow(
    title: String,
    detail: String,
    deleteLabel: String,
    onDelete: () -> Unit,
    onOpen: (() -> Unit)? = null,
    selected: Boolean? = null,
    onSelect: (() -> Unit)? = null,
) {
    val aura = LocalAura.current
    val tap = if (selected != null) onSelect else onOpen
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (tap != null || onSelect != null) {
                    Modifier.combinedClickable(onLongClickLabel = "Select", onLongClick = onSelect, onClick = tap ?: {})
                } else {
                    Modifier
                },
            )
            .heightIn(min = 56.dp).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraType.body, color = aura.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = AuraType.footnote, color = aura.secondary)
        }
        if (selected != null) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { SelectMark(selected) }
        } else {
            Box(Modifier.size(48.dp).clickable(onClickLabel = deleteLabel, role = Role.Button, onClick = onDelete), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Delete, contentDescription = deleteLabel, tint = aura.tertiary, modifier = Modifier.size(19.dp))
            }
        }
    }
}

/** A text field as a table row: the label as placeholder. */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false) {
    val aura = LocalAura.current
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(label, style = AuraType.body, color = aura.tertiary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = AuraType.body.copy(color = aura.label),
            cursorBrush = SolidColor(aura.label),
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}
