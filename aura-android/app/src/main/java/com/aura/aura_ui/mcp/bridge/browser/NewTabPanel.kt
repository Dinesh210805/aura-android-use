package com.aura.aura_ui.mcp.bridge.browser

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aura.aura_ui.R
import com.aura.aura_ui.mcp.bridge.browser.ChromeStyle.dp

/**
 * Spec 2026-08-06 — *the browser plane becomes a browser*.
 *
 * What "Home" means in a browser that has no home page.
 *
 * A configured start page would be a lie here — nobody set one, and loading a search
 * engine's homepage instead would be an outbound request and a third party handed the
 * fact that you tapped Home. So Home is a **local surface**: a search box and the tabs
 * that are already open. It costs no network, promises nothing it cannot keep, and
 * doubles as the tab overview a 44dp strip cannot fully serve.
 *
 * Drawn as a native `View` layered over the page host rather than loaded into the
 * `WebView`. Loading it as a `data:` URL would burn a history entry — so Back would walk
 * into the new-tab page — and would need the `data:` scheme that [BrowserChromeBehavior]
 * deliberately refuses.
 */
internal class NewTabPanel(
    private val context: Context,
    private val host: BrowserChromeHost,
) {

    private val tabList: LinearLayout
    private val searchField: EditText

    val view: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(ChromeStyle.INK)
        visibility = View.GONE
        // Opaque and clickable: this sits on top of a live WebView, and a panel that lets
        // taps fall through to the page underneath is worse than no panel.
        isClickable = true
    }

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(40), context.dp(24), context.dp(24))
        }

        column.addView(
            ChromeStyle.auraMark(context, 48).apply {
                (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        column.addView(
            ChromeStyle.label(context, "Where to?", sizeSp = 22f).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = context.dp(18) }
            },
        )

        searchField = EditText(context).apply {
            setTextColor(ChromeStyle.TEXT)
            setHintTextColor(ChromeStyle.TEXT_DIM)
            hint = BrowserChromeBehavior.EMPTY_URL_HINT
            textSize = 15f
            setSingleLine()
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = ChromeStyle.surface(context, ChromeStyle.INK_RAISED, ChromeStyle.OUTLINE, 16f)
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    val typed = text?.toString().orEmpty()
                    setText("")
                    clearFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(windowToken, 0)
                    host.onChromeNavigate(typed)
                    true
                } else {
                    false
                }
            }
        }
        column.addView(
            searchField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = context.dp(20) },
        )

        column.addView(
            ChromeStyle.label(context, "Open tabs", sizeSp = 12f, color = ChromeStyle.TEXT_DIM).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = context.dp(28) }
            },
        )

        tabList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            tabList,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = context.dp(10) },
        )

        view.addView(
            ScrollView(context).apply { addView(column) },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** Rebuild the tab cards. Same full-repaint reasoning as [BrowserChrome.render]. */
    fun render() {
        tabList.removeAllViews()
        val tabs = host.chromeTabs()
        if (tabs.isEmpty()) {
            tabList.addView(
                ChromeStyle.label(context, "Nothing open yet.", sizeSp = 13f, color = ChromeStyle.TEXT_DIM),
            )
            return
        }
        tabs.forEachIndexed { index, tab -> tabList.addView(card(tab, index)) }
    }

    private fun card(tab: ChromeTab, index: Int): View {
        val icon = ImageView(context).apply {
            setImageResource(if (tab.secure) R.drawable.ic_lock else R.drawable.ic_globe)
            setColorFilter(ChromeStyle.TEXT_DIM)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply {
                marginEnd = context.dp(12)
            }
        }

        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ChromeStyle.label(context, tab.title, sizeSp = 14f))
            addView(
                ChromeStyle.label(
                    context,
                    BrowserChromeBehavior.displayUrl(tab.url),
                    sizeSp = 11.5f,
                    color = ChromeStyle.TEXT_DIM,
                ),
            )
        }

        val closeCard = ChromeStyle.iconButton(
            context,
            R.drawable.ic_close,
            "Close ${tab.title}",
            ChromeStyle.TEXT_DIM,
        ) { host.onChromeCloseTab(index) }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ChromeStyle.surfaceRipple(context, ChromeStyle.INK_RAISED, ChromeStyle.OUTLINE, 16f)
            setPadding(context.dp(14), context.dp(12), context.dp(6), context.dp(12))
            addView(icon)
            addView(
                text,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                closeCard,
                LinearLayout.LayoutParams(context.dp(36), context.dp(36)),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = context.dp(8) }
            setOnClickListener { host.onChromeSelectTab(index) }
        }
    }
}
