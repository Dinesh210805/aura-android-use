package com.aura.aura_ui.presentation.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.aura.aura_ui.services.AgentStatusRegistry
import com.aura.aura_ui.utils.AgentLogger

/**
 * What the status strip should be doing, given every input that can influence it.
 *
 * Pure and separate for the same reason `PausePillBehavior.controlsFor` is: the inputs arrive
 * from four unrelated places — the run flag, the model's narration, the transcript pacer and the
 * screenshot gate — and the bug this replaces was each of them acting on its own view of the
 * answer. A decision with no `WindowManager` in it is one a test can actually pin.
 */
internal object StatusStripVisibility {

    enum class Action {
        /** On the glass now. */
        SHOW,

        /** Off the glass now, no grace — a capture is in flight, or the strip is not allowed. */
        HIDE_NOW,

        /**
         * Off the glass, but not yet. Emptiness has to PERSIST to count, because the ordinary
         * gap between two spoken sentences is momentarily indistinguishable from the end of a
         * conversation, and treating the two alike is what made the caption blink.
         */
        HIDE_AFTER_LINGER,
    }

    fun decide(enabled: Boolean, hasContent: Boolean, hiddenForCapture: Boolean): Action = when {
        // Order matters: a capture outranks content, and being disallowed outranks both.
        !enabled -> Action.HIDE_NOW
        hiddenForCapture -> Action.HIDE_NOW
        hasContent -> Action.SHOW
        else -> Action.HIDE_AFTER_LINGER
    }
}

/**
 * The status strip: what AURA is doing, and the last thing it said, on screen under the
 * camera notch for the whole run.
 *
 * ### Why this exists (2026-09-07)
 *
 * Progress lived only in the notification — and specifically in the *promoted* Live Alert
 * chip, which `AuraOverlayService.promoteIfSupported` gates behind `SDK_INT >= 36`. Below
 * Android 16 there was nothing on screen at all while the agent drove the phone: no
 * progress, no cancel, nothing but an ordinary notification behind a shade the user cannot
 * pull down without covering the app being automated.
 *
 * So this ships on **every** device rather than only the old ones. On Android 16 it sits
 * below the chip and says more than fifteen characters can; below it, it is the whole
 * story. One code path, no per-version behaviour to reason about.
 *
 * ### Three decisions that are not styling
 *
 * **`FLAG_NOT_TOUCHABLE` is mandatory.** This strip spans the top of the screen for the
 * entire run. Without the flag it would swallow every `dispatchGesture` tap aimed at
 * whatever is underneath — hand-building the "gesture dispatches at the right coordinates
 * and nothing happens" class of bug.
 *
 * **No `FLAG_LAYOUT_NO_LIMITS`.** That flag lets a window extend into the cutout area, so
 * with it `y = 0` puts this strip *behind* the camera rather than below it. Without it the
 * window is laid out inside the safe area and the small [TOP_MARGIN_DP] is all the nudge
 * that is needed.
 *
 * **It shows both voices, labelled.** The first cut showed only AURA's speech, on the
 * reasoning that echoing the user back makes it a transcript. That was wrong for the case
 * that matters: when you barge in mid-run you need to see the interruption register, and a
 * strip that sits silent while you talk reads as broken at exactly the wrong moment. What
 * keeps it from being the transcript sheet it replaced is that it holds the LAST utterance
 * only — no history, no scrollback, nothing to open.
 *
 * Plain Android views rather than Compose, for the same reason as [PausePillOverlay]:
 * added straight to the [WindowManager] from a service, with no Activity and so no
 * lifecycle owner for a `ComposeView` to attach to.
 */
class AgentStatusOverlay(private val context: Context) {

    /** The window manager the strip was added through, so [detach] removes it from the same one. */
    private var windowManager: WindowManager? = null

    /** Whether the attached window is a trusted accessibility overlay; picks the plate's alpha. */
    private var trusted: Boolean = false

    private var root: LinearLayout? = null
    private var statusLine: TextView? = null
    private var statusRow: LinearLayout? = null
    private var headlineLine: TextView? = null
    private var beatIcon: BeatIconView? = null
    private var beat: AgentStatusRegistry.Beat? = null
    private var spokenLine: TextView? = null

    /** Fades each newly revealed word up instead of snapping it on — see [renderSpeech]. */
    private var wordFade: android.animation.ValueAnimator? = null

    /**
     * The colours, read from the user's light or dark mode each time the window is built: the
     * app's glass and ink, never the sky. A theme change applies from the next attach.
     */
    private var ink: Int = INK
    private var inkMuted: Int = INK_TERTIARY
    private var plate: GradientDrawable? = null

    /** Screen stays on while the strip is up — see [attach]. */
    private val keepAwake = com.aura.aura_ui.agent.RunKeepAwake(context)

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()

    private fun Float.dp(): Float = this * context.resources.displayMetrics.density

    /** Latest content, kept so [sync] can decide whether there is anything worth showing. */
    private var status: String = ""
    private var speech: AgentStatusRegistry.Utterance? = null

    /** Whether the strip is ALLOWED on screen — a run is going, or the chat overlay is open. */
    private var enabled: Boolean = false

    /**
     * True while a run has the wheel. The spoken line is withheld then, so the strip shows only
     * the work being done; it comes back for conversation and for the reply after the run.
     */
    private var runActive: Boolean = false

    /** [speech] as the strip may draw it right now. */
    private val visibleSpeech: AgentStatusRegistry.Utterance?
        get() = speech.takeUnless { runActive }

    /**
     * Set while the agent screenshots the screen — the strip must not be in the picture.
     *
     * Not `@Volatile`, and that is checked rather than assumed: `AuraAccessibilityService`
     * calls the gate inside `withContext(Dispatchers.Main)` on both the hide and the restore,
     * so every write and every read of this happens on the main looper. If a capture path is
     * ever added that does not, this is the field that needs the annotation — a stale read
     * here is the strip appearing in AURA's own screenshot.
     */
    private var hiddenForCapture: Boolean = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Re-checked rather than trusted: content may have arrived while this was in the queue. */
    private val hideRunnable = Runnable { if (!hasContent()) showOrHide(false) }

    /** When the window went up, so the hide log can say how long the user had to read it. */
    private var shownAtMs: Long = 0L

    /** Last text each row genuinely rendered, so the hide log can name what vanished. */
    private var lastLoggedStatus: String = ""
    private var lastLoggedSpeech: String = ""

    /**
     * Allow or forbid the strip. Does not by itself put it on screen.
     *
     * Being *allowed* and having something *to say* are different questions. Answering both
     * with one `if` produced an empty black bar the moment the chat overlay opened, before a
     * word had been said — and then, once that was fixed by detaching on empty content, a
     * caption that blinked out between every two sentences.
     *
     * Both come from the same conflation, so they are separated rather than traded: this flag
     * decides whether the WINDOW exists, and [StatusStripVisibility] decides whether anything
     * is drawn in it. Enabling with nothing to say attaches a window that stays invisible until
     * there is.
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        sync()
    }

    /** What AURA is doing right now, or blank for nothing. */
    fun setStatus(text: String) {
        status = text
        sync()
    }

    /** A narrated line: its text, the icon for its kind, and the plan headline above it. */
    fun setBeat(beat: AgentStatusRegistry.Beat?) {
        this.beat = beat
        status = beat?.text.orEmpty()
        sync()
    }

    /** The last thing said out loud, labelled with who said it. Null for nothing. */
    fun setSpeech(speech: AgentStatusRegistry.Utterance?) {
        this.speech = speech
        sync()
    }

    /** A local run started or ended; hides or restores the spoken line (see [runActive]). */
    fun setRunActive(active: Boolean) {
        if (runActive == active) return
        runActive = active
        sync()
    }

    /**
     * The window lives as long as the strip is ALLOWED; only its visibility follows content.
     *
     * Those were one question, and that was the bug. Tearing the window down whenever the
     * content went momentarily empty meant every gap between two utterances did a full
     * `removeView` + `addView`: logcat from a real session has `strip HIDDEN` and `strip
     * SHOWN` **26 ms apart**, which on the glass is a caption that blinks out mid-conversation.
     *
     * Worse, it made the empty moment structural. Every writer into `AgentStatusRegistry` —
     * the Live transcript pacer, `AuraTTSManager`, `VoiceCaptureController`,
     * `clearSpeech()` — had to remember never to publish a blank or lose the whole strip.
     * `CompanionLiveController.startTranscriptPacer` carries exactly that guard, in its own
     * words: *"a single empty tick made the caption vanish mid-sentence"*. One guard, in one
     * of the four callers.
     *
     * So the decision moves here, where all four route through: attach on [enabled], and let
     * [applyVisibility] derive what is on the glass from every input at once. A blank publish
     * now costs the caption for a moment, never the window.
     */
    private fun sync() {
        if (enabled) attach() else detach()
        render()
        applyVisibility()
    }

    /**
     * [status] as the strip may draw it right now. During a run only [AgentStatusRegistry.THINKING]
     * is shown: while a task acts on the phone the screen belongs to the task, and the Pause pill
     * is the only AURA surface on it.
     */
    private val visibleStatus: String
        get() = status.takeUnless { runActive && it != AgentStatusRegistry.THINKING }.orEmpty()

    private fun hasContent(): Boolean = visibleStatus.isNotBlank() || visibleSpeech != null

    /**
     * The only place `root.visibility` is written.
     *
     * Two callers used to write it independently — [setHiddenForCapture] and the attach path —
     * with no shared value between them, so a re-attach *during* a capture came up VISIBLE and
     * landed in the agent's own screenshot, and a `restoreAfterCapture` that never arrived left
     * the strip invisible for the rest of the run. Deriving it from all three inputs makes both
     * unrepresentable.
     *
     * Content arriving shows the strip at once; content going away gets [LINGER_MS] to come
     * back first. That grace is what the gap between two spoken sentences actually is, and
     * without it the blink survives keeping the window attached. A capture never waits: a
     * screenshot catching the strip is the failure `OverlayCaptureGate` exists to prevent.
     */
    private fun applyVisibility() {
        handler.removeCallbacks(hideRunnable)
        when (StatusStripVisibility.decide(enabled, hasContent(), hiddenForCapture)) {
            StatusStripVisibility.Action.SHOW -> showOrHide(true)
            StatusStripVisibility.Action.HIDE_NOW -> showOrHide(false)
            StatusStripVisibility.Action.HIDE_AFTER_LINGER ->
                handler.postDelayed(hideRunnable, LINGER_MS)
        }
    }

    /**
     * `INVISIBLE`, never `GONE`: with `WRAP_CONTENT` height, GONE collapses the window to
     * nothing and showing it again costs a layout pass — a flash at precisely the transition
     * this whole change exists to remove one from.
     *
     * SHOWN/HIDDEN are logged here rather than at attach/detach because this is now where the
     * user-visible event happens. HIDDEN carries what was last actually on the glass and for
     * how long: a caption that vanishes early is invisible in a code read — it looks like an
     * ordinary clear — and the elapsed time is the only thing that separates the two.
     */
    private fun showOrHide(visible: Boolean) {
        val view = root ?: return
        val want = if (visible) View.VISIBLE else View.INVISIBLE
        if (view.visibility == want) return
        view.visibility = want
        if (visible) {
            shownAtMs = System.currentTimeMillis()
            AgentLogger.UI.i("strip SHOWN", mapOf("enabled" to enabled))
        } else {
            AgentLogger.UI.i(
                "strip HIDDEN",
                mapOf(
                    "visible_ms" to (System.currentTimeMillis() - shownAtMs),
                    "last_status" to lastLoggedStatus,
                    "last_speech" to lastLoggedSpeech,
                    "for_capture" to hiddenForCapture,
                ),
            )
        }
    }

    private fun attach() {
        if (root != null) return
        // Hosted by the accessibility service when it is bound: that makes the window a
        // trusted overlay, which is what lets the plate be opaque (see [stripBackground]).
        val a11y = com.aura.aura_ui.accessibility.AuraAccessibilityService.instance
        trusted = a11y != null
        if (!trusted && !Settings.canDrawOverlays(context)) {
            Log.e(TAG, "Cannot show status strip — overlay permission missing")
            return
        }
        val wm = (a11y ?: context).getSystemService(WindowManager::class.java) ?: return

        // Built already hidden. `addView` paints before the first [applyVisibility] runs and
        // the plate has a background, so attaching VISIBLE flashes an empty black bar for a
        // frame at the start of every run.
        val view = buildView().apply { visibility = View.INVISIBLE }
        runCatching { wm.addView(view, layoutParams()) }
            .onFailure {
                Log.e(TAG, "Status strip rejected by WindowManager: ${it.message}")
                return
            }
        windowManager = wm
        root = view
        // The strip is up for exactly as long as a run is: attach/detach bracket the whole
        // run, so this is the cheapest correct place to hold the screen. Without it a long
        // task hits the display timeout and both dispatchGesture and screenshot capture start
        // failing on a non-interactive screen. Idempotent + backstopped inside RunKeepAwake.
        keepAwake.acquire()
        // The structural claim of this class — one window for the whole run, not one per
        // utterance — is only checkable if the attach is logged separately from the show.
        // Grep a session: ATTACHED should appear once per run, SHOWN once per caption.
        AgentLogger.UI.i("strip ATTACHED", mapOf("enabled" to enabled))
    }

    private fun detach() {
        keepAwake.release()
        wordFade?.cancel()
        wordFade = null
        handler.removeCallbacks(hideRunnable)
        root?.let {
            showOrHide(false)
            runCatching { windowManager?.removeView(it) }
        }
        windowManager = null
        AgentLogger.UI.i("strip DETACHED", emptyMap())
        root = null
        plate = null
        statusLine = null
        statusRow = null
        headlineLine = null
        beatIcon = null
        spokenLine = null
        shownAtMs = 0L
        lastLoggedStatus = ""
        lastLoggedSpeech = ""
        // A capture in flight when the run ends has no strip left to restore, and a flag left
        // set here would keep the NEXT run's strip invisible from its first frame.
        hiddenForCapture = false
    }

    /**
     * Log what a row is ACTUALLY showing, once the view has measured itself.
     *
     * Not the string handed in — the string on the glass. `maxLines` clips silently, so a
     * caption can be three lines long in the code and two lines long to the user, and a log
     * of the input would quietly disagree with what was on screen at exactly the moment
     * someone is trying to work out what went wrong.
     *
     * Read after layout via [View.post], because a `TextView`'s `layout` is null until it
     * has been measured — reading it inline reports the PREVIOUS text, or nothing at all.
     *
     * [expected] is the text this call was made FOR. The speech row changes ~12×/s, so by the
     * time the post runs the view can already be showing the next caption — and logging that
     * under this render's name is a log that describes a frame nobody saw. (It did: it is what
     * sent the investigation of the frozen caption at the strip instead of at the pacer.) A row
     * that moves faster than it can be measured now logs nothing, which is the honest answer.
     */
    private fun logRendered(view: TextView, row: String, expected: String, onLogged: (String) -> Unit) {
        view.post {
            if (view.text.toString() != expected) return@post
            val shownText = view.layout?.let { layout ->
                val lines = minOf(layout.lineCount, view.maxLines)
                if (lines <= 0) "" else view.text.substring(0, layout.getLineEnd(lines - 1)).trim()
            } ?: view.text.toString()

            val clipped = shownText.length < view.text.length
            onLogged(shownText)
            AgentLogger.UI.i(
                "strip TEXT",
                mapOf(
                    "row" to row,
                    "shown" to shownText,
                    "clipped" to clipped,
                    "chars" to shownText.length,
                ),
            )
        }
    }

    /** Push the current content into the views, when they exist. */
    private fun render() {
        // Nothing to show: leave the rows holding their last words. The strip is about to go
        // dark as a whole (see [applyVisibility]); blanking the rows first would replace those
        // words with an empty plate for the length of the linger, which is the same flicker by
        // another route.
        if (!hasContent()) return
        beatIcon?.kind = beat?.kind ?: AgentStatusRegistry.Kind.WORK
        headlineLine?.let { view ->
            val headline = beat?.headline?.takeIf { status.isNotBlank() }
            view.text = headline.orEmpty()
            view.visibility = if (headline.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        statusRow?.visibility = if (status.isBlank()) View.GONE else View.VISIBLE
        statusLine?.let { view ->
            val text = trimToFit(status, STATUS_MAX_LINES)
            val changed = text != view.text.toString()
            view.text = text
            // Only on a real change: the speech pacer calls render() ~12×/s, and logging
            // every tick would bury the transitions this exists to make visible.
            if (changed && text.isNotBlank()) logRendered(view, "status", text) { lastLoggedStatus = it }
        }
        shapeFor(card = visibleSpeech != null)
        val view = spokenLine ?: return
        val said = visibleSpeech
        if (said == null) {
            if (view.visibility == View.VISIBLE) {
                AgentLogger.UI.i("strip SPEECH CLEARED", mapOf("was" to lastLoggedSpeech))
                lastLoggedSpeech = ""
            }
            view.visibility = View.GONE
            return
        }
        val who = when (said.speaker) {
            AgentStatusRegistry.Speaker.AURA -> "AURA"
            AgentStatusRegistry.Speaker.USER -> "You"
        }
        // NOT tail-trimmed. The caller hands over one sentence at a time, paced against the
        // audio, so there is nothing to slide a window over — and the window was the bug:
        // three lines would fill, then the view appeared to restart from the top with the
        // next three while the voice was still in the first.
        val full = "$who  ${said.text}"
        val previous = view.text.toString()
        view.visibility = View.VISIBLE
        if (full == previous) return
        renderSpeech(view, full, labelEnd = who.length, previous = previous)
        logRendered(view, "speech", full) { lastLoggedSpeech = it }
    }

    /**
     * Put the caption up with the change animated rather than snapped.
     *
     * Two different changes arrive here and they mean opposite things, so they animate
     * differently:
     *
     *  - **A word was appended** (the common case — the pacer reveals one word at a time).
     *    Only the new word animates: it fades up from transparent to full ink over
     *    [WORD_FADE_MS] while everything before it stays put. Nothing moves, nothing
     *    re-renders, so the eye tracks the sentence instead of re-reading it.
     *  - **The text was replaced** — a new sentence, or the speaker changed from AURA to
     *    You. There is no continuity to preserve, so the whole row cross-fades. Without
     *    this the swap reads as a glitch, because a caption that changes wholesale in one
     *    frame is indistinguishable from one that broke.
     *
     * The fade is done with a mutable span rather than by rebuilding the `Spannable` each
     * frame: only the paint colour changes, so the text never re-measures or re-wraps, and
     * an `invalidate()` is the entire per-frame cost.
     */
    private fun renderSpeech(view: TextView, full: String, labelEnd: Int, previous: String) {
        wordFade?.cancel()
        wordFade = null

        // The speaker is dimmer than the words: it is a label, not the content.
        val text = android.text.SpannableStringBuilder(full).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(inkMuted),
                0,
                labelEnd,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        // An append is the only case with continuity worth preserving; anything else is a
        // replacement, including the first render (`previous` empty) and a speaker change.
        val appendedFrom = previous.length
            .takeIf { previous.isNotEmpty() && full.length > it && full.startsWith(previous) }

        if (appendedFrom == null) {
            view.text = text
            view.alpha = 0f
            view.animate().cancel()
            view.animate().alpha(1f).setDuration(SENTENCE_FADE_MS).start()
            return
        }

        view.animate().cancel()
        view.alpha = 1f
        val fade = FadeInSpan(ink)
        text.setSpan(fade, appendedFrom, full.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = text
        wordFade = android.animation.ValueAnimator.ofInt(0, 0xFF).apply {
            duration = WORD_FADE_MS
            addUpdateListener {
                fade.alpha = it.animatedValue as Int
                view.invalidate()
            }
            start()
        }
    }

    /**
     * A span whose alpha can be animated in place.
     *
     * `ForegroundColorSpan` takes its colour at construction, so animating with one means a
     * new `Spannable` every frame — which re-measures and re-wraps a multi-line caption 60
     * times a second for a colour change. This mutates a field and repaints.
     */
    private class FadeInSpan(private val ink: Int) : android.text.style.CharacterStyle() {
        var alpha: Int = 0

        override fun updateDrawState(tp: android.text.TextPaint) {
            tp.color = (alpha shl 24) or (ink and 0x00FFFFFF)
        }
    }

    /**
     * Two shapes for one window. With only a status ("Thinking…") it is a small centered pill;
     * with a caption it is a rounded card that hugs the sentence, up to the screen's width.
     * The pill's radius is half its height, so it is set once the height is known.
     */
    private fun shapeFor(card: Boolean) {
        val view = root ?: return
        val (side, vert) = if (card) SIDE_PAD_DP to VERT_PAD_DP else PILL_SIDE_PAD_DP to PILL_VERT_PAD_DP
        if (view.paddingLeft != side.dp() || view.paddingTop != vert.dp()) {
            view.setPadding(side.dp(), vert.dp(), side.dp(), vert.dp())
        }
        view.tag = card
        plate?.cornerRadius = if (card) CORNER_DP.dp().toFloat() else view.height / 2f
    }

    /**
     * Take the strip off the glass while the agent screenshots the screen.
     *
     * Records the intent and lets [applyVisibility] resolve it against the rest. Writing
     * `root.visibility` directly is what let a restore that never arrived strand the strip
     * invisible, and let a re-attach mid-capture put it back in the picture.
     */
    fun setHiddenForCapture(hidden: Boolean) {
        hiddenForCapture = hidden
        applyVisibility()
    }

    /**
     * Keep the text that fits, taking it from the END.
     *
     * A `maxLines` + `ellipsize` TextView keeps the FIRST lines and trails a "…", which is
     * backwards for everything this strip shows. A live transcript grows word by word, so
     * the first lines are the stalest part of it — the user watches their own sentence get
     * cut off mid-thought while the words they just said are the ones being discarded. The
     * model's narration is newest-first in the same way.
     *
     * So the tail wins and the ellipsis goes: overflow starts again from the top of the box
     * with the most recent words, rather than freezing on the oldest with a "…" after them.
     * Cut at a word boundary where one is nearby, since a mid-word start reads as corruption.
     */
    private fun trimToFit(text: String, maxLines: Int): String {
        val budget = maxLines * APPROX_CHARS_PER_LINE
        if (text.length <= budget) return text
        val tail = text.takeLast(budget)
        val space = tail.indexOf(' ')
        return if (space in 0..WORD_BOUNDARY_SEARCH) tail.substring(space + 1) else tail
    }

    // ── view ───────────────────────────────────────────────────────────

    private fun buildView(): LinearLayout {
        val theme = com.aura.aura_ui.ui.theme.overlayColors(com.aura.aura_ui.ui.theme.overlayIsDark(context))
        ink = theme.label.toArgb()
        inkMuted = theme.secondary.toArgb()
        // Wide enough for a sentence, never wider than the screen minus its margins: the window
        // is WRAP_CONTENT so the pill can shrink to one word.
        val screenWidth = com.aura.aura_ui.accessibility.ScreenGeometry.realSizePx(context).first
        val textMax = (screenWidth - 2 * SIDE_MARGIN_DP.dp() - 2 * SIDE_PAD_DP.dp()).coerceAtLeast(MIN_WIDTH_DP.dp())
        val status = TextView(context).apply {
            maxWidth = textMax
            // Starts EMPTY and hidden. Whether there is anything to report is the caller's
            // question, not this view's — it is on screen during conversation as well as
            // during a run, and only one of those has a status.
            setTextColor(ink)
            // Deliberately SMALLER than the caption below it. This row says what the phone is
            // doing; the caption says what AURA is saying, and the caption is what the user is
            // actually reading. Same size for both made the strip read as two equal columns of
            // text with no way in.
            textSize = STATUS_SP
            // Extra leading: wrapped lines set tight are the hardest thing to read at a
            // glance, which is the only way this strip is ever read.
            setLineSpacing(2f.dp(), 1f)
            // Wraps rather than truncating: the model's own sentence names what is actually
            // on screen ("we're on YouTube's home screen; there's a Google ad at the top"),
            // and cutting that at one line throws away the half that says something.
            maxLines = STATUS_MAX_LINES
        }
        statusLine = status

        // Where the run is: the plan step this action serves. Small and quiet — it is context
        // for the line below, which is the thing that changes.
        val headline = TextView(context).apply {
            setTextColor(inkMuted)
            maxWidth = textMax
            textSize = HEADLINE_SP
            letterSpacing = 0.04f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 4.dp())
            visibility = View.GONE
        }
        headlineLine = headline

        val icon = BeatIconView(context, ink, BLOOD)
        beatIcon = icon
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                icon,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 10.dp()
                    topMargin = 1.dp()
                },
            )
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        statusRow = row

        val spoken = TextView(context).apply {
            // Full white, not the 90% ink it used to be. This row IS the content — it is what
            // the user is reading while AURA talks — and it sits on a dark plate
            // over arbitrary app pixels, where every step down in alpha costs real legibility.
            setTextColor(ink)
            maxWidth = textMax
            // Biggest thing on the strip. The status line above is a caption ABOUT the work;
            // this is what AURA is saying, read at a glance from arm's length while the phone
            // is driving itself.
            textSize = SPEECH_SP
            setLineSpacing(3f.dp(), 1f)
            // NO `maxLines`, and that is the fix for "the caption freezes then jumps".
            //
            // It used to be 3, with no `ellipsize`. A TextView in that state draws the first
            // three lines and silently DISCARDS the rest — so the moment a sentence wrapped
            // past line three the strip stopped changing while the voice kept going, and then
            // snapped when the next sentence replaced it. It read as the caption losing sync
            // with the audio; the pacer was correct the whole time and the view was throwing
            // its output away.
            //
            // Unbounded is safe here because of what is handed in: `PlaybackCaption.sentence`
            // gives ONE sentence, cut at the last `.!?` — not the running reply. The card is
            // WRAP_CONTENT, so it grows to hold that sentence and shrinks back on the next.
            // One card that changes height, never a second card and never a hidden tail.
            visibility = View.GONE
        }
        spokenLine = spoken

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = stripBackground(theme).also { plate = it }
            setPadding(PILL_SIDE_PAD_DP.dp(), PILL_VERT_PAD_DP.dp(), PILL_SIDE_PAD_DP.dp(), PILL_VERT_PAD_DP.dp())
            // A pill's radius is half its height, which is only known after layout.
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.tag != true) plate?.cornerRadius = v.height / 2f
            }
            addView(headline)
            addView(row)
            addView(spoken)
        }
    }

    /**
     * The plate: the prompt box's glass, the theme's strongest surface at the box's 0.94 alpha
     * behind its hairline edge, so the pill, the caption and the prompt box read as one surface.
     *
     * - Why no blur: frosting the app underneath hides what the user is watching AURA operate.
     * - Why untrusted is capped at 0xC8: Android 12 drops touches through an untrusted overlay
     *   whose opacity exceeds 0.8 ("Untrusted touch due to occlusion"). Do not raise past 0xCC.
     * - Change together: `inputBarBackground` in `VoiceAssistantOverlay.kt`.
     */
    private fun stripBackground(theme: com.aura.aura_ui.ui.theme.AuraColors) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = CORNER_DP.dp().toFloat()
        val fill = theme.surfaceStrong.toArgb() and 0x00FFFFFF
        setColor(fill or ((if (trusted) PLATE_ALPHA else PLATE_ALPHA_UNTRUSTED) shl 24))
        setStroke(1.dp(), theme.glassEdge.toArgb())
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type =
            if (trusted) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        // WRAP_CONTENT width, centered: a small pill for "Thinking…", a card that hugs the
        // caption. The text views cap the width (see [buildView]), never the window.
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // NOT_TOUCHABLE is the load-bearing one — see the class KDoc. NOT_FOCUSABLE
            // keeps it from ever taking the keyboard off the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = TOP_MARGIN_DP.dp()
        }
    }


    private companion object {
        const val TAG = "AgentStatusOverlay"
        /** Generous — a sentence that names what is on screen is worth the vertical space. */
        const val STATUS_MAX_LINES = 4

        /**
         * The caption is the content, so it is the larger of the two — but only by a step.
         * A first cut at 19sp was sized for a desk-distance read and simply looked shouty
         * over someone else's app; this matches the chat overlay's own body text, which is
         * the surface the user is already reading AURA in.
         */
        const val SPEECH_SP = 15f

        /** The status line is context for the caption, and is set a step down to say so. */
        const val STATUS_SP = 13f

        const val HEADLINE_SP = 11f

        /**
         * Rough characters per line at 14sp across the strip's width. Approximate on
         * purpose: it only decides how much tail to keep, and `maxLines` is the real
         * clamp — an estimate a little off costs a part-line, never a wrong render.
         */
        const val APPROX_CHARS_PER_LINE = 40

        /** How far into the tail to look for a word boundary before giving up and cutting. */
        const val WORD_BOUNDARY_SEARCH = 14

        /**
         * How long an empty strip waits before going dark.
         *
         * Sized off the measured gap, not a guess: a real session's teardown/rebuild pair sat
         * 26 ms apart, and the pauses between a pacer clearing one sentence and the next
         * arriving are the same order. Long enough to swallow those; short enough that a
         * genuinely finished caption is not left hanging (the Live pacer already holds the
         * last words for `CAPTION_DWELL_MS` = 1.5 s before it clears at all, so this rides on
         * top of a dwell rather than replacing one).
         */
        const val LINGER_MS = 600L

        /**
         * How long one newly revealed word takes to fade up.
         *
         * Shorter than the gap between two spoken words (~300 ms at conversational pace), so
         * each word has settled before the next arrives. Longer and the fades overlap into a
         * general shimmer, which is the thing that reads as "laggy" rather than "smooth".
         */
        const val WORD_FADE_MS = 160L

        /** A whole-caption swap. Slower than a word — it is a bigger change and should look it. */
        const val SENTENCE_FADE_MS = 220L

        const val TOP_MARGIN_DP = 8
        const val SIDE_MARGIN_DP = 16

        /** Floor for an absurdly narrow display, so the width can never compute to nothing. */
        const val MIN_WIDTH_DP = 160
        const val SIDE_PAD_DP = 16
        const val VERT_PAD_DP = 14

        const val PILL_SIDE_PAD_DP = 16
        const val PILL_VERT_PAD_DP = 8

        /** The caption card's radius, matching the prompt box's rounded glass. */
        const val CORNER_DP = 24

        /** The prompt box's fill alpha (0.94). Safe opaque: accessibility overlays are trusted. */
        const val PLATE_ALPHA = 0xF0

        /** Below Android 12's 0.8 untrusted-occlusion limit; see [stripBackground]. */
        const val PLATE_ALPHA_UNTRUSTED = 0xC8

        /** Fallbacks before the first build; [buildView] replaces them with the theme's ink. */
        val INK: Int = Color.parseColor("#FFFFFFFF")
        val INK_TERTIARY: Int = Color.parseColor("#8CFFFFFF")

        /** Mono's blood red — attention only: the HOLD icon, nothing decorative. */
        val BLOOD: Int = Color.parseColor("#FFA31621")
    }
}
