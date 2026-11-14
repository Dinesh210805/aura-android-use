package com.aura.aura_ui.agentcore

import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.auraCard
import com.aura.aura_ui.ui.theme.glass
import com.aura.aura_ui.ui.theme.rememberBitmap
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Size
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import com.aura.aura_ui.ui.theme.auraCardStrong
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The cards the chat draws: files, contacts, results, the plan, and the two cards that wait for
 * the user (a question, and an Allow or Deny in front of a step that commits them).
 *
 * - Contract: a card only starts an Android intent or calls the lambda it was given. It never
 *   talks to the agent itself.
 * - Fails: an intent nothing on the phone can handle is dropped silently; the card stays.
 * - Change together: a new [ChatCard] subclass needs a branch in [CardView].
 */
@Composable
fun CardView(card: ChatCard, onPick: (String) -> Unit) {
    when (card) {
        is ChatCard.Files -> FilesCard(card)
        is ChatCard.Contacts -> ContactsCard(card, onPick)
        is ChatCard.Result -> ResultCard(card)
        is ChatCard.Sources -> SourcesRow(card)
    }
}

/** Source chips under an answer: the site's name, tap to open the page. */
@Composable
private fun SourcesRow(card: ChatCard.Sources) {
    val aura = LocalAura.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier.fillMaxWidth().then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        card.links.forEachIndexed { i, link ->
            val host = runCatching { android.net.Uri.parse(link.url).host?.removePrefix("www.") }.getOrNull() ?: link.url
            Text(
                "${i + 1} · $host",
                Modifier.clip(AuraShape.Pill).background(aura.well)
                    .clickable(onClickLabel = "Open ${link.title}") {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link.url))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = AuraType.caption,
                color = aura.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CardFrame(title: String, trailing: String? = null, content: @Composable () -> Unit) {
    val aura = LocalAura.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).auraCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), Modifier.weight(1f), style = AuraType.caption, color = aura.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.let { Text(it, style = AuraType.caption, color = aura.tertiary) }
        }
        content()
    }
}

// ── Files ────────────────────────────────────────────────────────────────────

@Composable
private fun FilesCard(card: ChatCard.Files) {
    val (visual, other) = card.files.partition { it.isVisual }
    CardFrame("Files", "${card.files.size} found") {
        visual.chunked(GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { Thumbnail(it, Modifier.weight(1f)) }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        other.forEach { FileRow(it) }
        Text("Tap to open, hold to share", style = AuraType.footnote, color = LocalAura.current.tertiary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumbnail(file: ChatCard.FileItem, modifier: Modifier) {
    val aura = LocalAura.current
    val context = LocalContext.current
    val picture by rememberBitmap(file.uri) { thumbnail(context, file.uri) }
    Box(
        modifier.aspectRatio(1f).clip(AuraShape.Inner).background(aura.well)
            .combinedClickable(onClickLabel = "Open ${file.name}", onLongClickLabel = "Share", onLongClick = { share(context, file) }) { open(context, file) },
        contentAlignment = Alignment.Center,
    ) {
        picture?.let { Image(it, contentDescription = file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (file.mimeType.startsWith("video/")) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: ChatCard.FileItem) {
    val aura = LocalAura.current
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(AuraShape.Inner)
            .combinedClickable(onClickLabel = "Open ${file.name}", onLongClickLabel = "Share", onLongClick = { share(context, file) }) { open(context, file) }
            .heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(if (file.mimeType.startsWith("audio/")) Icons.Outlined.MusicNote else Icons.Outlined.Description, aura.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = AuraType.callout, color = aura.label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                listOfNotNull(
                    file.sizeBytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                    file.modifiedMs.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it).toString() },
                ).joinToString(" · "),
                style = AuraType.footnote,
                color = aura.secondary,
            )
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector, tint: Color) {
    Box(Modifier.size(40.dp).clip(AuraShape.Inner).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

private fun thumbnail(context: Context, uri: String): Bitmap? {
    val parsed = Uri.parse(uri)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { return context.contentResolver.loadThumbnail(parsed, Size(THUMB_PX, THUMB_PX), null) }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = (maxOf(bounds.outWidth, bounds.outHeight) / THUMB_PX).coerceAtLeast(1)
    return context.contentResolver.openInputStream(parsed)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

private fun open(context: Context, file: ChatCard.FileItem) = start(
    context,
    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.uri), file.mimeType.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
)

private fun share(context: Context, file: ChatCard.FileItem) = start(
    context,
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType(file.mimeType.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, Uri.parse(file.uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        file.name,
    ),
)

private fun start(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ── Contacts ─────────────────────────────────────────────────────────────────

@Composable
private fun ContactsCard(card: ChatCard.Contacts, onPick: (String) -> Unit) {
    val aura = LocalAura.current
    CardFrame(if (card.choose) "Which one?" else "Contact") {
        card.people.forEachIndexed { index, person ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(aura.hairline))
            PersonRow(person, onPick.takeIf { card.choose })
        }
    }
}

@Composable
private fun PersonRow(person: ChatCard.Person, onPick: ((String) -> Unit)?) {
    val aura = LocalAura.current
    val context = LocalContext.current
    val photo by rememberBitmap(person.contactId) { contactPhoto(context, person.contactId) }
    Row(
        Modifier.fillMaxWidth().clip(AuraShape.Inner)
            .then(if (onPick != null) Modifier.clickable(onClickLabel = "Choose ${person.name}") { onPick(person.name) } else Modifier)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(aura.accentSoft), contentAlignment = Alignment.Center) {
            photo?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Text(person.name.take(1).uppercase(), style = AuraType.headline, color = aura.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.name, style = AuraType.headline, color = aura.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(person.number, style = AuraType.footnote, color = aura.secondary)
        }
        RoundAction(Icons.Outlined.Call, "Call ${person.name}") { start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${person.number}"))) }
        RoundAction(Icons.Outlined.Sms, "Message ${person.name}") { start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${person.number}"))) }
        if (remember { installed(context, WHATSAPP) }) {
            RoundAction(Icons.Outlined.ChatBubbleOutline, "WhatsApp ${person.name}") {
                val digits = person.number.filter { it.isDigit() }
                start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage(WHATSAPP))
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val aura = LocalAura.current
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(aura.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = aura.accent, modifier = Modifier.size(18.dp))
        }
    }
}

private fun contactPhoto(context: Context, contactId: Long): Bitmap? {
    if (contactId <= 0) return null
    val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
    return ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, uri)?.use(BitmapFactory::decodeStream)
}

private fun installed(context: Context, packageName: String): Boolean =
    runCatching { context.packageManager.getApplicationInfo(packageName, 0).enabled }.getOrDefault(false)

// ── Result ───────────────────────────────────────────────────────────────────

@Composable
private fun ResultCard(card: ChatCard.Result) {
    val aura = LocalAura.current
    CardFrame(card.source.ifBlank { "Result" }) {
        Text(card.title, style = AuraType.headline, color = aura.label)
        card.lines.forEach { line -> RichText(line, AuraType.callout, aura.label) }
    }
}

// ── Plan ─────────────────────────────────────────────────────────────────────

@Composable
fun PlanCard(plan: AgentEvent.PlanUpdated) {
    val aura = LocalAura.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).auraCard().animateContentSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("PLAN · ${plan.done} OF ${plan.steps.size}", style = AuraType.caption, color = aura.secondary)
        plan.steps.forEachIndexed { index, step ->
            val done = index < plan.done
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(if (done) aura.success else aura.well),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(Icons.Rounded.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(step, style = AuraType.callout, color = if (done) aura.secondary else aura.label)
            }
        }
    }
}

// ── Cards that wait for the user ─────────────────────────────────────────────

/**
 * The card for a pending [UserBroker.Pending].
 * @param onEdit called for "Edit" on a confirm card, after the step has been declined.
 */
/**
 * A question from AURA that holds the run until it is answered: a go-ahead before something
 * that can't be taken back, a choice, or a typed answer.
 * - Contract: exactly one of [onAnswer] or [onEdit] fires per tap. Solid, not glass, so it reads
 *   over any sky; the go-ahead is the one filled button so it can't be mistaken for the others.
 */
@Composable
fun PromptCard(prompt: UserBroker.Pending, onAnswer: (String, String?) -> Unit, onEdit: () -> Unit) {
    val aura = LocalAura.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.runtime.LaunchedEffect(prompt.id) { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).auraCardStrong().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (prompt.isConfirm) aura.warning else aura.label))
            Text(
                if (prompt.isConfirm) "Needs your OK" else "AURA is asking",
                Modifier.padding(start = 8.dp).weight(1f),
                style = AuraType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = aura.secondary,
            )
            Text("Waiting for you", style = AuraType.caption, color = aura.tertiary)
        }
        // A long draft scrolls instead of pushing the buttons off screen.
        Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            RichText(prompt.question, AuraType.body, aura.label)
        }
        when {
            prompt.isConfirm -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Allow", filled = true, modifier = Modifier.fillMaxWidth()) { onAnswer(prompt.id, UserBroker.ALLOW) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Change it", modifier = Modifier.weight(1f)) {
                        onAnswer(prompt.id, UserBroker.DENY)
                        onEdit()
                    }
                    PillButton("Don't allow", modifier = Modifier.weight(1f), tint = aura.danger) { onAnswer(prompt.id, UserBroker.DENY) }
                }
            }
            prompt.options.isNotEmpty() -> Column(Modifier.clip(AuraShape.Inner).background(aura.well)) {
                prompt.options.forEachIndexed { index, option ->
                    if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(aura.hairline))
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onAnswer(prompt.id, option) }.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option, Modifier.weight(1f), style = AuraType.body, color = aura.label)
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = aura.tertiary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            else -> AnswerField { onAnswer(prompt.id, it) }
        }
    }
}

@Composable
private fun AnswerField(onSend: (String?) -> Unit) {
    val aura = LocalAura.current
    var typed by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().clip(AuraShape.Pill).background(aura.well).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (typed.isEmpty()) Text("Your answer", style = AuraType.body, color = aura.tertiary)
            BasicTextField(typed, { typed = it }, textStyle = AuraType.body.copy(color = aura.label), cursorBrush = SolidColor(aura.label), singleLine = true)
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (typed.isBlank()) aura.hairline else aura.accent)
                .clickable(enabled = typed.isNotBlank(), onClickLabel = "Send answer", role = Role.Button) { onSend(typed) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = if (typed.isBlank()) aura.tertiary else aura.onAccent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, filled: Boolean = false, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    val aura = LocalAura.current
    Box(
        modifier.heightIn(min = 44.dp).clip(AuraShape.Pill).background(if (filled) aura.accent else aura.well)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AuraType.headline, color = if (filled) aura.onAccent else tint ?: aura.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── What AURA did, folded ────────────────────────────────────────────────────

/** A run of tool rows from the transcript as one line that opens into the list. */
@Composable
fun StepsFold(steps: List<ItemRow>) {
    val aura = LocalAura.current
    var open by remember(steps.first().rowid) { mutableStateOf(false) }
    val failed = steps.count { it.ok == false }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize()
            .then(if (open) Modifier.clip(AuraShape.Inner).background(aura.surface).padding(bottom = 10.dp) else Modifier),
    ) {
        Row(
            Modifier.clip(AuraShape.Pill).clickable(onClickLabel = if (open) "Hide steps" else "Show steps") { open = !open }
                .background(aura.surface).heightIn(min = 32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (failed > 0) aura.warning else aura.success))
            Spacer(Modifier.width(8.dp))
            val seconds = (steps.last().atMs - steps.first().atMs) / 1_000
            val worked = if (seconds > 0) "Worked for ${if (seconds < 60) "$seconds s" else "${seconds / 60} min"} · " else ""
            val summary = worked + "${steps.size} ${if (steps.size == 1) "step" else "steps"}" + if (failed > 0) " · $failed did not work" else ""
            Text(summary, style = AuraType.caption, color = aura.secondary)
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = aura.tertiary,
                modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f),
            )
        }
        if (open) {
            steps.forEach { step ->
                Text(
                    StepPhrase.of(step.tool.orEmpty(), "{}", null),
                    Modifier.padding(start = 14.dp, top = 6.dp),
                    style = AuraType.footnote,
                    color = if (step.ok == false) aura.danger else aura.secondary,
                )
            }
        }
    }
}

private const val GRID_COLUMNS = 3
private const val THUMB_PX = 320
private const val WHATSAPP = "com.whatsapp"
