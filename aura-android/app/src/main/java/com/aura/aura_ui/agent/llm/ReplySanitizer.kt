package com.aura.aura_ui.agent.llm

/**
 * B13: strips model reasoning from a final reply before it reaches the chat bubble,
 * TTS, or the conversation-plane model. Reasoning belongs to the trace only
 * ([AgentLlmTap.Call.reasoning]).
 *
 * Handles the three shapes reasoning models actually emit on the OpenAI-compat wire:
 * - paired `<think>…</think>` / `<thinking>…</thinking>` / `<thought>…</thought>`
 *   blocks inlined in `content` (DeepSeek-R1, Qwen3, GPT-OSS on Groq/OpenRouter;
 *   Gemini 3.x emits `<thought>`);
 * - an orphan closing tag — some providers consume the opening tag, so content
 *   arrives as `reasoning…</think>answer`;
 * - an unclosed opening tag from a truncated generation — nothing after it is answer.
 *
 * Pure and provider-agnostic — unit-tested in `ReplySanitizerTest`.
 */
internal object ReplySanitizer {

    private const val TAG_NAMES = "think(?:ing)?|thought"
    private val PAIRED_BLOCK = Regex("(?is)<(?:$TAG_NAMES)>.*?</(?:$TAG_NAMES)>")
    private val OPENING_TAG = Regex("(?i)<(?:$TAG_NAMES)>")
    private val CLOSING_TAG = Regex("(?i)</(?:$TAG_NAMES)>")
    private val UNCLOSED_TAIL = Regex("(?is)<(?:$TAG_NAMES)>.*$")

    fun stripThinking(reply: String): String {
        var out = PAIRED_BLOCK.replace(reply, "")
        // Orphan closing tag: everything before it is reasoning whose opening tag
        // the provider consumed.
        CLOSING_TAG.find(out)?.let { close ->
            if (!OPENING_TAG.containsMatchIn(out.substring(0, close.range.first))) {
                out = out.substring(close.range.last + 1)
            }
        }
        // Orphan opening tag (truncated generation): drop it and everything after.
        out = UNCLOSED_TAIL.replace(out, "")
        return out.trim()
    }
}
