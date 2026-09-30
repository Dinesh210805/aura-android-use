package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.memory.MemoryService

/**
 * Static-first system-instruction assembly (umbrella §6 / V.6): the cacheable Persona prefix, then a
 * clearly-delimited dynamic tail of recalled context. The tail is the single justified cache-break
 * (DANGEROUS_uncached reason: it changes per session start). buildSessionContext already excludes
 * `sensitive` entries (V.7), so a sensitive memory can NEVER leak into the unprompted prefix -- the
 * privacy invariant is structural, enforced by the store, not re-implemented here.
 */
class CompanionPromptAssembler(
    private val memory: MemoryService,
    /**
     * What this device's user asked to be called, or null when unknown — which is
     * the state of every fresh install. See [Persona.text]: unknown means the
     * persona speaks in plain second person instead of assuming a name or gender.
     */
    private val ownerName: String? = null,
    /** Languages the user listed in Settings; empty falls back to pure mirroring. */
    private val languages: List<String> = emptyList(),
) {
    /**
     * @param pendingResume Build 3: the [ResumeOffer.voicePrompt] for an interrupted run, if one
     * is worth offering. When present it becomes an "# Unfinished task" section so the model can
     * proactively ask to continue and route to `drive_phone(resume=true)` — never auto-resumes.
     */
    suspend fun assemble(nowEpochMs: Long, pendingResume: String? = null, briefing: String? = null): String {
        val tail = runCatching { memory.buildSessionContext(nowEpochMs) }.getOrDefault("")

        // Reminders are one-shot, and the tool that used to mark them delivered (`get_commitments`)
        // no longer exists — the whole memory surface moved behind ask_aura. Without this, a due
        // reminder would be injected into EVERY session's context forever.
        //
        // Marked at injection rather than when AURA speaks them: the context above is built once
        // per session and AURA is instructed to relay what is in it, so this is the moment they are
        // handed over. The trade is honest and worth naming — a session that dies before AURA gets
        // a word out loses that reminder. Tracking delivery through the spoken turn would avoid it,
        // at the cost of per-reminder state on a plane that has none, and the failure it prevents
        // (a reminder repeating every session until the end of time) is the worse one.
        runCatching {
            memory.dueCommitments(nowEpochMs).map { it.id }
                .takeIf { it.isNotEmpty() }
                ?.let { memory.markDelivered(it) }
        }
        // Speech style shapes the persona rather than joining the recalled-context tail: how the
        // user talks is an instruction about AURA's own voice, not a fact for it to recite back.
        val style = runCatching { memory.styleNotes() }.getOrDefault(emptyList())
        return buildString {
            append(Persona.text(ownerName, style, languages))
            if (tail.isNotBlank()) append("\n\n# Context I remember\n").append(tail)
            if (!briefing.isNullOrBlank()) append("\n\n").append(briefing)
            if (!pendingResume.isNullOrBlank()) {
                append("\n\n# Unfinished task\n").append(pendingResume)
                append(
                    "\nIf it still makes sense, offer to continue it; if the user agrees, call " +
                        "drive_phone with resume=true (no task).",
                )
            }
        }
    }
}
