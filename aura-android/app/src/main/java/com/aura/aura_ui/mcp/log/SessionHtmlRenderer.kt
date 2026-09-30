package com.aura.aura_ui.mcp.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a [SessionLog] as a self-described, LangSmith-style HTML trace for the
 * in-app WebView. Tool invocations and LLM calls are merged into one chronological
 * timeline. Gesture screenshots are annotated (tap crosshair / swipe arrow) via
 * [SessionScreenshotAnnotator] and referenced by relative path, so the WebView is
 * loaded with `baseUrl = file://<sessionDir>/`.
 *
 * The HTML is a *view* — the canonical record stays `metadata.json`. (Claude Code
 * reads the JSON; humans read this.)
 */
object SessionHtmlRenderer {

    // java.text, not android.text.format: the Android class is a stub under JVM unit tests and
    // returns null, which is why this renderer had no tests at all until now. Same output.
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val clockOnly = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun render(session: SessionLog, sessionDir: File): String {
        // Annotate gesture screenshots up front (idempotent, regenerated each render).
        session.invocations.forEach { SessionScreenshotAnnotator.annotate(sessionDir, it) }

        val blocks = buildTimeline(session, sessionDir)
        val started = Date(session.startedAtMillis)
        val durationMs = (session.endedAtMillis ?: System.currentTimeMillis()) - session.startedAtMillis
        val sourceLabel = when (session.source) {
            AgentRunLogger.SOURCE_AGENT -> "On-device agent"
            LiveConversationLogger.SOURCE_LIVE -> "Live conversation"
            else -> "MCP (external)"
        }
        // A conversation with no tool calls is not an empty session — counting only tools and
        // LLM calls is what made a 26-utterance Live log announce itself as "0 tools · 0 LLM
        // calls". Every plane gets counted by what it actually produces.
        val counts = buildList {
            if (session.utterances.isNotEmpty()) {
                add("${session.utterances.size} utterance${if (session.utterances.size == 1) "" else "s"}")
            }
            if (session.invocations.isNotEmpty() || session.utterances.isEmpty()) {
                add("${session.invocations.size} tools")
            }
            if (session.llmCalls.isNotEmpty() || session.utterances.isEmpty()) {
                add("${session.llmCalls.size} LLM calls")
            }
        }.joinToString(" · ")

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>$CSS</style><script>$JS</script></head><body>
<div class="hdr">
  <div class="title">AURA session log</div>
  <div class="sub">${esc(sourceLabel)}${session.command?.let { " · " + esc(it) } ?: ""}</div>
  <div class="meta">${esc(stamp.format(started))}
    · ${fmtDuration(durationMs)}
    · $counts
    ${session.endReason?.let { "· " + esc(it) } ?: ""}</div>
  ${session.agentLabel?.let { "<div class=\"meta\">${esc(it)}</div>" } ?: ""}
</div>
<div class="timeline">
${blocks.joinToString("\n")}
</div>
</body></html>"""
    }

    /** A timeline entry keyed by timestamp so tools + LLM calls interleave correctly. */
    private fun buildTimeline(session: SessionLog, sessionDir: File): List<String> {
        data class Entry(val ts: Long, val html: String)
        val entries = mutableListOf<Entry>()

        for (inv in session.invocations) {
            entries += Entry(inv.timestampMillis, toolBlock(inv, session, sessionDir))
        }
        for (call in session.llmCalls) {
            entries += Entry(call.timestampMillis, llmBlock(call))
        }
        // Speech carries its own timestamp, so it sorts into the same clock as tools and LLM
        // calls without any special casing — a Live session that hands work to the action
        // plane reads as one story rather than two logs.
        for (utterance in session.utterances) {
            entries += Entry(utterance.atMillis, utteranceBlock(utterance))
        }
        return entries.sortedBy { it.ts }.map { it.html }
    }

    /**
     * One spoken turn — or one recorded absence. Gaps and barge-ins are styled *louder*
     * than ordinary speech on purpose: they are the entries someone opens this trace to
     * find, and rendering them as quiet grey text would rebuild the ambiguity the
     * conversation log was written to remove.
     */
    private fun utteranceBlock(u: Utterance): String {
        val who = when (u.speaker) {
            "user" -> "YOU"
            "aura" -> "AURA"
            else -> "SYSTEM"
        }
        val cls = when {
            u.kind == "gap" -> "gap"
            u.kind == "interrupted" -> "cut"
            u.kind == "lifecycle" -> "sys"
            u.kind == "task" -> "task"
            u.speaker == "user" -> "user"
            else -> "aura"
        }
        val kindPill = if (u.kind == "speech") "" else "<span class=\"pill kind\">${esc(u.kind)}</span>"
        val partial = if (!u.complete && u.kind != "gap") "<span class=\"pill kind\">partial</span>" else ""
        val time = clockOnly.format(Date(u.atMillis))
        return """
<div class="card say ${cls}">
  <div class="row">
    <span class="badge say ${cls}">${esc(who)}</span>
    $kindPill$partial
    <span class="dur">${esc(time)}</span>
  </div>
  <div class="said">${esc(u.text)}</div>
</div>"""
    }

    private fun toolBlock(inv: ToolInvocation, session: SessionLog, sessionDir: File): String {
        val statusCls = if (inv.success) "ok" else "fail"
        val statusTxt = if (inv.success) "OK" else "FAILED"
        val coordPill = if (inv.tapX != null && inv.tapY != null) {
            val end = if (inv.tapX2 != null && inv.tapY2 != null) " → (${inv.tapX2}, ${inv.tapY2})" else ""
            "<span class=\"pill\">🎯 (${inv.tapX}, ${inv.tapY})$end</span>"
        } else ""
        val shot = screenshotHtml(inv, sessionDir)
        val args = inv.argsJson?.takeIf { it.isNotBlank() && it != "{}" }
            ?.let { "<div class=\"kv\"><span class=\"k\">args</span><pre class=\"mono\">${esc(it)}</pre></div>" } ?: ""
        val output = inv.outputSummary.takeIf { it.isNotBlank() }
            ?.let { "<div class=\"kv\"><span class=\"k\">output</span><pre class=\"mono\">${esc(it)}</pre></div>" } ?: ""
        return """
<div class="card tool ${statusCls}">
  <div class="row">
    <span class="badge tool">TOOL</span>
    <span class="name">${esc(inv.toolName)}</span>
    $coordPill
    <span class="status ${statusCls}">$statusTxt</span>
    <span class="dur">${inv.durationMs}ms</span>
  </div>
  $args$output$shot
</div>"""
    }

    private fun screenshotHtml(inv: ToolInvocation, sessionDir: File): String {
        if (!inv.hasScreenshot) return ""
        // Prefer the annotated copy if one exists; the raw shot's extension follows its bytes.
        val src = if (inv.gestureType != null) {
            "annotated/${inv.index}.png"
        } else {
            rawScreenshotRelativePath(sessionDir, inv.index) ?: return ""
        }
        return "<div class=\"shot\"><img src=\"$src\" loading=\"lazy\"></div>"
    }

    private fun llmBlock(call: LlmCall): String {
        val tokens = listOfNotNull(
            call.promptTokens?.let { "prompt $it" },
            call.completionTokens?.let { "completion $it" },
            call.totalTokens?.let { "total $it" },
        ).joinToString(" · ")
        val tokenRow = if (tokens.isNotEmpty()) "<div class=\"tokens\">🔢 $tokens</div>" else ""
        val pid = "p${call.index}_${call.timestampMillis}"
        return """
<div class="card llm">
  <div class="row">
    <span class="badge llm">LLM #${call.index + 1}</span>
    <span class="name">${esc(call.provider)} · ${esc(call.model)}</span>
    <span class="dur">${call.durationMs}ms</span>
  </div>
  <button class="toggle" onclick="t('$pid')">💬 Prompt — ${call.prompt.length} chars (tap to expand)</button>
  <pre class="prompt hidden mono" id="$pid">${esc(call.prompt)}</pre>
  <div class="resp mono">${esc(call.response)}</div>
  $tokenRow
</div>"""
    }

    private fun fmtDuration(ms: Long): String {
        val s = ms / 1000.0
        return if (s < 60) "%.1fs".format(s) else "${(s / 60).toInt()}m ${(s % 60).toInt()}s"
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val JS = """
        function t(id){var e=document.getElementById(id);if(e)e.classList.toggle('hidden');}
    """

    private const val CSS = """
    :root{--bg:#0b0f14;--surface:#11161d;--surface2:#161d26;--border:#212b36;--text:#c9d4e0;
      --muted:#6b7785;--blue:#4493f8;--green:#3fb950;--red:#f85149;--purple:#a371f7;--amber:#d29922;--cyan:#39c5cf;}
    *{box-sizing:border-box;margin:0;padding:0;}
    body{background:var(--bg);color:var(--text);font-family:-apple-system,'Segoe UI',Roboto,sans-serif;
      font-size:13px;line-height:1.5;padding:14px;}
    .hdr{border-bottom:1px solid var(--border);padding-bottom:12px;margin-bottom:14px;}
    .title{font-size:17px;font-weight:700;color:var(--blue);}
    .sub{font-size:13px;color:var(--text);margin-top:2px;}
    .meta{font-size:11px;color:var(--muted);font-family:monospace;margin-top:3px;}
    .timeline{display:flex;flex-direction:column;gap:8px;}
    .card{border:1px solid var(--border);border-radius:8px;background:var(--surface);overflow:hidden;}
    .card .row{display:flex;align-items:center;gap:8px;padding:8px 12px;flex-wrap:wrap;border-bottom:1px solid var(--border);}
    .card.tool{border-left:3px solid var(--green);}
    .card.tool.fail{border-left:3px solid var(--red);}
    .card.llm{border-left:3px solid var(--purple);}
    .card.say.user{border-left:3px solid var(--cyan);}
    .card.say.aura{border-left:3px solid var(--blue);}
    .card.say.sys,.card.say.task{border-left:3px solid var(--muted);}
    .card.say.cut{border-left:3px solid var(--amber);}
    .card.say.gap{border-left:3px solid var(--red);background:#1a1012;}
    .badge{font-size:9px;font-weight:700;letter-spacing:.5px;padding:2px 7px;border-radius:4px;text-transform:uppercase;}
    .badge.say.user{background:#04282b;color:#5fd7dd;border:1px solid #12545a;}
    .badge.say.aura{background:#0d1a2e;color:#79b8ff;border:1px solid #1e3a5a;}
    .badge.say.sys,.badge.say.task{background:#1a1f26;color:var(--muted);border:1px solid var(--border);}
    .badge.say.cut{background:#2b2109;color:#e3b341;border:1px solid #5a4310;}
    .badge.say.gap{background:#2d0d10;color:#f8837c;border:1px solid #5e1b1b;}
    .pill.kind{background:var(--surface2);color:var(--muted);border:1px solid var(--border);
      font-size:9px;font-weight:700;letter-spacing:.5px;text-transform:uppercase;padding:2px 7px;border-radius:4px;}
    .said{padding:8px 12px;font-size:13px;color:var(--text);white-space:pre-wrap;word-break:break-word;}
    .card.say.gap .said{color:#f8837c;font-style:italic;}
    .card.say .row .dur{margin-left:auto;}
    .badge.tool{background:#0d2d17;color:#56d364;border:1px solid #1b5e30;}
    .badge.llm{background:#221a40;color:#c4a7ff;border:1px solid #43308a;}
    .name{font-weight:600;font-size:12px;}
    .pill{background:#0d2d17;color:#56d364;font-family:monospace;font-size:11px;font-weight:700;
      padding:2px 8px;border-radius:4px;border:1px solid #1b5e30;}
    .status{font-weight:700;font-size:11px;margin-left:auto;}
    .status.ok{color:var(--green);} .status.fail{color:var(--red);}
    .dur{color:var(--muted);font-family:monospace;font-size:11px;}
    .status+.dur{margin-left:0;}
    .kv{padding:6px 12px;} .kv .k{display:block;font-size:10px;text-transform:uppercase;color:var(--muted);
      letter-spacing:.5px;margin-bottom:3px;}
    .mono{font-family:'JetBrains Mono',monospace;font-size:11px;white-space:pre-wrap;word-break:break-word;color:#adbac7;}
    pre.mono{background:#06090d;border:1px solid var(--border);border-radius:6px;padding:8px 10px;overflow-x:auto;}
    .shot{padding:10px 12px;} .shot img{max-width:100%;border-radius:8px;border:2px solid #1b5e30;display:block;}
    .toggle{display:block;width:100%;text-align:left;background:#0d1a2e;border:1px solid #1e3a5a;color:var(--blue);
      font-family:monospace;font-size:11px;padding:6px 12px;cursor:pointer;}
    .prompt{margin:0 12px 8px;}
    .resp{margin:8px 12px;background:#06090d;border:1px solid var(--border);border-left:3px solid #43308a;
      border-radius:6px;padding:8px 12px;}
    .tokens{margin:0 12px 10px;color:var(--muted);font-family:monospace;font-size:11px;
      background:var(--surface2);border-radius:4px;padding:4px 8px;display:inline-block;}
    .hidden{display:none;}
    """
}
