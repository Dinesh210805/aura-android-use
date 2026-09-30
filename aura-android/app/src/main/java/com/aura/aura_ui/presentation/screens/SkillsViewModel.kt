package com.aura.aura_ui.presentation.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.aura_ui.agent.skills.Skill
import com.aura.aura_ui.agent.skills.SkillBudget
import com.aura.aura_ui.agent.skills.SkillFrontmatterParser
import com.aura.aura_ui.agent.skills.SkillSource
import com.aura.aura_ui.agent.skills.UserSkillStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * @property skills user-authored skills (editable, toggleable, deletable)
 * @property bundled first-party skills shipped in assets (read-only, always on)
 * @property enabledIds ids of user skills currently enabled for the agent
 */
data class SkillsUiState(
    val skills: List<Skill> = emptyList(),
    val bundled: List<Skill> = emptyList(),
    val enabledIds: Set<String> = emptySet(),
    val addError: String? = null,
)

class SkillsViewModel(
    private val store: UserSkillStore,
    private val bundledSource: SkillSource? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    init {
        refresh()
        bundledSource?.let { source ->
            viewModelScope.launch {
                val loaded = runCatching { source.load() }.getOrDefault(emptyList())
                _state.value = _state.value.copy(bundled = loaded)
            }
        }
    }

    fun addSkill(name: String, description: String, whenToUse: String, body: String): Boolean {
        validationError(name, body)?.let {
            _state.value = _state.value.copy(addError = it)
            return false
        }
        val created = store.upsert(name, description, whenToUse, body)
        if (created == null) {
            _state.value = _state.value.copy(addError = "A skill needs a name and a description.")
            return false
        }
        _state.value = _state.value.copy(addError = null)
        refresh()
        return true
    }

    /**
     * Edit an existing user skill. Renaming changes the derived id, so the old
     * entry is removed and its enabled/disabled state carries over to the new id.
     */
    fun updateSkill(originalId: String, name: String, description: String, whenToUse: String, body: String): Boolean {
        validationError(name, body)?.let {
            _state.value = _state.value.copy(addError = it)
            return false
        }
        val wasEnabled = store.isEnabled(originalId)
        val updated = store.upsert(name, description, whenToUse, body)
        if (updated == null) {
            _state.value = _state.value.copy(addError = "A skill needs a name and a description.")
            return false
        }
        if (updated.id != originalId) {
            store.delete(originalId)
            store.setEnabled(updated.id, wasEnabled)
        }
        _state.value = _state.value.copy(addError = null)
        refresh()
        return true
    }

    fun toggle(id: String, enabled: Boolean) { store.setEnabled(id, enabled); refresh() }

    fun delete(id: String) { store.delete(id); refresh() }

    fun clearError() { _state.value = _state.value.copy(addError = null) }

    /**
     * SK4 + SK3 — authoring-time validation with real feedback. Without the collision
     * check, saving a skill named like a bundled one "succeeded" in Settings while the
     * repository's more-trusted-wins dedup silently served the BUNDLED version forever.
     */
    private fun validationError(name: String, body: String): String? {
        val id = SkillFrontmatterParser.slug(name)
        if (_state.value.bundled.any { it.id == id }) {
            return "\"${name.trim()}\" is a built-in skill — pick a different name."
        }
        if (body.length > SkillBudget.MAX_BODY_CHARS) {
            return "The steps are too long (${body.length} chars — max ${SkillBudget.MAX_BODY_CHARS})."
        }
        return null
    }

    private fun refresh() {
        val skills = store.listRaw()
        _state.value = _state.value.copy(
            skills = skills,
            enabledIds = skills.filter { store.isEnabled(it.id) }.map { it.id }.toSet(),
        )
    }
}
