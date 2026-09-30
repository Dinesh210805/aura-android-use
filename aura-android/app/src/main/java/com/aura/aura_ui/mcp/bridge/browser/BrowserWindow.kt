package com.aura.aura_ui.mcp.bridge.browser

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aura.aura_ui.R
import com.aura.aura_ui.accessibility.ScreenGeometry
import com.aura.aura_ui.mcp.bridge.browser.ChromeStyle.dp

/**
 * Spec 2026-07-31 — *the window: one overlay, three sizes*.
 * Spec 2026-08-06 — *the browser plane becomes a browser*.
 *
 * The visible half of the browser plane. A page AURA drives should default to being
 * something the user can watch, the same way a person looks over someone's shoulder —
 * not a task that silently happens somewhere. [Size.FULL] is the normal state while a
 * browser task is running; [Size.BALL] lets the user shrink it out of the way without
 * stopping it (the WebView keeps running, only the window's own footprint changes);
 * [Size.HIDDEN] means no window at all, and is reached only by [detach] — used for the
 * background mode a caller has to ask for explicitly, and for the moment between two
 * tabs.
 *
 * ### What is deliberate here
 *
 * **The WebView is adopted, not created.** [attach] takes the tab the agent was already
 * driving. Building a second WebView for the visible window and loading the same URL into
 * it would throw away the session, the scroll position and any half-filled form — and a
 * reload mid-login discards half-typed credentials, which is the one thing a login handoff
 * must never do. Re-parenting a `WebView` keeps the instance and its renderer alive; the
 * page does not reload.
 *
 * **The chrome insets itself; the page does not.** This is a `FLAG_LAYOUT_IN_SCREEN`
 * window sized to the display's *real* pixels, so it genuinely owns the area behind the
 * status and navigation bars. Nothing consumed those insets before 2026-08-06, which is
 * why the banner rendered underneath the clock and the "I'm done" bar lost its lower half
 * to the gesture pill — measured on the test device, 139px at the top and 56px at the
 * bottom, ~7% of the screen. Note that raising `FULL_HEIGHT_FRACTION` to 1.0 the day
 * before *caused* the clipping while fixing a gap; a fraction was never the right control.
 * See [BrowserChromeBehavior.chromePadding].
 *
 * **The container intercepts touches; the WebView cannot report them.** A WebView consumes
 * its own touch events, so the parent's [FrameLayout.dispatchTouchEvent] is the only place
 * that sees every one of them. This feed exists because the accessibility path
 * *structurally cannot* serve here: during a handoff the user is touching AURA's own
 * window, so `PauseTrigger`'s `fromOwnApp` filter discards every touch as self-caused. Two
 * different questions ("is a human using the phone?" / "is a human using this window?")
 * that happen to share a word.
 *
 * **Focusable at [Size.FULL], not at [Size.BALL].** The Pause pill is `FLAG_NOT_FOCUSABLE`
 * precisely so it never steals the keyboard; a minimized ball has to behave the same way
 * or it would block whatever the user does elsewhere on the phone while a task runs
 * behind it. Full size is the opposite case: the user may be typing into a form or an
 * address bar, so it must take real focus or there is no keyboard.
 *
 * **One window, one hierarchy, chrome swapped by visibility.** [Size.BALL] does not tear
 * the page chrome down and rebuild it — it hides the chrome/webview column and shows a
 * small bubble in the same container, so resizing back to [Size.FULL] never touches the
 * adopted WebView at all.
 */
internal class BrowserWindow(
    private val context: Context,
    private val host: BrowserChromeHost,
) {

    /** The three sizes. Window state is cosmetic — it never changes what the agent can do. */
    enum class Size { HIDDEN, BALL, FULL }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: FrameLayout? = null
    private var host_: FrameLayout? = null
    private var column: LinearLayout? = null
    private var fullChrome: View? = null
    private var ballView: View? = null
    private var adopted: WebView? = null
    private var chrome: BrowserChrome? = null
    private var newTabPanel: NewTabPanel? = null
    private var handoffBar: View? = null

    @Volatile
    private var lastTouchAtMs: Long = 0L

    var size: Size = Size.HIDDEN
        private set

    /** Invoked on the main thread when the user taps "Done" — the escape hatch. */
    var onDone: (() -> Unit)? = null

    /**
     * Invoked on the main thread when the user taps the close ("✕") affordance, on either
     * the chrome or the minimized ball. Separate from [onDone]: the caller decides what
     * "close" means for whatever state it is in (end a handoff cleanly vs. background a
     * plain "watch it work" task) — this class only reports the tap.
     */
    var onClose: (() -> Unit)? = null

    /**
     * Where the user last dragged the ball, in absolute screen pixels (top-left origin).
     * Null until the first drag — [layoutParams] falls back to the top-end default then.
     * Survives BALL↔FULL↔BALL round trips because [resize] rebuilds LayoutParams from this
     * field rather than from the fixed margin constants every time.
     */
    private var lastBallX: Int? = null
    private var lastBallY: Int? = null

    /** True whenever a window is up, at either [Size.BALL] or [Size.FULL]. */
    val isShowing: Boolean get() = root != null

    /**
     * Milliseconds since the user last touched this window, or [Long.MAX_VALUE] if never.
     *
     * `MAX_VALUE` rather than 0 so an untouched window reads as "long since quiet" instead
     * of "just touched" — the auto-resume check compares against a minimum, and a 0 here
     * would hold every handoff open forever waiting for a touch that already happened.
     */
    val msSinceLastTouch: Long
        get() = lastTouchAtMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it }
            ?: Long.MAX_VALUE

    /** Is this window the thing in front, or did the user go elsewhere to read an OTP? */
    val isFrontmost: Boolean get() = root?.isAttachedToWindow == true && size == Size.FULL

    /**
     * Show [webView] to the user at [size]. Main thread only.
     *
     * @param isHandoff true for the "I need you" escalation (a Blood prompt bar with a
     *   Done button, [onDone] wired up); false for plain "watch the task run" visibility,
     *   where there is nothing for the user to hand back.
     * @param handoffPrompt what to ask for. Ignored unless [isHandoff].
     * @return false when the window could not be shown — no overlay permission, or an OEM
     *   that silently refuses the window. Reported rather than swallowed: a handoff the
     *   user cannot see is worse than a refused one, because the agent would sit waiting
     *   for someone to log into a window that does not exist.
     */
    fun attach(
        webView: WebView,
        size: Size = Size.FULL,
        isHandoff: Boolean = true,
        handoffPrompt: String? = null,
    ): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            Log.e(TAG, "Browser window needs the overlay permission")
            return false
        }
        detach()

        val container = buildContainer(isHandoff, handoffPrompt)
        // Detach from whatever held it. The engine keeps its tabs' WebViews parentless and
        // lays them out by hand, so in practice this is a no-op — but a WebView that is
        // still someone's child cannot be added anywhere, and it throws when you try.
        (webView.parent as? ViewGroup)?.removeView(webView)
        // Index 0: below the new-tab panel, which was added first and must stay on top
        // when it is visible.
        host_?.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val added = runCatching { windowManager.addView(container, layoutParams(size)) }.isSuccess
        if (!added) {
            Log.e(TAG, "Browser window rejected by WindowManager")
            host_?.removeView(webView)
            return false
        }

        root = container
        adopted = webView
        this.size = size
        lastTouchAtMs = 0L
        applyChromeFor(size)
        refreshChrome()
        return true
    }

    /**
     * Switch an already-attached window between [Size.BALL] and [Size.FULL] in place.
     *
     * Never rebuilds the hierarchy or touches the adopted WebView — only the window's
     * own [WindowManager.LayoutParams] change, via [WindowManager.updateViewLayout], the
     * same mechanism `PausePillOverlay` uses for its own moves. [Size.HIDDEN] is not
     * accepted here; that is [detach]'s job, because going fully background means giving
     * the WebView its offscreen layout back, which this class deliberately does not do
     * (see [detach]'s doc).
     *
     * @return false if there is no window up, or the resize itself failed.
     */
    fun resize(newSize: Size): Boolean {
        val container = root ?: return false
        if (newSize == Size.HIDDEN) return false
        val ok = runCatching { windowManager.updateViewLayout(container, layoutParams(newSize)) }.isSuccess
        if (!ok) return false
        size = newSize
        applyChromeFor(newSize)
        return true
    }

    /** Repaint the chrome from the host. No-op when no window is up. */
    fun refreshChrome() {
        chrome?.render()
        if (newTabPanel?.view?.visibility == View.VISIBLE) newTabPanel?.render()
    }

    /**
     * Show or hide the local new-tab surface over the page.
     *
     * Layered rather than loaded into the WebView on purpose — see [NewTabPanel]. Shown
     * automatically for a freshly-opened tab, because a blank white rectangle is a worse
     * answer to "+" than a search box.
     */
    fun showNewTabPanel(show: Boolean) {
        val panel = newTabPanel ?: return
        if (show) panel.render()
        panel.view.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun applyChromeFor(size: Size) {
        fullChrome?.visibility = if (size == Size.FULL) View.VISIBLE else View.GONE
        ballView?.visibility = if (size == Size.BALL) View.VISIBLE else View.GONE
    }

    /**
     * Take the window down and hand the WebView back, parentless.
     *
     * The caller must re-assert the offscreen layout afterwards. It is not done here
     * because this class does not know how the engine wants its detached views measured —
     * and guessing would reintroduce the 0×0 viewport bug from the other direction.
     */
    fun detach(): WebView? {
        val view = adopted
        host_?.let { h -> view?.let { runCatching { h.removeView(it) } } }
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        host_ = null
        column = null
        fullChrome = null
        ballView = null
        adopted = null
        chrome = null
        newTabPanel = null
        handoffBar = null
        size = Size.HIDDEN
        return view
    }

    // ── construction ───────────────────────────────────────────────────

    private fun buildContainer(isHandoff: Boolean, handoffPrompt: String?): FrameLayout {
        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ChromeStyle.INK)
        }
        column = stack

        val browserChrome = BrowserChrome(
            context = context,
            host = host,
            onMinimize = { resize(Size.BALL) },
            onClose = { onClose?.invoke() },
        )
        chrome = browserChrome
        stack.addView(
            browserChrome.view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val pageHost = FrameLayout(context)
        host_ = pageHost
        val panel = NewTabPanel(context, host)
        newTabPanel = panel
        pageHost.addView(
            panel.view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        stack.addView(
            pageHost,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        if (isHandoff) {
            // Always present during a handoff, never conditional. Auto-resume is three
            // heuristics over a human being, and a heuristic without a manual override is
            // a trap.
            val bar = buildHandoffBar(handoffPrompt)
            handoffBar = bar
            stack.addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        fullChrome = stack

        val ball = buildBall()
        ballView = ball

        val layers = FrameLayout(context).apply {
            addView(
                stack,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            addView(ball, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        return TouchReportingFrame(context) { lastTouchAtMs = System.currentTimeMillis() }.apply {
            // Absorb the window's initial focus. This window is focusable (the user has to
            // be able to type), so on attach Android hands focus to the first focusable
            // descendant — which is the address bar. Observed on device: opening the
            // browser left the URL selected in blue, one stray keypress away from being
            // replaced, before the user had even looked at the page. A focusable container
            // takes that first focus instead; tapping the field still focuses it normally.
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            addView(
                layers,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            applyInsets(this)
        }
    }

    /**
     * Keep the chrome out from under the system bars.
     *
     * Seeded from the platform's own `status_bar_height` / `navigation_bar_height`
     * dimensions before the first inset dispatch arrives, because an overlay window may
     * lay out one frame before it is told anything — and one frame of chrome under the
     * clock is exactly the bug being fixed. The listener then corrects the seed, which
     * matters on gesture navigation where the resource still reports the old 48dp bar
     * while the real inset is 16dp.
     */
    private fun applyInsets(target: View) {
        fun barHeight(name: String): Int {
            val id = context.resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        }
        setChromePadding(barHeight("status_bar_height"), barHeight("navigation_bar_height"), 0)

        ViewCompat.setOnApplyWindowInsetsListener(target) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            setChromePadding(bars.top, bars.bottom, ime.bottom)
            insets
        }
    }

    private fun setChromePadding(statusBarPx: Int, navBarPx: Int, imeHeightPx: Int) {
        val padding = BrowserChromeBehavior.chromePadding(statusBarPx, navBarPx, imeHeightPx)
        // Padding goes on the column, not on the individual rows: it then applies equally
        // to the chrome at the top and to whichever of the page or the handoff bar is at
        // the bottom, so nothing can end up underneath a bar by being added later.
        column?.setPadding(0, padding.top, 0, padding.bottom)
    }

    /**
     * The handoff bar: what the user is being asked for, and the way to say they are done.
     *
     * Blood on the dot and the button, ink on the bar. The old version was a flat
     * full-width black bar of centred text, which read as a disabled footer rather than
     * the one control the whole escalation exists to offer.
     */
    private fun buildHandoffBar(prompt: String?): View {
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ChromeStyle.BLOOD)
            }
            layoutParams = LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply {
                marginStart = context.dp(16)
                marginEnd = context.dp(10)
            }
        }

        val promptLabel = ChromeStyle.label(
            context,
            prompt?.takeIf { it.isNotBlank() } ?: DEFAULT_HANDOFF_PROMPT,
            sizeSp = 13.5f,
        ).apply { maxLines = 2 }

        val done = TextView(context).apply {
            this.text = DONE_LABEL
            setTextColor(ChromeStyle.TEXT)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(context.dp(22), context.dp(10), context.dp(22), context.dp(10))
            background = ChromeStyle.surfaceRipple(context, ChromeStyle.BLOOD, null, 999f)
            setOnClickListener { onDone?.invoke() }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ChromeStyle.INK)
            setPadding(0, context.dp(10), context.dp(12), context.dp(10))
            addView(dot)
            addView(
                promptLabel,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = context.dp(12)
                },
            )
            addView(done)
        }
    }

    /**
     * The minimized ball: the AURA mark on an ink circle, with a close badge on its corner.
     *
     * Was a 🌐 emoji, replaced on user instruction (2026-08-06). An emoji also renders in
     * whatever the system font vendor decided, which on an OEM skin is not necessarily the
     * glyph anyone designed for — the mark is ours and looks the same everywhere.
     */
    private fun buildBall(): View {
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ChromeStyle.INK)
                setStroke(context.dp(1), ChromeStyle.OUTLINE)
            }
            elevation = context.dp(6).toFloat()
            addView(
                ChromeStyle.auraMark(context, 30).apply {
                    layoutParams = FrameLayout.LayoutParams(context.dp(30), context.dp(30)).apply {
                        gravity = Gravity.CENTER
                    }
                },
            )
            setOnTouchListener(dragAndTapListener())
        }

        val closeBadge = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(ChromeStyle.TEXT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Close browser"
            val pad = context.dp(5)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ChromeStyle.BLOOD)
            }
            elevation = context.dp(7).toFloat()
            setOnClickListener { onClose?.invoke() }
        }

        return FrameLayout(context).apply {
            addView(
                circle,
                FrameLayout.LayoutParams(context.dp(BALL_DP), context.dp(BALL_DP)).apply {
                    gravity = Gravity.CENTER
                },
            )
            addView(
                closeBadge,
                FrameLayout.LayoutParams(context.dp(CLOSE_BADGE_DP), context.dp(CLOSE_BADGE_DP)).apply {
                    gravity = Gravity.TOP or Gravity.END
                },
            )
            visibility = View.GONE
        }
    }

    /**
     * Sees every touch before its children do — including the ones the WebView keeps.
     *
     * `dispatchTouchEvent` rather than `onTouchEvent` or an `OnTouchListener`: a WebView
     * consumes its own events, so anything downstream of it never hears about the typing
     * and tapping that IS the handoff. This is the whole reason the class exists.
     */
    private class TouchReportingFrame(
        context: Context,
        private val onTouch: () -> Unit,
    ) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            onTouch()
            return super.dispatchTouchEvent(ev)
        }
    }

    private fun layoutParams(size: Size): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val (screenWidth, screenHeight) = ScreenGeometry.realSizePx(context)
        val (w, h) = when (size) {
            Size.FULL -> screenWidth to screenHeight
            Size.BALL -> context.dp(BALL_DP) to context.dp(BALL_DP)
            Size.HIDDEN -> 1 to 1
        }

        // NOT_FOCUSABLE at BALL: a minimized bubble must not steal the keyboard from
        // whatever the user is doing elsewhere on the phone while the task keeps running
        // behind it — the same reasoning `PausePillOverlay` uses. FULL is the one
        // deliberate exception among AURA's overlays, because the user may be typing.
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (size == Size.BALL) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        // TRANSLUCENT at BALL, OPAQUE at FULL. The ball is a circle inside a square
        // window: with an opaque format the corners outside the circle paint solid black,
        // so the "bubble" renders as a black box with a circle drawn on it (observed on
        // device). FULL stays opaque because a full-screen window that the compositor
        // knows is opaque skips everything behind it.
        val format = if (size == Size.BALL) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE

        return WindowManager.LayoutParams(w, h, type, flags, format).apply {
            // ADJUST_RESIZE so the page shrinks above the keyboard instead of hiding the
            // field being typed into.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (size == Size.BALL) {
                // TOP|START with absolute coordinates, not TOP|END with a fixed margin: a
                // draggable ball needs a plain (x, y) it can move freely, and defaulting to
                // the top-right corner (this class's original resting spot) only on the
                // very first show keeps existing behaviour unless the user has moved it.
                gravity = Gravity.TOP or Gravity.START
                x = lastBallX ?: clampX(screenWidth - context.dp(BALL_DP) - context.dp(BALL_MARGIN_DP))
                y = lastBallY ?: clampY(context.dp(BALL_MARGIN_DP))
            } else {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
    }

    private fun clampX(x: Int): Int {
        val screenW = ScreenGeometry.realSizePx(context).first
        return x.coerceIn(0, (screenW - context.dp(BALL_DP)).coerceAtLeast(0))
    }

    private fun clampY(y: Int): Int {
        val screenH = ScreenGeometry.realSizePx(context).second
        return y.coerceIn(0, (screenH - context.dp(BALL_DP)).coerceAtLeast(0))
    }

    /**
     * Drag-to-move plus tap-to-expand on the minimized ball, mirroring the pattern
     * `PausePillOverlay` uses for its own drag handle: a slop threshold tells a trembling
     * tap from a real drag, and only a real drag updates the window's position.
     *
     * Reads and writes [root]'s LIVE `WindowManager.LayoutParams` directly (via
     * `updateViewLayout`) rather than calling [resize] mid-drag — `resize` rebuilds the
     * params from scratch and would fight every frame of the gesture.
     */
    private fun dragAndTapListener() = object : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragged = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val params = root?.layoutParams as? WindowManager.LayoutParams ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (!dragged &&
                        kotlin.math.abs(dx) < context.dp(TOUCH_SLOP_DP) &&
                        kotlin.math.abs(dy) < context.dp(TOUCH_SLOP_DP)
                    ) {
                        return true
                    }
                    dragged = true
                    params.x = clampX(startX + dx)
                    params.y = clampY(startY + dy)
                    runCatching { windowManager.updateViewLayout(root, params) }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) {
                        lastBallX = params.x
                        lastBallY = params.y
                    } else if (e.action == MotionEvent.ACTION_UP) {
                        resize(Size.FULL)
                    }
                }
            }
            return true
        }
    }

    private companion object {
        const val TAG = "BrowserWindow"

        const val BALL_DP = 60
        const val BALL_MARGIN_DP = 24
        const val CLOSE_BADGE_DP = 22
        const val TOUCH_SLOP_DP = 8

        const val DEFAULT_HANDOFF_PROMPT = "Sign in, then tap Done"
        const val DONE_LABEL = "Done"
    }
}

/** Convenience for callers that hold a nullable view. */
internal fun View?.detachFromParent() {
    (this?.parent as? ViewGroup)?.removeView(this)
}
