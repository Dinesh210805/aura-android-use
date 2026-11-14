package com.aura.aura_ui.agentcore

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.glass

/**
 * Find in chat: the messages that contain the words, newest last, and a bar to step through them.
 * - Contract: no agent logic; the transcript scrolls to and marks the current match.
 */
object ChatFind {
    /** Rows of [items] whose text holds [query], ignoring case, in transcript order. Pure. */
    fun matches(items: List<ItemRow>, query: String): List<Long> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        return items.filter { (it.kind == ItemKind.USER.name || it.kind == ItemKind.ASSISTANT.name) && it.text.contains(q, ignoreCase = true) }
            .map { it.rowid }
    }

    private const val MIN_QUERY = 2
}

/** The find bar: the words, "3 of 7", previous, next and close. Starts at the newest match. */
@Composable
fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    at: Int,
    onStep: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = LocalAura.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).glass(AuraShape.Pill).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (query.isEmpty()) Text("Find in chat", style = AuraType.body, color = aura.secondary)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = AuraType.body.copy(color = aura.label),
                cursorBrush = SolidColor(aura.label),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotBlank()) {
            Text(if (count == 0) "None" else "${at + 1} of $count", style = AuraType.caption, color = aura.secondary)
        }
        FindButton(Icons.Rounded.KeyboardArrowUp, "Earlier match", count > 0) { onStep(-1) }
        FindButton(Icons.Rounded.KeyboardArrowDown, "Later match", count > 0) { onStep(1) }
        FindButton(Icons.Rounded.Close, "Close find", true, onClose)
    }
}

@Composable
private fun FindButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val aura = LocalAura.current
    androidx.compose.foundation.layout.Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) aura.label else aura.tertiary, modifier = Modifier.size(20.dp))
    }
}
