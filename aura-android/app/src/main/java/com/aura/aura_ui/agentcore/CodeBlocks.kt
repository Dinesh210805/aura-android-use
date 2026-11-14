package com.aura.aura_ui.agentcore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.LocalAura

/** A run of a reply: prose, or the inside of a ``` fence. */
internal data class Block(val code: Boolean, val text: String)

/**
 * [text] cut at its ``` fences. A fence still open (a reply still streaming) runs to the end.
 * The fence's language tag is dropped; the code keeps its whitespace exactly. Pure.
 */
internal fun blocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val run = StringBuilder()
    var code = false
    fun flush() {
        val body = run.toString().removeSuffix("\n")
        if (body.isNotBlank()) out += Block(code, body)
        run.clear()
    }
    text.lines().forEach { line ->
        if (line.trimStart().startsWith(FENCE)) {
            flush()
            code = !code
        } else {
            run.append(line).append('\n')
        }
    }
    flush()
    return out
}

private const val FENCE = "```"

/** One code block: monospace, scrolls sideways rather than wrapping, with a copy button that copies it exactly. */
@Composable
internal fun CodeBlock(code: String, style: TextStyle) {
    val aura = LocalAura.current
    val copy = rememberCopy()
    Box(Modifier.fillMaxWidth().clip(AuraShape.Inner).background(aura.well)) {
        Text(
            code,
            Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 44.dp),
            style = style.copy(fontFamily = FontFamily.Monospace, fontSize = style.fontSize * CODE_SCALE),
            color = aura.label,
            softWrap = false,
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(36.dp).clip(CircleShape)
                .clickable(onClickLabel = "Copy code") { copy(code) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code", tint = aura.secondary, modifier = Modifier.size(16.dp)) }
    }
}

private const val CODE_SCALE = 0.88f
