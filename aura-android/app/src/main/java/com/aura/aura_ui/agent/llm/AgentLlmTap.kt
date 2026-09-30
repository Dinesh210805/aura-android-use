package com.aura.aura_ui.agent.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide tap that lets the on-device agent's session logger observe each
 * LLM round-trip without coupling the (static, provider-agnostic) OpenAI-compat
 * HTTP client to the logger.
 *
 * [AgentRunLogger][com.aura.aura_ui.mcp.log.AgentRunLogger] installs a [sink] for
 * the duration of a run; the interceptor in [OpenAiCompatProvider] invokes it with
 * the parsed request/response. Only one on-device run is active at a time
 * (`agentRunJob`), so a single nullable sink is sufficient.
 *
 * Security: the sink receives the chat *body* only — never request headers — so the
 * bearer API key is never exposed. Large base64 image parts are elided to a
 * `[image NNN KB]` placeholder so the captured prompt stays readable and small.
 */
object AgentLlmTap {

    /** A single observed LLM call, already parsed into readable text. */
    data class Call(
        val provider: String,
        val model: String,
        /**
         * The **per-turn input delta** — the newest request message only (the tool
         * result the model is reacting to, or the user goal on turn 0). The shared
         * system block is carried separately in [systemPrompt]; older history is
         * redundant with the trace timeline and is dropped. (Trace-v2.)
         */
        val prompt: String,
        val response: String,
        /** The system/instruction block, so the logger can store it once per run. Null if absent. */
        val systemPrompt: String?,
        /** Thinking/reasoning text from a thinking model (Gemini 3.x `reasoning_content`), when present. */
        val reasoning: String?,
        /** `finish_reason` from the response (`stop`/`tool_calls`/`length`/…), when present. */
        val finishReason: String?,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val totalTokens: Int?,
        /** Prompt tokens served from a server-side cache, when the provider reports it. */
        val cachedTokens: Int?,
        /** Completion tokens spent on hidden reasoning (thinking models), when reported. */
        val reasoningTokens: Int?,
        val durationMs: Long,
        /** A2 diagnostic: where this request stopped matching the previous one ([PrefixProbe]). */
        val prefixProbe: String? = null,
        /**
         * Size of the whole outgoing request body, and of the `tools` array inside it.
         *
         * Exists because [prompt] is the per-turn DELTA only, so the trace held ~800 chars of text
         * for calls the provider charged 14k input tokens for — under 30% attributable. The tool
         * schemas ride in `tools` on every request and were invisible, which is where most of the
         * unexplained mass was. With these three, input cost is accountable per call instead of
         * inferred.
         */
        val requestBytes: Int = 0,
        val toolsBytes: Int = 0,
        val toolCount: Int = 0,
        /** The exact HTTP bodies, untouched. The logger writes them to disk; nothing trims them. */
        val requestBody: String = "",
        val responseBody: String = "",
        /** Null for an agent turn; [PURPOSE_RESEARCH_PHRASE] for the pre-task research's phrase call. */
        val purpose: String? = null,
    )

    /**
     * The research phrase call runs beside turn 1 through the same provider code, so it reaches
     * this tap too. It is recognised by its system instruction and kept out of everything that
     * measures the RUN: the token budget, the cache-prefix probe, and tool-call-to-turn links.
     */
    const val PURPOSE_RESEARCH_PHRASE = "research-phrase"
    const val PURPOSE_RESEARCH_READ = "research-read"

    @Volatile
    var sink: ((Call) -> Unit)? = null

    /** The last traced request body, for [PrefixProbe]. Only held while a trace is capturing. */
    @Volatile
    private var lastRequestBody: String? = null

    /** Called when a run starts, so its first call is not diffed against the previous run's last. */
    /** A stretch of the phrase instruction that survives JSON escaping unchanged. */
    private val RESEARCH_MARKER =
        com.aura.aura_ui.agent.research.ResearchText.PHRASE_INSTRUCTION.substring(0, 60)

    /** Same, for the call that reads a web page into a note (F9: it was logged as an agent turn). */
    private val RESEARCH_READ_MARKER =
        com.aura.aura_ui.agent.research.ResearchText.EXTRACT_INSTRUCTION.substring(0, 60)

    fun resetPrefixProbe() { lastRequestBody = null }

    /**
     * F3 — the run budget's feed of the provider's own token accounting.
     *
     * Deliberately separate from [sink]: the budget must be corrected by real usage whether or not
     * anyone is capturing a trace, and a run must not have to enable logging to be metered
     * accurately. Installed by `AuraAgent` for the duration of a run and cleared in its `finally`.
     *
     * Invoked on the OkHttp thread, so the receiver must be safe to call off the agent coroutine.
     */
    @Volatile
    var usageSink: ((TokenUsage) -> Unit)? = null

    /** True when anything wants the call parsed — lets the interceptor skip the work otherwise. */
    val isActive: Boolean get() = sink != null || usageSink != null

    /**
     * The `usage` object, or null when the body carries none.
     *
     * Exists because a provider error body is not always a JSON **object**: captured from the
     * device 2026-09-22, Gemini returned its 503 wrapped in an array — `[{"error":{...}}]`. The
     * previous `JSONObject(responseBody)` threw `JSONException` on that shape, and the only trace
     * left was "Failed to parse token usage for budget", which names the wrong thing entirely: the
     * budget was fine, the *model* was refusing work. Unwrapping the array keeps the log honest,
     * and an error body simply has no usage to report.
     */
    private fun usageObjectOrNull(responseBody: String): JSONObject? {
        val trimmed = responseBody.trimStart()
        val root = when {
            trimmed.startsWith("[") -> JSONArray(responseBody).optJSONObject(0)
            trimmed.startsWith("{") -> JSONObject(responseBody)
            else -> null
        } ?: return null
        return root.optJSONObject("usage")
    }

    /**
     * Parse a chat-completions request+response pair and emit a [Call]. Failures are
     * swallowed (logging must never break the LLM path).
     */
    fun report(host: String, requestBody: String, responseBody: String, durationMs: Long) {
        val s = sink
        val u = usageSink
        if (s == null && u == null) return
        val researchPurpose = when {
            requestBody.contains(RESEARCH_MARKER) -> PURPOSE_RESEARCH_PHRASE
            requestBody.contains(RESEARCH_READ_MARKER) -> PURPOSE_RESEARCH_READ
            else -> null
        }
        val isResearch = researchPurpose != null
        // Usage first and in its own runCatching: the budget must still be corrected even if the
        // richer trace parsing below throws on an unusual body shape.
        if (u != null && !isResearch) {
            runCatching {
                u(parseTokenUsage(usageObjectOrNull(responseBody)))
            }.onFailure { Log.w(TAG, "Failed to parse token usage for budget", it) }
        }
        if (s == null) return
        runCatching {
            val provider = providerFromHost(host)
            val reqObj = JSONObject(requestBody)
            val model = reqObj.optString("model", "(unknown)")
            val messages = reqObj.optJSONArray("messages")
            // Trace-v2: split the system block out (logged once per run) and keep only
            // the per-turn delta (the newest message) instead of the whole transcript.
            val systemPrompt = firstSystemText(messages)
            val prompt = lastMessageDelta(messages)
            val respObj = runCatching { JSONObject(responseBody) }.getOrNull()
            val rendered = respObj?.let { extractAssistant(it) } ?: responseBody.take(4_000)
            // Gemini inlines its thought in `content`; move it to `reasoning` where readers look.
            val jsonReasoning = respObj?.let { extractReasoning(it) }
            val split = if (jsonReasoning != null) SplitThought(jsonReasoning, rendered) else splitInlineThought(rendered)
            val toolsJson = reqObj.optJSONArray("tools")
            val usage = parseTokenUsage(respObj?.optJSONObject("usage"))
            s(
                Call(
                    provider = provider,
                    model = model,
                    prompt = prompt,
                    response = split.body,
                    systemPrompt = systemPrompt,
                    reasoning = split.reasoning,
                    finishReason = respObj?.let { extractFinishReason(it) },
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    totalTokens = usage.totalTokens,
                    cachedTokens = usage.cachedTokens,
                    reasoningTokens = usage.reasoningTokens,
                    durationMs = durationMs,
                    prefixProbe = if (isResearch) null else PrefixProbe.describe(lastRequestBody, requestBody),
                    requestBytes = requestBody.length,
                    toolsBytes = toolsJson?.toString()?.length ?: 0,
                    toolCount = toolsJson?.length() ?: 0,
                    requestBody = requestBody,
                    responseBody = responseBody,
                    purpose = researchPurpose,
                ),
            )
        }.onFailure { Log.w(TAG, "Failed to parse LLM call for tap", it) }
        if (!isResearch) lastRequestBody = requestBody
    }

    /** Token accounting for one call. A null field means "not reported" — never guessed as zero. */
    data class TokenUsage(
        val promptTokens: Int?,
        val completionTokens: Int?,
        val totalTokens: Int?,
        val cachedTokens: Int?,
        val reasoningTokens: Int?,
    )

    /**
     * Parse a chat-completions `usage` object across the shapes this app's providers
     * actually emit. Every endpoint (Groq, OpenRouter, Gemini's OpenAI-compat endpoint,
     * or any user-added custom OpenAI-compat endpoint) rides the same client, so this
     * must not assume one vendor's field names — it tries each known spelling in turn
     * and takes the first present value. Pure function: no I/O, safe to unit test
     * directly against captured sample `usage` blobs per provider.
     */
    fun parseTokenUsage(usage: JSONObject?): TokenUsage {
        if (usage == null) return TokenUsage(null, null, null, null, null)
        val cachedTokens =
            // OpenAI / Gemini OpenAI-compat endpoint.
            usage.optJSONObject("prompt_tokens_details")?.optIntOrNull("cached_tokens")
                // DeepSeek and other OpenAI-compat providers that skip the nested object.
                ?: usage.optIntOrNull("prompt_cache_hit_tokens")
                // Anthropic-shaped usage, as surfaced by some proxies (e.g. OpenRouter).
                ?: usage.optIntOrNull("cache_read_input_tokens")
        val reasoningTokens =
            usage.optJSONObject("completion_tokens_details")?.optIntOrNull("reasoning_tokens")
        return TokenUsage(
            promptTokens = usage.optIntOrNull("prompt_tokens"),
            completionTokens = usage.optIntOrNull("completion_tokens"),
            totalTokens = usage.optIntOrNull("total_tokens"),
            cachedTokens = cachedTokens,
            reasoningTokens = reasoningTokens,
        )
    }

    private fun providerFromHost(host: String): String = when {
        "groq" in host -> "Groq"
        "openrouter" in host -> "OpenRouter"
        "generativelanguage" in host || "google" in host -> "Gemini"
        else -> host
    }

    /** The text of the first `system` message — the shared instruction block. Null if none. */
    private fun firstSystemText(messages: JSONArray?): String? {
        if (messages == null) return null
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role") == "system") return renderMessageBody(msg).ifBlank { null }
        }
        return null
    }

    /**
     * The newest message rendered with its role header — the per-turn input delta.
     * With `parallel_tool_calls=false` each turn appends exactly one new input (a tool
     * result, or the user goal on turn 0), so the last message IS the delta. A leading
     * `system` message (turn 0 sends only system+user) is skipped so the delta is the
     * user goal, not the doctrine.
     */
    private fun lastMessageDelta(messages: JSONArray?): String {
        if (messages == null || messages.length() == 0) return ""
        val last = messages.optJSONObject(messages.length() - 1) ?: return ""
        val role = last.optString("role", "?")
        if (role == "system") return "" // only message is system → no per-turn delta yet
        val body = renderMessageBody(last)
        return "── ${role.uppercase()} ──\n$body".trim()
    }

    /** Render one message's content + any requested tool calls, eliding images. No role header. */
    private fun renderMessageBody(msg: JSONObject): String {
        val sb = StringBuilder()
        when (val content = msg.opt("content")) {
            is String -> sb.append(content)
            is JSONArray -> for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                when (part.optString("type")) {
                    "text" -> sb.append(part.optString("text"))
                    "image_url" -> {
                        val url = part.optJSONObject("image_url")?.optString("url").orEmpty()
                        val kb = (url.length * 3 / 4) / 1024 // rough base64→bytes→KB
                        sb.append("[image ~${kb} KB]")
                    }
                    else -> sb.append("[", part.optString("type", "part"), "]")
                }
                sb.append('\n')
            }
            else -> sb.append(content?.toString().orEmpty())
        }
        msg.optJSONArray("tool_calls")?.let { tc ->
            for (k in 0 until tc.length()) {
                val fn = tc.optJSONObject(k)?.optJSONObject("function")
                if (fn != null) sb.append("\n→ tool_call ").append(fn.optString("name"))
                    .append('(').append(fn.optString("arguments")).append(')')
            }
        }
        return sb.toString().trim()
    }

    /**
     * Thinking text a thinking model returns alongside its answer. Tries multiple
     * field names to maximize compatibility:
     * - `message.reasoning_content` — Gemini 3.x with OpenAI-compat endpoint + `thinking_config.include_thoughts`
     * - `message.reasoning` — OpenAI's o1/o3 models and other reasoning models via OpenAI endpoint
     *
     * Returns null if no reasoning field is present (e.g., non-reasoning models).
     * The encrypted `thought_signature` is NOT this — it is opaque and never shown.
     */
    private fun extractReasoning(resp: JSONObject): String? {
        val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
        // Try reasoning_content first (Gemini), then reasoning (OpenAI), return null if both blank.
        val r = msg.optString("reasoning_content").ifBlank { msg.optString("reasoning") }
        return r.ifBlank { null }
    }

    /** `finish_reason` from the first choice (`stop`/`tool_calls`/`length`/…). */
    private fun extractFinishReason(resp: JSONObject): String? =
        resp.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")?.ifBlank { null }

    /**
     * The assistant's plain prose for this turn, or null when it wrote none.
     *
     * Deliberately NOT derived from [extractAssistant] by string-splitting its "
→ " tool
     * rendering: that separator is a trace-formatting detail, and a status strip that broke
     * silently whenever someone reformatted the trace would be a bad way to find out.
     * Reads `content` from the response directly instead.
     */
    private fun extractAssistant(resp: JSONObject): String {
        val choices = resp.optJSONArray("choices") ?: return resp.toString().take(4_000)
        val sb = StringBuilder()
        for (i in 0 until choices.length()) {
            val msg = choices.optJSONObject(i)?.optJSONObject("message") ?: continue
            msg.optString("content").takeIf { it.isNotBlank() }?.let { sb.append(it) }
            msg.optJSONArray("tool_calls")?.let { tc ->
                for (k in 0 until tc.length()) {
                    val fn = tc.optJSONObject(k)?.optJSONObject("function")
                    if (fn != null) sb.append("\n→ ").append(fn.optString("name"))
                        .append('(').append(fn.optString("arguments")).append(')')
                }
            }
        }
        return sb.toString().trim().ifBlank { "(no content)" }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private const val TAG = "AgentLlmTap"
}

/** An assistant body split into the thought the model inlined and what remains. */
internal data class SplitThought(val reasoning: String?, val body: String)

/**
 * Gemini on the OpenAI-compat endpoint returns its thinking inside `content` as
 * `<thought>…</thought>` rather than in `reasoning_content`, so
 * `AgentLlmTap.extractReasoning` sees nothing and the thought lands in the response instead.
 * A trace read for "why did it do that" then looks empty on exactly the models that do think.
 *
 * Moves the thought to where readers look, and removes it from the body so the same text is not
 * stored twice. An unterminated tag (truncated completion) is left alone — swallowing everything
 * after it would delete the answer.
 */
internal fun splitInlineThought(body: String): SplitThought {
    val match = INLINE_THOUGHT.find(body) ?: return SplitThought(null, body)
    val thought = match.groupValues[1].trim()
    val rest = body.removeRange(match.range).trim()
    return if (thought.isEmpty()) SplitThought(null, rest) else SplitThought(thought, rest)
}

private val INLINE_THOUGHT = Regex("""<thought>([\s\S]*?)</thought>""", RegexOption.IGNORE_CASE)
