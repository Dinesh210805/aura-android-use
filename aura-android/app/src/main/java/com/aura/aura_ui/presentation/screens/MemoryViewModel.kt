package com.aura.aura_ui.presentation.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.aura_ui.agent.memory.AppFactEntry
import com.aura.aura_ui.agent.memory.Commitment
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.agent.memory.EncryptedLearningsStore
import com.aura.aura_ui.agent.memory.EncryptedMemoryService
import com.aura.aura_ui.agent.memory.FactKind
import com.aura.aura_ui.agent.memory.LearningEntry
import com.aura.aura_ui.agent.memory.MemoryEntry
import com.aura.aura_ui.agent.memory.MemoryPrefs
import com.aura.aura_ui.agent.memory.MemoryType
import com.aura.aura_ui.agent.memory.UserProfile
import com.aura.aura_ui.agent.memory.UserProfileStore
import com.aura.aura_ui.agent.memory.memoryStore
import com.aura.aura_ui.agent.memory.parseLanguages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One app's lessons, resolved for display (spec 2026-07-17 per-app Memory UI). */
data class AppLessonsUi(
    val appPackage: String,
    val appLabel: String,
    val paths: List<LearningEntry>,
    val recoveries: List<AppFactEntry>,
    val quirks: List<AppFactEntry>,
    val antiPatterns: List<AppFactEntry>,
)

/** Pure grouping: store map → sorted display groups (unit-tested without Android). */
fun groupLessons(
    byApp: Map<String, EncryptedLearningsStore.AppLessons>,
    labelOf: (String) -> String,
): List<AppLessonsUi> =
    byApp.map { (pkg, lessons) ->
        AppLessonsUi(
            appPackage = pkg,
            appLabel = labelOf(pkg),
            paths = lessons.paths.sortedByDescending { it.successCount },
            recoveries = lessons.facts.filter { it.kind == FactKind.RECOVERY },
            quirks = lessons.facts.filter { it.kind == FactKind.QUIRK },
            antiPatterns = lessons.facts.filter { it.kind == FactKind.ANTI_PATTERN },
        )
    }.sortedBy { it.appLabel.lowercase() }

/**
 * Assistant memory split the same way the prompt splits it (spec 2026-07-28), so what the user
 * sees here matches what the model is actually told. Action logs and references are deliberately
 * absent from the first three groups: they are recall-only and would otherwise read as things
 * AURA "knows".
 */
data class AssistantMemoryUi(
    val facts: List<MemoryEntry> = emptyList(),
    val preferences: List<MemoryEntry> = emptyList(),
    val style: List<MemoryEntry> = emptyList(),
    val episodes: List<MemoryEntry> = emptyList(),
    val other: List<MemoryEntry> = emptyList(),
) {
    val isEmpty: Boolean
        get() = facts.isEmpty() && preferences.isEmpty() && style.isEmpty() &&
            episodes.isEmpty() && other.isEmpty()
}

/**
 * Pure grouping, ranked to mirror [EncryptedMemoryService.buildSessionContext]: pinned first,
 * then best-established, then freshest. Unit-tested without Android.
 */
fun groupAssistantMemory(entries: List<MemoryEntry>): AssistantMemoryUi {
    // Legacy PROJECT rows are absent by construction: the only source here is
    // EncryptedMemoryService.allEntries(), which reclassifies them on read. If a future caller
    // bypasses that reader, a legacy row would become invisible — and so undeletable.
    fun rank(of: List<MemoryEntry>) = of.sortedWith(
        compareByDescending<MemoryEntry> { it.pinned }
            .thenByDescending { it.confirmations }
            .thenByDescending { it.freshestAtEpochMs },
    )
    val byType = entries.groupBy { it.type }
    return AssistantMemoryUi(
        facts = rank(byType[MemoryType.USER].orEmpty()),
        preferences = rank(byType[MemoryType.FEEDBACK].orEmpty()),
        style = rank(byType[MemoryType.STYLE].orEmpty()),
        episodes = byType[MemoryType.EPISODE].orEmpty().sortedByDescending { it.freshestAtEpochMs },
        other = (byType[MemoryType.ACTION_LOG].orEmpty() + byType[MemoryType.REFERENCE].orEmpty())
            .sortedByDescending { it.freshestAtEpochMs },
    )
}

data class MemoryUiState(
    val assistant: AssistantMemoryUi = AssistantMemoryUi(),
    val appLessons: List<AppLessonsUi> = emptyList(),
    val commitments: List<Commitment> = emptyList(),
    val learningsEnabled: Boolean = true,
    /** Name + languages. Not memory entries — see [UserProfile] for why they are kept apart. */
    val profile: UserProfile = UserProfile(),
    /**
     * False when the Keystore was unavailable and the fail-closed memory store is refusing to
     * persist. Surfaced because the alternative symptom is "AURA forgot my name" with no cause
     * anywhere in the UI.
     */
    val memoryEncrypted: Boolean = true,
)

/**
 * Memory inspector: read-only over what the agent authored (V.7 — no hand-adding memories), plus
 * the one hand-authored part, the [UserProfile] card (name + languages).
 */
class MemoryViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    // M8 — memory + learnings hold user-derived PII; fail closed rather than persist plaintext.
    private val memoryPrefsStore = memoryStore(appContext)
    private val memory = EncryptedMemoryService(memoryPrefsStore)
    private val profileStore = UserProfileStore(appContext)
    private val learnings = EncryptedLearningsStore(EncryptedJsonStore(appContext, "aura_learnings", failClosedWhenUnencrypted = true))
    private val prefs = MemoryPrefs(appContext)

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = MemoryUiState(
            assistant = groupAssistantMemory(memory.allEntries()),
            appLessons = groupLessons(learnings.lessonsByApp()) { pkg ->
                runCatching {
                    appContext.packageManager.getApplicationLabel(
                        appContext.packageManager.getApplicationInfo(pkg, 0),
                    ).toString()
                }.getOrDefault(pkg)
            },
            // M3/M13 — commitments were invisible here, so the user couldn't see
            // (or stop) why the companion kept reminding.
            commitments = memory.allCommitments(),
            learningsEnabled = prefs.learningsEnabled,
            profile = profileStore.load(),
            memoryEncrypted = memoryPrefsStore.isEncrypted,
        )
    }

    /** Settings → Memory: what AURA calls them. Blank clears it back to the unnamed persona. */
    fun setName(name: String) { profileStore.setName(name); refresh() }

    /** Free-text list ("English, Tamil") — parsed, de-duplicated and capped by [parseLanguages]. */
    fun setLanguages(raw: String) { profileStore.setLanguages(parseLanguages(raw)); refresh() }

    fun setLearningsEnabled(enabled: Boolean) { prefs.learningsEnabled = enabled; refresh() }

    fun deletePath(appPackage: String, goalType: String, steps: List<String>) {
        viewModelScope.launch { learnings.deletePath(appPackage, goalType, steps); refresh() }
    }

    fun deleteFact(appPackage: String, kind: FactKind, text: String) {
        viewModelScope.launch { learnings.deleteFact(appPackage, kind, text); refresh() }
    }

    fun forgetApp(appPackage: String) {
        viewModelScope.launch { learnings.forgetApp(appPackage); refresh() }
    }

    /** Drop one wrong memory without wiping the store — the correction path pinning implies. */
    fun deleteMemory(id: String) {
        viewModelScope.launch { memory.delete(id); refresh() }
    }

    /** Pin keeps a memory out of eviction and first into the model's context. */
    fun setMemoryPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { memory.setPinned(id, pinned); refresh() }
    }

    /** Mark a reminder done from the inspector (M3's user-side completion path). */
    fun dismissCommitment(id: String) {
        viewModelScope.launch {
            memory.completeCommitments(listOf(id))
            refresh()
        }
    }

    fun clearAll() {
        memory.clear()
        EncryptedJsonStore(appContext, "aura_learnings", failClosedWhenUnencrypted = true).clearAll()
        refresh()
    }
}
