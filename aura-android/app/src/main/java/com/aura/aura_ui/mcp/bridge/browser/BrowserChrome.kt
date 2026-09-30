package com.aura.aura_ui.mcp.bridge.browser

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aura.aura_ui.R
import com.aura.aura_ui.mcp.bridge.browser.ChromeStyle.dp
import com.aura.aura_ui.mcp.bridge.browser.ChromeStyle.setEnabledDimmed

/**
 * Spec 2026-08-06 — *the browser plane becomes a browser*.
 *
 * The two rows above the page: a tab strip and a toolbar, plus the loading bar between
 * them. Everything visible that is not the page itself and not the handoff bar.
 *
 * ### What is deliberate here
 *
 * **It renders, it does not decide.** Every question with a right answer — is this typed
 * text a URL or a search, what do we call a tab with no title, how far down does the
 * status bar push us — lives in [BrowserChromeBehavior] where it can be tested. This
 * class turns the answers into pixels and turns taps into [BrowserChromeHost] calls.
 *
 * **The AURA mark is permanent and is the identity.** It replaces the old
 * "AURA is browsing — tap − to minimize" text banner. A browser window that appears
 * unannounced over someone's phone is indistinguishable from a phishing overlay, so
 * *something* must say whose it is; a mark the user recognises does that in 24dp and
 * keeps working after they have stopped reading the sentence.
 *
 * **[render] is a full repaint from a snapshot.** Chips are rebuilt rather than diffed.
 * A chip that survives across updates can outlive the tab it points at — and then a tap
 * reaches a destroyed `WebView`. Rebuilding costs a handful of views on a state change
 * a human caused, and makes that whole class of bug unreachable.
 */
internal class BrowserChrome(
    private val context: Context,
    private val host: BrowserChromeHost,
    private val onMinimize: () -> Unit,
    private val onClose: () -> Unit,
) {

    private val tabScroller: HorizontalScrollView
    private val tabRow: LinearLayout
    private val newTabButton: ImageView
    private val backButton: ImageView
    private val forwardButton: ImageView
    private val reloadButton: ImageView
    private val securityIcon: ImageView
    private val urlField: EditText
    private val progressFill: View
    private val progressTrack: FrameLayout

    /** True while the user is typing an address — suppresses repaints that would fight them. */
    private var editing = false

    /**
     * What the strip was last built from.
     *
     * `onProgressChanged` fires 10-20 times per page load, and each one reaches [render].
     * Without this the strip tore itself down and rebuilt every chip — plus queued a
     * smooth-scroll animation — on every tick, stuttering precisely while the user is
     * watching a page load. The full-repaint rule in this class's KDoc is about chips not
     * outliving their tabs; it never required rebuilding when nothing about the tabs
     * changed.
     */
    private var tabSignature: String? = null

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ChromeStyle.INK)
    }

    init {
        // ── row 1: identity, tabs, window controls ─────────────────────
        val strip = ChromeStyle.row(context, ChromeStyle.TAB_ROW_DP)
        strip.addView(
            ChromeStyle.auraMark(context, 24).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = context.dp(12)
            },
        )

        tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            // Fading edges, because a half-scrolled chip is otherwise indistinguishable
            // from a control. Observed on device: with the active chip scrolled into view,
            // the previous chip's trailing "✕" was all that remained visible, sitting
            // right beside the AURA mark — it read as a close button *for the mark*. A
            // fade says "there is more this way" in the idiom Android users already know.
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(context.dp(20))
            addView(
                tabRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        // weight 1 so the chips take whatever the controls leave and scroll past it,
        // rather than pushing the controls off the end of a narrow screen.
        strip.addView(
            tabScroller,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = context.dp(8)
            },
        )

        newTabButton = ChromeStyle.iconButton(context, R.drawable.ic_add, "New tab") {
            host.onChromeNewTab()
        }
        strip.addView(newTabButton, buttonParams())
        strip.addView(
            ChromeStyle.iconButton(context, R.drawable.ic_home, "Home") { host.onChromeHome() },
            buttonParams(),
        )
        strip.addView(
            ChromeStyle.iconButton(context, R.drawable.ic_minimize, "Minimize") { onMinimize() },
            buttonParams(),
        )
        // Blood: closing is the one destructive control up here.
        strip.addView(
            ChromeStyle.iconButton(context, R.drawable.ic_close, "Close browser", ChromeStyle.BLOOD) {
                onClose()
            },
            buttonParams().apply { marginEnd = context.dp(4) },
        )
        view.addView(strip)

        // ── row 2: navigation and the address ──────────────────────────
        val toolbar = ChromeStyle.row(context, ChromeStyle.TOOLBAR_DP)
        backButton = ChromeStyle.iconButton(context, R.drawable.ic_arrow_back, "Back") {
            host.onChromeBack()
        }
        forwardButton = ChromeStyle.iconButton(context, R.drawable.ic_arrow_forward, "Forward") {
            host.onChromeForward()
        }
        toolbar.addView(backButton, buttonParams().apply { marginStart = context.dp(4) })
        toolbar.addView(forwardButton, buttonParams())

        securityIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply {
                marginStart = context.dp(12)
                marginEnd = context.dp(8)
            }
        }

        urlField = EditText(context).apply {
            setTextColor(ChromeStyle.TEXT)
            setHintTextColor(ChromeStyle.TEXT_DIM)
            textSize = 14f
            maxLines = 1
            setSingleLine()
            background = null
            setPadding(0, 0, 0, 0)
            hint = BrowserChromeBehavior.EMPTY_URL_HINT
            // textUri, not textUri|textAutoComplete: an address bar that autocorrects
            // "github" to "GitHub" and capitalises hostnames is worse than no help at all.
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    commitUrl()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                editing = hasFocus
                if (hasFocus) {
                    // Swap the friendly display form for the real URL the moment editing
                    // starts. Someone who taps the bar to copy or tweak an address needs
                    // the scheme and the query, not the tidied-up version.
                    setText(currentTab()?.url.orEmpty())
                    // Select-all must be POSTED, not called inline. Observed on device:
                    // tapping the bar on example.com and typing "wikipedia.org" produced
                    // "wikipedia.orgexample.com" — the selection was collapsed to offset 0
                    // by the IME attaching after this callback, so typing inserted instead
                    // of replacing. Every address bar selects on focus; getting it wrong
                    // makes the bar unusable for anything but appending.
                    post { if (hasSelection() || text.isNotEmpty()) selectAll() }
                } else {
                    render()
                }
            }
        }

        reloadButton = ChromeStyle.iconButton(context, R.drawable.ic_refresh, "Reload") {
            host.onChromeReload()
        }

        val addressBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ChromeStyle.surface(context, ChromeStyle.INK_RAISED, ChromeStyle.OUTLINE, 14f)
            addView(securityIcon)
            addView(
                urlField,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                reloadButton,
                LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply {
                    marginEnd = context.dp(4)
                },
            )
        }
        toolbar.addView(
            addressBar,
            LinearLayout.LayoutParams(0, context.dp(40), 1f).apply {
                marginStart = context.dp(4)
                marginEnd = context.dp(10)
            },
        )
        view.addView(toolbar)

        // ── row 3: loading ─────────────────────────────────────────────
        // A hairline track rather than nothing, so the bar grows along a visible rail
        // instead of a stripe appearing out of black.
        progressFill = View(context).apply { setBackgroundColor(ChromeStyle.TEXT) }
        progressTrack = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(28, 255, 255, 255))
            addView(
                progressFill,
                FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        view.addView(
            progressTrack,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(ChromeStyle.PROGRESS_DP),
            ),
        )

        render()
    }

    private fun buttonParams() = LinearLayout.LayoutParams(
        context.dp(ChromeStyle.ICON_BUTTON_DP),
        context.dp(ChromeStyle.ICON_BUTTON_DP),
    )

    private fun currentTab(): ChromeTab? = host.chromeTabs().getOrNull(host.chromeActiveIndex())

    private fun commitUrl() {
        val typed = urlField.text?.toString().orEmpty()
        urlField.clearFocus()
        hideKeyboard()
        host.onChromeNavigate(typed)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(urlField.windowToken, 0)
    }

    /** Repaint from the host's current state. Safe to call as often as it changes. */
    fun render() {
        val tabs = host.chromeTabs()
        val activeIndex = host.chromeActiveIndex()
        val active = tabs.getOrNull(activeIndex)

        renderAddress(active)
        renderProgress(active)
        renderTabs(tabs, activeIndex)

        backButton.setEnabledDimmed(active?.canGoBack == true)
        forwardButton.setEnabledDimmed(active?.canGoForward == true)
        newTabButton.setEnabledDimmed(tabs.size < MAX_CHIPS_BEFORE_FULL)
    }

    private fun renderAddress(active: ChromeTab?) {
        // Never overwrite a field the user is typing into. Agent navigation continues
        // underneath, and the bar catches up the moment they commit or tap away.
        if (editing) return

        val url = active?.url.orEmpty()
        urlField.setText(BrowserChromeBehavior.displayUrl(url).takeIf { it != BrowserChromeBehavior.EMPTY_URL_HINT } ?: "")

        when {
            url.isBlank() -> {
                securityIcon.setImageResource(R.drawable.ic_globe)
                securityIcon.setColorFilter(ChromeStyle.TEXT_DIM)
            }
            active?.secure == true -> {
                securityIcon.setImageResource(R.drawable.ic_lock)
                securityIcon.setColorFilter(ChromeStyle.TEXT_DIM)
            }
            else -> {
                // Blood, and one of only three uses in this window. An http page the user
                // is about to type a password into is exactly the "look here now" case the
                // colour is reserved for.
                securityIcon.setImageResource(R.drawable.ic_warning)
                securityIcon.setColorFilter(ChromeStyle.BLOOD)
            }
        }
    }

    private fun renderProgress(active: ChromeTab?) {
        val loading = active?.loading == true
        progressTrack.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        if (!loading) return
        progressTrack.post {
            val full = progressTrack.width
            if (full <= 0) return@post
            progressFill.layoutParams = progressFill.layoutParams.apply {
                width = full * (active?.progress ?: 0) / 100
            }
            progressFill.requestLayout()
        }
    }

    private fun renderTabs(tabs: List<ChromeTab>, activeIndex: Int) {
        // Identity of the strip, not of the load: progress is deliberately absent.
        val signature = "$activeIndex|" + tabs.joinToString("|") { "${it.title} ${it.secure}" }
        if (signature == tabSignature) return
        tabSignature = signature

        tabRow.removeAllViews()
        if (!BrowserChromeBehavior.shouldShowTabChips(tabs.size)) {
            // INVISIBLE, not GONE. The scroller carries the row's `weight = 1`, so hiding
            // it with GONE removes it from the layout entirely and the window controls
            // slide left to meet the AURA mark — "+" lands under the thumb that reached
            // for "close" a moment ago, purely because a tab was opened or shut. Keeping
            // the space reserved pins the controls to the right edge in every state.
            tabScroller.visibility = View.INVISIBLE
            return
        }
        tabScroller.visibility = View.VISIBLE
        var activeChip: View? = null
        tabs.forEachIndexed { index, tab ->
            val view = chip(tab, index, active = index == activeIndex)
            if (index == activeIndex) activeChip = view
            tabRow.addView(view)
        }

        // Scroll the active chip into view. Without this the strip always starts at tab 0,
        // so with two tabs open on a phone-width row the tab you are actually looking at
        // is off the right edge — the strip then actively misinforms, showing one chip
        // that is not the current page.
        val target = activeChip ?: return
        tabScroller.post {
            val margin = context.dp(24)
            val left = target.left - margin
            val right = target.right + margin
            when {
                left < tabScroller.scrollX -> tabScroller.smoothScrollTo(left.coerceAtLeast(0), 0)
                right > tabScroller.scrollX + tabScroller.width ->
                    tabScroller.smoothScrollTo((right - tabScroller.width).coerceAtLeast(0), 0)
            }
        }
    }

    /**
     * One tab chip.
     *
     * The active chip **inverts** — light fill, ink text — rather than merely brightening.
     * On a black bar a brightness difference between "selected" and "not selected" is the
     * kind of distinction that survives a design review and fails in sunlight.
     */
    private fun chip(tab: ChromeTab, index: Int, active: Boolean): View {
        val fill = if (active) ChromeStyle.TEXT else ChromeStyle.INK
        val textColor = if (active) ChromeStyle.INK else ChromeStyle.TEXT_DIM
        val stroke = if (active) null else ChromeStyle.OUTLINE

        val title = TextView(context).apply {
            text = BrowserChromeBehavior.ellipsise(tab.title, STRIP_TITLE_MAX)
            setTextColor(textColor)
            textSize = 12.5f
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }

        val closeChip = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(textColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Close ${tab.title}"
            layoutParams = LinearLayout.LayoutParams(context.dp(14), context.dp(14)).apply {
                marginStart = context.dp(8)
            }
            setOnClickListener { host.onChromeCloseTab(index) }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ChromeStyle.surfaceRipple(context, fill, stroke, 11f)
            setPadding(context.dp(11), 0, context.dp(9), 0)
            addView(title)
            addView(closeChip)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                context.dp(30),
            ).apply { marginEnd = context.dp(6) }
            setOnClickListener { host.onChromeSelectTab(index) }
        }
    }

    private companion object {
        /**
         * Mirrors `AppBrowserBridge.MAX_TABS`. Duplicated rather than imported because the
         * chrome package must not depend back on the bridge — the dependency runs one way,
         * through [BrowserChromeHost]. Only used to dim the "+" button, so drifting by one
         * costs a slightly-too-bright icon and never a wrong refusal: the bridge is still
         * the thing that enforces the cap.
         */
        const val MAX_CHIPS_BEFORE_FULL = 8

        /**
         * Shorter than BrowserChromeBehavior.TAB_TITLE_MAX. The controls own 45% of the
         * row, so a full-length title is one chip and nothing else.
         */
        const val STRIP_TITLE_MAX = 13
    }
}
