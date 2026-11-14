package com.aura.aura_ui.agentcore

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Check
import com.aura.aura_ui.agent.llm.ThinkingSupport
import com.aura.aura_ui.agent.llm.ReasoningLevel
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aura.aura_ui.agent.llm.LlmEndpoint
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.llm.ModelCatalog
import com.aura.aura_ui.agent.llm.ModelInfo
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.mcp.bridge.CustomEndpointStore
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.presentation.components.AuraMark
import com.aura.aura_ui.presentation.navigation.AuraRoutes
import com.aura.aura_ui.presentation.screens.home.AppPromptMarquee
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.Capsule
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.LocalHaze
import com.aura.aura_ui.ui.theme.glass
import com.aura.aura_ui.ui.theme.auraBackground
import com.aura.aura_ui.ui.theme.auraCard
import com.aura.aura_ui.ui.theme.auraCardStrong
import com.aura.aura_ui.ui.theme.rememberBitmap
import com.google.firebase.auth.FirebaseAuth
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.net.URL
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A provider the user has set up, with the model saved for it. */
data class ModelChoice(val endpointId: String, val provider: String, val model: String, val selected: Boolean)

/** The selected provider's models, as its `/models` listing returned them. */
/** The thinking menu for the selected model: the levels it accepts, in order, and the saved one. */
data class Thinking(val levels: List<ReasoningLevel>, val current: ReasoningLevel)

data class Catalog(val endpointId: String, val models: List<ModelInfo>, val loading: Boolean, val error: String? = null)

/** The signed-in Google account, as far as the chat shows it. All null when signed out. */
data class Profile(val name: String? = null, val email: String? = null, val photoUrl: String? = null) {
    val firstName: String? get() = name?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
}

/**
 * State for the chat screen: the open thread, the running turn, and the model in use.
 *
 * - Contract: holds no agent logic. It forwards the user's actions to [AgentEngine] and
 *   [UserBroker]. Sending while a turn is running steers that turn instead of starting a second
 *   one. It is the only collector of `AgentCore.openThread`, so a notification tap opens its chat.
 * - Reads/Writes: `ProviderKeyStore` (the selected provider and its model).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AgentCore.get(app)
    private val keys = ProviderKeyStore(app)
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

    /** The last turn stopped at its time limit; the chat offers "Keep going". */
    val outOfTime = MutableStateFlow(false)

    /** A run the app died in the middle of, as (thread, request); the chat offers to continue it. */
    val interrupted = MutableStateFlow(core.interrupted()?.takeIf { core.engine.isIdle() })

    /** What the idle review left for the user: (staged memories, skill drafts). */
    val learned = MutableStateFlow(0 to 0)

    fun refreshLearned() = viewModelScope.launch {
        learned.value = withContext(Dispatchers.IO) { core.memory.staged().size to core.learning.drafts.list().size }
    }
    val models = MutableStateFlow(emptyList<ModelChoice>())
    val catalog = MutableStateFlow<Catalog?>(null)
    val profile = MutableStateFlow(Profile())

    /** Null when the selected model has no thinking control, which hides the menu. */
    val thinking = MutableStateFlow<Thinking?>(null)

    init {
        refresh()
        // A notification for a scheduled task or a digest opens its chat.
        viewModelScope.launch {
            AgentCore.openThread.filterNotNull().collect { id ->
                open(id)
                AgentCore.openThread.value = null
            }
        }
    }

    /** Re-read what another screen may have changed: the provider, the model, the account. */
    fun refresh() = viewModelScope.launch {
        models.value = withContext(Dispatchers.IO) { runCatching { modelChoices() }.getOrDefault(emptyList()) }
        thinking.value = withContext(Dispatchers.IO) { runCatching { thinkingNow() }.getOrNull() }
        profile.value = runCatching {
            FirebaseAuth.getInstance().currentUser?.takeIf { !it.isAnonymous }
                ?.let { Profile(it.displayName, it.email, it.photoUrl?.toString()) }
        }.getOrNull() ?: Profile()
    }

    private fun endpoints(): List<LlmEndpoint> {
        val custom = CustomEndpointStore(EncryptedJsonStore(getApplication(), CustomEndpointStore.STORE_NAME)).allEndpoints()
        return (LlmEndpointCatalog.BUILTINS + custom).distinctBy { it.id }
    }

    private fun modelChoices(): List<ModelChoice> {
        val selected = keys.getSelectedEndpointId()
        return endpoints().mapNotNull { endpoint ->
            if (endpoint.requiresKey && !keys.isConfigured(endpoint.id)) return@mapNotNull null
            val model = keys.getSelectedModel(endpoint.id) ?: endpoint.defaultModelId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ModelChoice(endpoint.id, endpoint.displayName, model, endpoint.id == selected)
        }
    }

    /**
     * The selected model's thinking levels ([ThinkingSupport.levelsFor]) and the saved level, clamped
     * to them. Reads the facts its listing gave at selection, so OpenRouter's exact flag wins.
     */
    private fun thinkingNow(): Thinking? {
        val id = keys.getSelectedEndpointId()
        val endpoint = endpoints().firstOrNull { it.id == id } ?: return null
        val model = keys.getSelectedModel(id) ?: endpoint.defaultModelId.ifBlank { return null }
        val levels = ThinkingSupport.levelsFor(endpoint, model, keys.getSelectedModelReasoning(id), keys.getSelectedModelCanDisableThinking(id))
        if (levels.size < 2) return null
        return Thinking(levels, ThinkingSupport.clamp(keys.getGeneration(id).reasoning, levels))
    }

    /**
     * Saves [level] for the selected provider. Settings → Model reads the same value, and the next
     * request uses it: the agent resolves the model at the start of every turn.
     */
    fun setThinking(level: ReasoningLevel) {
        val id = keys.getSelectedEndpointId()
        keys.setGeneration(id, keys.getGeneration(id).copy(reasoning = level))
        thinking.value = thinking.value?.copy(current = level)
    }

    /** Use another provider that is already set up. The model sheet then lists its models. */
    fun pickProvider(choice: ModelChoice) {
        keys.setSelectedEndpointId(choice.endpointId)
        refresh()
    }

    /**
     * Lists [endpointId]'s models from its `/models` endpoint.
     * - Fails: on a network or key error the list holds only the saved model, with the error.
     */
    fun loadCatalog(endpointId: String = keys.getSelectedEndpointId()) = viewModelScope.launch {
        val endpoint = withContext(Dispatchers.IO) { endpoints().firstOrNull { it.id == endpointId } } ?: return@launch
        val saved = keys.getSelectedModel(endpointId) ?: endpoint.defaultModelId
        val fallback = listOfNotNull(saved.takeIf { it.isNotBlank() }?.let { ModelInfo(it, keys.getSelectedModelVisionCapable(endpointId) ?: true) })
        if (catalog.value?.endpointId != endpointId || catalog.value?.error != null) catalog.value = Catalog(endpointId, fallback, loading = true)
        val result = ModelCatalog.fetch(endpoint, keys.getKey(endpointId).orEmpty())
        catalog.value = result.fold(
            onSuccess = { list -> Catalog(endpointId, list.sortedWith(compareByDescending<ModelInfo> { it.visionCapable }.thenBy { it.id }), loading = false) },
            onFailure = { error -> Catalog(endpointId, fallback, loading = false, error = (error as? ModelCatalog.ModelFetchError)?.userMessage ?: "Could not list the models.") },
        )
    }

    /** Saves [model] for the selected provider, with its context window and vision flag. */
    fun chooseModel(model: ModelInfo) {
        keys.saveModelChoice(keys.getSelectedEndpointId(), model.id, model)
        refresh()
    }

    fun open(id: String?) {
        threadId.value = id
        plan.value = null
    }

    /** Continue the run the app died in, from its last saved step; or let it go. */
    fun resume(go: Boolean) {
        val (id, _) = interrupted.value ?: return
        interrupted.value = null
        core.forgetInterrupted()
        if (!go) return
        viewModelScope.launch {
            // A benchmark task's thread is deleted after its run; nothing to continue then.
            if (core.threads.thread(id) == null) return@launch
            open(id)
            send(CONTINUE, emptyList())
        }
    }

    fun answer(promptId: String, answer: String?) = core.broker.answer(promptId, answer)

    /** A tap on a "which one?" card: answers the open question, or is sent as a message. */
    fun pick(name: String) {
        val waiting = prompts.value.firstOrNull { !it.isConfirm }
        if (waiting != null) answer(waiting.id, name) else send(name, emptyList())
    }

    fun stop() {
        turn?.cancel()
    }

    fun send(text: String, uris: List<Uri>) {
        if (text.isBlank() && uris.isEmpty()) return
        viewModelScope.launch {
            val id = threadId.value ?: core.threads.create().id.also { threadId.value = it }
            // Before steering: a side question must not reach the running task.
            Btw.questionIn(text)?.let { core.btw(id, it); return@launch }
            if (running.value && core.engine.steer(id, text)) return@launch
            // Running but nothing to steer: a goal between turns (its judge is thinking) or a turn
            // still starting. The new message replaces it; two turns must never share a thread.
            if (running.value) turn?.cancelAndJoin()
            core.releaseIdlePause()
            outOfTime.value = false
            if (interrupted.value != null) {
                interrupted.value = null
                core.forgetInterrupted()
            }
            turn = launch {
                running.value = true
                try {
                    val goal = Goals.goalIn(text)
                    // A goal stays in the chat for all its turns, so the loop keeps control: no hand-off.
                    val turn = if (goal != null) {
                        Goals.goalLoop(
                            goal,
                            turn = { said, quiet -> core.engine.runTurn(id, said, uris.mapNotNull(::attachment).takeIf { !quiet }.orEmpty(), quiet = quiet) },
                            judge = core::judgeGoal,
                            notice = { core.threads.append(id, ItemKind.NOTICE, it) },
                        )
                    } else {
                        core.engine.runTurn(id, text, uris.mapNotNull(::attachment), handOffScreen = core.handsOffScreen())
                    }
                    turn.collect { event ->
                        when (event) {
                            is AgentEvent.TurnStarted -> status.value = "Thinking"
                            is AgentEvent.ToolStarted -> status.value = StepPhrase.of(event.tool, event.argsPreview, null)
                            is AgentEvent.ToolFinished -> status.value = "Thinking"
                            is AgentEvent.PlanUpdated -> plan.value = event
                            // Work on the screen happens on the overlay, where it can be watched and paused.
                            is AgentEvent.TurnEnded -> when (event.end) {
                                TurnEnd.HANDED_OFF -> core.handOff(id, text)
                                TurnEnd.LIMIT -> outOfTime.value = true
                                else -> Unit
                            }
                            is AgentEvent.AssistantText, is AgentEvent.Card, is AgentEvent.UserPromptRequested -> Unit
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

/**
 * The chat with AURA: a drawer (places in the app and recent chats), the conversation with its
 * cards, and a glass composer with dictation, the model sheet and the voice button.
 *
 * - Contract: no agent logic here or in the files it draws with (`ChatCards.kt`,
 *   `ChatDrawer.kt`, `ModelSheet.kt`, `Dictation.kt`); colours and type come from
 *   `ui/theme/AuraTheme.kt`.
 * - Change together: the routes the drawer opens are registered in `AuraAppShell.kt`.
 *
 * @param onVoice opens the voice overlay; null hides the voice button.
 */
@Composable
fun AgentChatScreen(
    onNavigate: (route: String) -> Unit = {},
    onVoice: (() -> Unit)? = null,
    vm: ChatViewModel = viewModel(),
) {
    val aura = LocalAura.current
    val threads by vm.threads.collectAsState()
    val current by vm.current.collectAsState()
    val items by vm.items.collectAsState()
    val prompts by vm.prompts.collectAsState()
    val running by vm.running.collectAsState()
    val status by vm.status.collectAsState()
    val plan by vm.plan.collectAsState()
    val outOfTime by vm.outOfTime.collectAsState()
    val interrupted by vm.interrupted.collectAsState()
    val learned by vm.learned.collectAsState()
    val profile by vm.profile.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var modelsOpen by remember { mutableStateOf(false) }
    val composerFocus = remember { FocusRequester() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The model or the account may have been changed on another screen.
    LaunchedEffect(Unit) { vm.refresh() }

    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        drawerContent = {
            // Leaves a strip of the chat showing, as ChatGPT does, on any screen width.
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(DRAWER_FRACTION).widthIn(max = DRAWER_MAX),
                drawerContainerColor = aura.surfaceStrong,
                drawerContentColor = aura.label,
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            ) {
                ChatDrawer(
                    vm = vm,
                    threads = threads,
                    current = current,
                    onNavigate = onNavigate,
                    close = { scope.launch { drawer.close() } },
                )
            }
        },
    ) {
        val phoneAccess = rememberPhoneAccess()
        // The content scrolls behind the top bar and the composer, which blur it (LocalHaze).
        val haze = rememberHazeState()
        val density = LocalDensity.current
        var topPx by remember { mutableIntStateOf(0) }
        var bottomPx by remember { mutableIntStateOf(0) }
        val insets = PaddingValues(top = with(density) { topPx.toDp() } + 8.dp, bottom = with(density) { bottomPx.toDp() } + 8.dp)
        CompositionLocalProvider(LocalHaze provides haze) {
            Box(Modifier.fillMaxSize().background(aura.canvas).imePadding()) {
                Box(Modifier.fillMaxSize().hazeSource(haze).auraBackground()) {
                    if (items.isEmpty()) {
                        Welcome(profile.firstName, Modifier.fillMaxSize().padding(insets), onPrompt = { draft = it })
                    } else {
                        Transcript(items, vm::pick, insets, Modifier.fillMaxSize())
                    }
                }
                TopBar(
                    title = threads.firstOrNull { it.id == current }?.title.takeIf { items.isNotEmpty() },
                    profile = profile,
                    onMenu = { scope.launch { drawer.open() } },
                    onNewChat = { vm.open(null) }.takeIf { current != null },
                    onProfile = { onNavigate(AuraRoutes.SETTINGS) },
                    modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { topPx = it.height },
                )
                // Floats under the top bar, so it moves nothing when it appears or goes.
                AnimatedVisibility(
                    !phoneAccess,
                    Modifier.align(Alignment.TopCenter).padding(top = with(density) { topPx.toDp() }),
                ) { AccessChip() }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().onSizeChanged { bottomPx = it.height }) {
                    AnimatedVisibility(plan != null && running) { plan?.let { PlanCard(it) } }
                    AnimatedVisibility(prompts.isNotEmpty()) {
                        prompts.firstOrNull()?.let { prompt ->
                            PromptCard(prompt, vm::answer, onEdit = {
                                draft = "Change it: "
                                runCatching { composerFocus.requestFocus() }
                            })
                        }
                    }
                    AnimatedVisibility(running) { StatusLine(status.orEmpty()) }
                    AnimatedVisibility(interrupted != null && !running) {
                        interrupted?.let { (_, request) ->
                            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PillButton("Continue: ${request.take(40)}", Modifier.weight(1f, fill = false), filled = true) { vm.resume(true) }
                                Spacer(Modifier.width(8.dp))
                                PillButton("Dismiss") { vm.resume(false) }
                            }
                        }
                    }
                    LaunchedEffect(running) { if (!running) vm.refreshLearned() }
                    AnimatedVisibility(!running && learned.first + learned.second > 0) {
                        val n = learned.first + learned.second
                        PillButton("AURA learned $n ${if (n == 1) "thing" else "things"}", Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                            onNavigate(if (learned.first > 0) AuraRoutes.AGENT_MEMORY else AuraRoutes.AGENT_SKILLS)
                        }
                    }
                    AnimatedVisibility(outOfTime && !running) {
                        PillButton("Keep going", Modifier.padding(start = 16.dp, bottom = 8.dp), filled = true) {
                            vm.send(KEEP_GOING, emptyList())
                        }
                    }
                    Composer(
                        text = draft,
                        onText = { draft = it },
                        running = running,
                        focus = composerFocus,
                        vm = vm,
                        onVoice = onVoice,
                        onModels = { modelsOpen = true },
                    )
                }
            }
        }
    }

    if (modelsOpen) {
        ModelSheet(
            vm,
            onDismiss = { modelsOpen = false },
            onMoreSettings = {
                modelsOpen = false
                onNavigate(AuraRoutes.AGENT_BRAIN)
            },
            onAddKey = {
                modelsOpen = false
                onNavigate(AuraRoutes.apiKeys())
            },
        )
    }
}

@Composable
private fun TopBar(
    title: String?,
    profile: Profile,
    onMenu: () -> Unit,
    onNewChat: (() -> Unit)?,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = LocalAura.current
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.Rounded.Menu, "Chats and places", onMenu)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                title?.takeIf { it.isNotBlank() } ?: "AURA",
                style = AuraType.headline,
                color = aura.onSky,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        if (onNewChat != null) {
            GlassIconButton(Icons.Outlined.Edit, "New chat", onNewChat)
            Spacer(Modifier.width(8.dp))
        }
        Avatar(profile, 40, onProfile)
    }
}

@Composable
private fun GlassIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val aura = LocalAura.current
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).glass(CircleShape, elevation = 2.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = aura.label, modifier = Modifier.size(20.dp))
        }
    }
}

/** The account's Google photo, or its initial, or the AURA mark when signed out. */
@Composable
fun Avatar(profile: Profile, sizeDp: Int, onClick: (() -> Unit)? = null) {
    val aura = LocalAura.current
    val photo by rememberBitmap(profile.photoUrl) {
        profile.photoUrl?.let { url -> URL(url).openStream().use(BitmapFactory::decodeStream) }
    }
    val initial = (profile.name ?: profile.email)?.take(1)?.uppercase()
    val picture = photo
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(aura.well)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Account and settings", role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            picture != null -> Image(picture, contentDescription = "Your account", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            initial != null -> Text(initial, style = AuraType.headline, color = aura.label)
            else -> AuraMark(size = (sizeDp * 0.5f).dp)
        }
    }
}

/**
 * Shown under the top bar while the accessibility service is off: without it AURA can answer but
 * cannot use the phone. One tap opens Accessibility with AURA's row picked out.
 */
@Composable
private fun AccessChip() {
    val aura = LocalAura.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier.clip(AuraShape.Pill).background(aura.surfaceStrong)
            .clickable(onClickLabel = "Turn on accessibility", role = Role.Button) { PhoneAccess.openSettings(context) }
            .heightIn(min = 36.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.AccessibilityNew, contentDescription = null, tint = aura.danger, modifier = Modifier.size(16.dp).drawWithContent {
            drawContent()
            drawLine(aura.danger, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height), strokeWidth = 1.6.dp.toPx())
        })
        Text("Accessibility is off", Modifier.padding(start = 8.dp), style = AuraType.caption, color = aura.label)
        Text("Turn on", Modifier.padding(start = 8.dp), style = AuraType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = aura.label)
    }
}

/** Share of the screen width the drawer takes, and its cap on tablets. */
private const val DRAWER_FRACTION = 0.82f
private val DRAWER_MAX = 360.dp

/**
 * The empty chat: the mark, the greeting and the date centred in the space between the top bar
 * and the composer, then the app carousel on a card.
 * - Contract: all spacing is fixed in dp and the block is centred in whatever height is left, so
 *   it sits the same on a small phone and a tall one; the greeting wraps rather than shrinks.
 */
@Composable
private fun Welcome(firstName: String?, modifier: Modifier, onPrompt: (String) -> Unit) {
    val aura = LocalAura.current
    val now = remember { java.time.LocalDateTime.now() }
    val greeting = when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AuraMark(size = 48.dp, tint = aura.onSky)
        Spacer(Modifier.height(20.dp))
        Text(listOfNotNull(greeting, firstName).joinToString(", "), style = AuraType.hero, color = aura.onSky, textAlign = TextAlign.Center)
        Text(
            now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM")),
            Modifier.padding(top = 6.dp),
            style = AuraType.heroSub,
            color = aura.onSkyMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        // The apps on this phone, each with a thing to ask. Tapping the caption puts that prompt in
        // the composer. On a card, so the caption reads on any sky.
        Column(Modifier.fillMaxWidth().widthIn(max = 520.dp).auraCard().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Try asking", style = AuraType.footnote, color = aura.secondary)
            AppPromptMarquee(cs = Capsule.scheme().copy(card = aura.surfaceStrong, ink = aura.label), onPrompt = onPrompt)
        }
    }
}

/** Puts text that would sit on the page onto glass when the page is a sky; plain in Classic. */
@Composable
private fun Modifier.onPage(): Modifier {
    val aura = LocalAura.current
    return if (aura.sky == null) this else this.auraCard(AuraShape.Bubble).padding(horizontal = 8.dp, vertical = 10.dp)
}

/** What the transcript draws: one row, or a run of tool rows folded into one line. */
private sealed interface Entry {
    val key: Long

    data class One(val row: ItemRow) : Entry {
        override val key get() = row.rowid
    }

    data class Steps(val rows: List<ItemRow>) : Entry {
        override val key get() = -rows.first().rowid
    }
}

private fun entries(items: List<ItemRow>): List<Entry> = buildList {
    val steps = mutableListOf<ItemRow>()
    fun flush() {
        if (steps.isNotEmpty()) add(Entry.Steps(steps.toList()))
        steps.clear()
    }
    items.forEach { row ->
        if (row.kind == ItemKind.TOOL.name) {
            steps += row
        } else {
            flush()
            add(Entry.One(row))
        }
    }
    flush()
}

@Composable
private fun Transcript(items: List<ItemRow>, onPick: (String) -> Unit, padding: PaddingValues, modifier: Modifier) {
    val list = rememberLazyListState()
    val shown = remember(items) { entries(items) }
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.animateScrollToItem(shown.lastIndex) }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shown, key = { it.key }) { entry ->
            when (entry) {
                is Entry.Steps -> StepsFold(entry.rows)
                is Entry.One -> ItemView(entry.row, onPick)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ItemView(item: ItemRow, onPick: (String) -> Unit) {
    val aura = LocalAura.current
    val copy = rememberCopy()
    when (item.kind) {
        ItemKind.USER.name -> Box(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp), contentAlignment = Alignment.CenterEnd) {
            // On the sky the bubble is a glass card like AURA's replies: the plain well is
            // nearly transparent there, and ink text on a night sky cannot be read.
            val fill = if (aura.sky != null) Modifier.auraCard(AuraShape.Bubble) else Modifier.clip(AuraShape.Bubble).background(aura.well)
            Column(
                fill
                    .combinedClickable(onClickLabel = null, onLongClickLabel = "Copy message", onLongClick = { copy(item.text) }, onClick = {})
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(item.text, style = AuraType.body, color = aura.label)
                attachmentNames(item.attachmentsJson).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = AuraType.footnote, color = aura.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        ItemKind.ASSISTANT.name -> Column {
            SelectionContainer {
                RichText(item.text, AuraType.body, aura.label, Modifier.padding(horizontal = 12.dp).onPage().padding(horizontal = 6.dp, vertical = 2.dp))
            }
            ReplyActions(item.text, copy)
        }
        ItemKind.CARD.name -> ChatCard.decode(item.text)?.let { CardView(it, onPick) }
        ItemKind.PROMPT.name -> Text(
            "Asked: ${item.text}",
            Modifier.padding(horizontal = 12.dp).onPage().padding(horizontal = 6.dp),
            style = AuraType.footnote,
            color = aura.secondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        else -> Text(
            item.text,
            Modifier.padding(horizontal = 16.dp).clip(AuraShape.Inner).background(aura.danger.copy(alpha = 0.12f)).padding(12.dp),
            style = AuraType.callout,
            color = aura.label,
        )
    }
}

/** Copies text to the clipboard and says so. Android 13+ shows its own clipboard preview, so no toast there. */
@Composable
private fun rememberCopy(): (String) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    return remember(clipboard) {
        { text ->
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * Under each of AURA's replies: copy it as written (Markdown), copy it as plain text, or share it.
 * Plain text drops the Markdown marks, for pasting into a message.
 */
@Composable
private fun ReplyActions(text: String, copy: (String) -> Unit) {
    val aura = LocalAura.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val tint = if (aura.sky != null) aura.onSkyMuted else aura.tertiary
    Row(Modifier.padding(start = 14.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        ReplyAction(Icons.Outlined.ContentCopy, "Copy reply", tint) { copy(text) }
        ReplyAction(Icons.Outlined.TextFields, "Copy as plain text", tint) { copy(plainText(text)) }
        ReplyAction(Icons.Outlined.Share, "Share reply", tint) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, plainText(text))
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun ReplyAction(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/** [markdown] without its marks: headings, emphasis, code fences, link targets, list bullets. Pure. */
internal fun plainText(markdown: String): String = markdown
    .replace(Regex("```[a-zA-Z0-9_-]*\\n?"), "")
    .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
    .replace(Regex("(?m)^#{1,6}\\s+"), "")
    .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
    .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
    .replace(Regex("(?<![*\\w])[*_](?!\\s)(.+?)(?<!\\s)[*_](?![*\\w])"), "$1")
    .replace("`", "")
    .trim()

private fun attachmentNames(json: String?): List<String> = json?.let {
    runCatching { Json.decodeFromString(ListSerializer(Attachment.serializer()), it).map(Attachment::displayName) }.getOrNull()
}.orEmpty()

@Composable
private fun StatusLine(status: String) {
    val aura = LocalAura.current
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).glass(AuraShape.Pill, elevation = 0.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = aura.label, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(status, style = AuraType.footnote, color = aura.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The glass composer: attach · text · model · mic (dictation) · talk to AURA, or send once
 * there is something to send, or stop while a turn runs.
 */
@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    running: Boolean,
    focus: FocusRequester,
    vm: ChatViewModel,
    onVoice: (() -> Unit)?,
    onModels: () -> Unit,
) {
    val aura = LocalAura.current
    var attached by remember { mutableStateOf(emptyList<Uri>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { attached = attached + it }
    val dictation = rememberDictation { heard -> onText(listOf(text.trimEnd(), heard).filter { it.isNotBlank() }.joinToString(" ")) }
    val canSend = text.isNotBlank() || attached.isNotEmpty()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Sending ends typing: the keyboard closes so the answer has the screen.
    val submit = {
        if (canSend) {
            vm.send(text.trim(), attached)
            onText("")
            attached = emptyList()
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp).glass(AuraShape.Composer).padding(12.dp)) {
        if (attached.isNotEmpty()) {
            Row(
                Modifier.padding(bottom = 8.dp).clip(AuraShape.Pill).background(aura.well)
                    .clickable(onClickLabel = "Remove attachments") { attached = emptyList() }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${attached.size} attached", style = AuraType.caption, color = aura.label)
                Icon(Icons.Rounded.Close, contentDescription = null, tint = aura.label, modifier = Modifier.padding(start = 6.dp).size(14.dp))
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 28.dp, max = 140.dp).padding(horizontal = 6.dp)) {
            if (text.isEmpty()) {
                Text(
                    when {
                        dictation.listening -> "Listening…"
                        running -> "Add something to the task"
                        else -> "What can I do for you?"
                    },
                    style = AuraType.body,
                    color = aura.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = AuraType.body.copy(color = aura.label),
                cursorBrush = SolidColor(aura.label),
                maxLines = 6,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { submit() }),
            )
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ComposerButton(Icons.Rounded.Add, "Attach a file") { runCatching { picker.launch(arrayOf("image/*", "text/*", "application/json")) } }
            // The model name takes what is left and shortens first; the buttons keep their 44dp.
            Box(Modifier.weight(1f)) { ModelChip(vm, onModels) }
            ComposerButton(if (dictation.listening) Icons.Rounded.Stop else Icons.Rounded.Mic, if (dictation.listening) "Stop dictation" else "Dictate", active = dictation.listening, onClick = dictation::toggle)
            Spacer(Modifier.width(4.dp))
            when {
                running && text.isBlank() -> RoundButton(Icons.Rounded.Stop, "Stop", onClick = vm::stop)
                canSend || onVoice == null -> RoundButton(Icons.Rounded.ArrowUpward, "Send", enabled = canSend, onClick = submit)
                else -> TalkButton(onVoice)
            }
        }
    }
}

@Composable
private fun ComposerButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val aura = LocalAura.current
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (active) aura.well else Color.Transparent)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = aura.label, modifier = Modifier.size(22.dp))
    }
}

/** Send or stop: a filled circle in the accent. */
@Composable
private fun RoundButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val aura = LocalAura.current
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(if (enabled) aura.accent else aura.well), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = if (enabled) aura.onAccent else aura.tertiary, modifier = Modifier.size(20.dp))
        }
    }
}

/** Talk with AURA: the mark in a filled circle, opening the voice overlay. */
@Composable
private fun TalkButton(onClick: () -> Unit) {
    val aura = LocalAura.current
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Talk with AURA", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(aura.label), contentAlignment = Alignment.Center) {
            AuraMark(size = 22.dp, tint = aura.canvas)
        }
    }
}

/** The model in use, as a chip that opens the model sheet. */
@Composable
private fun ModelChip(vm: ChatViewModel, onClick: () -> Unit) {
    val aura = LocalAura.current
    val models by vm.models.collectAsState()
    val selected = models.firstOrNull { it.selected }
    // One pill, two taps: the name opens the model list, the level opens the thinking menu.
    Row(Modifier.heightIn(min = 44.dp).clip(AuraShape.Pill), verticalAlignment = Alignment.CenterVertically) {
        Text(
            selected?.model?.substringAfterLast('/') ?: "Set up a model",
            Modifier.weight(1f, fill = false).clip(AuraShape.Pill)
                .clickable(onClickLabel = "Choose the model", role = Role.Button, onClick = onClick)
                .padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            style = AuraType.footnote,
            color = aura.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ThinkingLevel(vm)
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = aura.secondary, modifier = Modifier.size(18.dp))
    }
}

/**
 * How hard the model thinks, as a word beside the model name, opening a menu of the levels this
 * model accepts. Not drawn for a model without a thinking control.
 */
@Composable
private fun ThinkingLevel(vm: ChatViewModel) {
    val aura = LocalAura.current
    val thinking by vm.thinking.collectAsState()
    val state = thinking ?: return
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            shortLabel(state.current),
            Modifier.clip(AuraShape.Pill)
                .clickable(onClickLabel = "Choose how hard AURA thinks", role = Role.Button) { open = true }
                .padding(horizontal = 4.dp, vertical = 12.dp),
            style = AuraType.footnote,
            color = aura.secondary,
            maxLines = 1,
        )
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = aura.surfaceStrong,
            shape = AuraShape.Inner,
        ) {
            Text("Thinking", Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), style = AuraType.caption, color = aura.secondary)
            state.levels.forEach { level ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(ThinkingSupport.label(level), style = AuraType.body, color = aura.label)
                            Text(thinkingHint(level), style = AuraType.footnote, color = aura.secondary)
                        }
                    },
                    trailingIcon = {
                        if (level == state.current) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = aura.label, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        vm.setThinking(level)
                        open = false
                    },
                )
            }
        }
    }
}

/** The level in four letters or fewer, so the composer row never truncates. */
private fun shortLabel(level: ReasoningLevel): String = if (level == ReasoningLevel.MEDIUM) "Med" else ThinkingSupport.label(level)

private fun thinkingHint(level: ReasoningLevel): String = when (level) {
    ReasoningLevel.DEFAULT -> "The model decides"
    ReasoningLevel.OFF -> "Fastest, no reasoning"
    ReasoningLevel.LOW -> "Quick reasoning"
    ReasoningLevel.MEDIUM -> "Balanced"
    ReasoningLevel.HIGH -> "Deepest, slowest"
}

private val BULLET = Regex("""^\s*(?:[-*•]|\d+[.)])\s+(.*)$""")

/**
 * Text as the model writes it: paragraphs, "- " and "1." lists, "#" headings and `**bold**`.
 * - Contract: anything else is shown as written. This is not a markdown renderer.
 */
@Composable
fun RichText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val aura = LocalAura.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.trim().lines().forEach { raw ->
            val line = raw.trimEnd()
            val bullet = BULLET.find(line)
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                bullet != null -> Row {
                    Text("•", Modifier.width(18.dp), style = style, color = aura.secondary)
                    Text(inline(bullet.groupValues[1]), style = style, color = color)
                }
                line.startsWith("#") -> Text(inline(line.trimStart('#', ' ')), style = style.copy(fontWeight = FontWeight.SemiBold), color = color)
                else -> Text(inline(line), style = style, color = color)
            }
        }
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    text.replace("`", "").split("**").forEachIndexed { index, part ->
        if (index % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
    }
}

/** Sent by the "Keep going" button; the thread still holds the task and what was done. */
private const val KEEP_GOING = "Keep going from where you stopped."

/** Sent by "Continue" after the app died mid-run; the thread holds the work up to the last step. */
private const val CONTINUE = "Continue the task from where you were stopped. Look at the screen first if you were using it."
