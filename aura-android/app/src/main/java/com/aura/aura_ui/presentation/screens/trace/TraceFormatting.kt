package com.aura.aura_ui.presentation.screens.trace

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.aura.aura_ui.ui.theme.MonoScheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Turning a stored trace payload into something a human can read.
 *
 * Everything here is a pure function on a String, so it is unit-tested rather than
 * screenshot-tested. The hard constraint is that **nothing is ever dropped**: a payload
 * that cannot be parsed is still shown in full, just un-escaped.
 *
 * Three shapes actually reach this code, and the third is the one that breaks naive
 * pretty-printers:
 *
 *  1. **Pure JSON** — a tool's `argsJson`. Parses; gets indented.
 *  2. **Prose + JSON** — `perceive_screen`'s output is a newline-joined summary line
 *     followed by the element JSON. Parsing the whole blob fails; parsing the tail works.
 *  3. **Truncated / not JSON at all** — `ToolInvocation.outputSummary` is documented as a
 *     *preview*, so its JSON may simply stop mid-object. Un-escape and show as text.
 *
 * There is deliberately **no per-tool branch** here. A formatter that special-cases
 * `perceive_screen` becomes a stale reader the moment that payload changes shape — which
 * is exactly how the last perception regression happened.
 */

/**
 * One renderable piece of a payload. A stored payload is rarely one thing: an MCP result is an
 * envelope holding a summary line, the tool's own JSON, and sometimes a screenshot.
 */
sealed interface PayloadBlock {
    /** Human sentences. Rendered in the reading typeface, justified — not as code. */
    data class Prose(val text: String, val isWarning: Boolean) : PayloadBlock

    /** Structured data. Rendered monospace and syntax-coloured. */
    data class Json(val payload: ReadablePayload) : PayloadBlock

    /** A binary blob that must never be inlined (the screenshot is shown as an image below). */
    data class Elided(val label: String) : PayloadBlock
}

/**
 * Split a stored payload into blocks for display.
 *
 * The dominant shape on device is the MCP result envelope — and it is not valid JSON, because
 * each part carries the tool's own JSON as an unescaped string (see
 * [com.aura.aura_ui.agent.mcpbridge.McpEnvelopeText]). Rendering that verbatim is what made the
 * output panel unreadable: protocol scaffolding, escaped punctuation and, on perception steps,
 * thousands of characters of base64 in the middle of the text.
 *
 * Unwrapping happens here as well as at the logger, because sessions recorded before the logger
 * was fixed still hold raw envelopes and must stay readable.
 */
fun buildPayloadBlocks(raw: String): List<PayloadBlock> {
    val envelope = com.aura.aura_ui.agent.mcpbridge.McpEnvelopeText.parse(raw)
    if (envelope != null) {
        return envelope.parts.flatMap { part ->
            if (part.isBinary) {
                listOf(PayloadBlock.Elided("screenshot · ~${part.approxBytes / 1024} KB (shown below)"))
            } else {
                blocksOf(part.text)
            }
        }.ifEmpty { blocksOf(raw) }
    }
    return blocksOf(raw)
}

/** Split one plain payload into at most a prose header and a JSON body. */
private fun blocksOf(raw: String): List<PayloadBlock> {
    if (raw.isBlank()) return emptyList()
    val readable = humanizePayload(raw)
    if (!readable.hasJson) return listOf(proseBlock(readable.text))
    if (readable.jsonStart == 0) return listOf(PayloadBlock.Json(readable))

    val prose = readable.text.substring(0, readable.jsonStart).trim()
    val body = readable.text.substring(readable.jsonStart)
    return listOfNotNull(
        prose.takeIf { it.isNotEmpty() }?.let { proseBlock(it) },
        PayloadBlock.Json(ReadablePayload(body, 0)),
    )
}

private fun proseBlock(text: String) = PayloadBlock.Prose(
    text = text,
    // The perception tools lead with a "⚠ THIS SCREEN IS STILL LOADING" line whose whole job is
    // to be noticed. It is the one prose line in a trace that earns a colour.
    isWarning = text.trimStart().startsWith("⚠"),
)

/** A payload rewritten for reading, plus where its JSON section starts (for highlighting). */
data class ReadablePayload(
    val text: String,
    /** Index into [text] where the JSON section begins, or -1 when there is none. */
    val jsonStart: Int,
) {
    val hasJson: Boolean get() = jsonStart >= 0
}

private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    isLenient = true
    ignoreUnknownKeys = true
}

/**
 * Best-effort human rendering of [raw]. Never returns less information than it was given.
 *
 * Callers must keep [raw] around for copy/export — a reformatted payload is not the payload
 * the model saw, and pasting it into a bug report quietly changes the evidence.
 */
fun humanizePayload(raw: String): ReadablePayload {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ReadablePayload(raw, -1)

    // 1. The whole thing is JSON. Only attempt this when it *looks* structural — lenient
    //    parsing would otherwise turn the bare word `ok` into a quoted `"ok"`.
    if (trimmed.startsWithJsonOpener()) {
        prettyOrNull(trimmed)?.let { return ReadablePayload(it, 0) }
    }

    // 2. Prose header followed by a JSON body (the perceive_screen shape).
    val cut = firstJsonOpener(trimmed)
    if (cut > 0) {
        prettyOrNull(trimmed.substring(cut))?.let { body ->
            val prose = unescape(trimmed.substring(0, cut).trimEnd()) + "\n\n"
            return ReadablePayload(prose + body, prose.length)
        }
    }

    // 3. Truncated JSON, plain text, or a log line. Un-escape so `\n` becomes a line break
    //    instead of two literal characters, and leave the rest exactly as stored.
    return ReadablePayload(unescape(trimmed), -1)
}

private fun String.startsWithJsonOpener(): Boolean = startsWith("{") || startsWith("[")

private fun firstJsonOpener(s: String): Int {
    val brace = s.indexOf('{')
    val bracket = s.indexOf('[')
    return when {
        brace < 0 -> bracket
        bracket < 0 -> brace
        else -> minOf(brace, bracket)
    }
}

private fun prettyOrNull(s: String): String? = runCatching {
    prettyJson.encodeToString(JsonElement.serializer(), prettyJson.parseToJsonElement(s))
}.getOrNull()

/**
 * Resolve the escape sequences that survive into stored payloads (`\n`, `\t`, `\uXXXX`, …).
 *
 * These arrive because a payload was JSON-encoded once on the way in and then stored as a
 * plain string; without this the trace shows `Tapped\nelement 5` on one line.
 */
fun unescape(s: String): String {
    if ('\\' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i == s.lastIndex) {
            out.append(c)
            i++
            continue
        }
        when (val next = s[i + 1]) {
            'n' -> { out.append('\n'); i += 2 }
            't' -> { out.append('\t'); i += 2 }
            'r' -> { out.append('\r'); i += 2 }
            '"' -> { out.append('"'); i += 2 }
            '\\' -> { out.append('\\'); i += 2 }
            '/' -> { out.append('/'); i += 2 }
            'u' -> {
                val hex = s.drop(i + 2).take(4)
                val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                if (code == null) {
                    out.append(c)
                    i++
                } else {
                    out.append(code.toChar())
                    i += 6
                }
            }
            else -> { out.append(c).append(next); i += 2 }
        }
    }
    return out.toString()
}

// ── Syntax colour ───────────────────────────────────────────────────────────

/**
 * The one place colour is spent inside a payload body.
 *
 * Mono's rule stands: **no token ever gets [com.aura.aura_ui.ui.theme.Mono.Blood]** — red in
 * this app means "this failed", and a red string literal would make every args block look
 * like an error. These are muted hues chosen to stay legible on both the light card
 * (`#FFFFFF`) and the dark one (`#1F1C19`).
 */
data class JsonPalette(
    val key: Color,
    val string: Color,
    val number: Color,
    val literal: Color,
) {
    companion object {
        fun of(scheme: MonoScheme): JsonPalette = if (scheme.isDark) {
            JsonPalette(
                key = Color(0xFF9BA8E8),
                string = Color(0xFF8FC7A4),
                number = Color(0xFFE0B36A),
                literal = Color(0xFFC79BE0),
            )
        } else {
            JsonPalette(
                key = Color(0xFF3A4BA0),
                string = Color(0xFF2E6B4F),
                number = Color(0xFF8A5A00),
                literal = Color(0xFF6E3E96),
            )
        }
    }
}

/** Above this, tokenising costs more than the colour is worth — render plain instead. */
private const val HIGHLIGHT_CHAR_LIMIT = 40_000

private val JSON_TOKEN = Regex(
    // key ("…" immediately before a colon) | string | number | literal
    """"(?:\\.|[^"\\])*"\s*:|"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|\btrue\b|\bfalse\b|\bnull\b""",
)

/**
 * Colour the JSON section of [payload] starting at [ReadablePayload.jsonStart]; the prose
 * prefix stays in the body text colour so the two halves stay visually distinct.
 */
fun highlightJson(payload: ReadablePayload, palette: JsonPalette, base: Color): AnnotatedString {
    val text = payload.text
    if (!payload.hasJson || text.length > HIGHLIGHT_CHAR_LIMIT) return AnnotatedString(text)

    return buildAnnotatedString {
        withStyle(SpanStyle(color = base)) { append(text.substring(0, payload.jsonStart)) }
        var cursor = payload.jsonStart
        for (m in JSON_TOKEN.findAll(text, payload.jsonStart)) {
            if (m.range.first > cursor) {
                withStyle(SpanStyle(color = base)) { append(text.substring(cursor, m.range.first)) }
            }
            val token = m.value
            val color = when {
                token.endsWith(":") || token.trimEnd().endsWith(":") -> palette.key
                token.startsWith("\"") -> palette.string
                token == "true" || token == "false" || token == "null" -> palette.literal
                else -> palette.number
            }
            withStyle(SpanStyle(color = color)) { append(token) }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) {
            withStyle(SpanStyle(color = base)) { append(text.substring(cursor)) }
        }
    }
}
