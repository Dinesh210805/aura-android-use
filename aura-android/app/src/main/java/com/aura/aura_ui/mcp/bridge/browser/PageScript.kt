package com.aura.aura_ui.mcp.bridge.browser

/**
 * The JavaScript injected into the scratch WebView.
 *
 * Design notes that matter:
 *
 *  - **Elements are stamped, not path-addressed.** Extraction tags each interactive
 *    node with `data-aura-el="N"` and hands back `[data-aura-el="N"]` as its selector.
 *    A generated CSS path (`div>div:nth-child(7)>a`) breaks the moment the page
 *    re-renders; a stamped attribute survives anything short of a DOM replacement,
 *    and when it *is* replaced the selector misses cleanly instead of matching the
 *    wrong element. Missing loudly beats hitting the wrong link.
 *
 *  - **No `@JavascriptInterface` anywhere.** Everything crosses the boundary as a
 *    JSON string through `evaluateJavascript`'s return value. An exposed interface
 *    object is reachable by any script on any page the browser visits, which is
 *    the classic Android WebView RCE surface.
 *
 *  - **Typing goes through the native value setter.** React and friends install
 *    their own `value` property descriptor; assigning `el.value` directly updates
 *    the DOM but never notifies the framework, so the page reverts on the next
 *    render. This is the same class of problem the AURA IME work solved on the
 *    native side — the visible field changing is not proof the app agrees.
 */
internal object PageScript {

    /** Returns a JSON *string* (double-encoded through evaluateJavascript). */
    val EXTRACT: String = """
    (function() {
      // Is this document actually laid out? A detached WebView never runs the
      // compositor, so every rect is 0x0 there. Measured on device: filtering on
      // geometry returned 0 of 4 elements. We don't need geometry — elements are
      // addressed by selector, never by coordinate — so geometry is used only as
      // a filter when it's trustworthy, and ignored when the page has none.
      var laidOut = !!(document.body && document.body.getBoundingClientRect().width > 0);

      function hiddenByStyle(el) {
        // Walk ancestors: a child of a display:none parent still reports its own
        // computed display, so checking the element alone misses hidden subtrees.
        var node = el;
        while (node && node.nodeType === 1) {
          var s = window.getComputedStyle(node);
          if (s && (s.display === 'none' || s.visibility === 'hidden')) return true;
          node = node.parentElement;
        }
        return false;
      }

      function visible(el) {
        if (!el) return false;
        if (el.hasAttribute('hidden') || el.getAttribute('aria-hidden') === 'true') return false;
        if (hiddenByStyle(el)) return false;
        if (laidOut) {
          var r = el.getBoundingClientRect();
          if (r.width <= 0 || r.height <= 0) return false;
        }
        return true;
      }
      function role(el) {
        var tag = el.tagName.toLowerCase();
        var explicit = (el.getAttribute('role') || '').toLowerCase();
        if (tag === 'input') {
          var t = (el.getAttribute('type') || 'text').toLowerCase();
          if (t === 'password') return 'password';
          if (t === 'checkbox') return 'checkbox';
          if (t === 'radio') return 'radio';
          if (t === 'submit' || t === 'button') return 'button';
          return 'input';
        }
        if (tag === 'textarea') return 'textarea';
        if (tag === 'select') return 'select';
        if (tag === 'button') return 'button';
        if (tag === 'a') return 'link';
        if (explicit === 'button' || explicit === 'link') return explicit;
        return explicit || tag;
      }
      function label(el) {
        var candidates = [
          el.getAttribute('aria-label'),
          (el.innerText || '').trim(),
          el.getAttribute('placeholder'),
          el.getAttribute('title'),
          el.getAttribute('alt'),
          el.getAttribute('name')
        ];
        for (var i = 0; i < candidates.length; i++) {
          var c = candidates[i];
          if (c && c.trim().length > 0) return c.trim().slice(0, 200);
        }
        return '';
      }
      function value(el) {
        var tag = el.tagName.toLowerCase();
        if (tag === 'input' || tag === 'textarea' || tag === 'select') {
          if (role(el) === 'password') return null;   // never exfiltrate a typed secret
          var v = el.value;
          return (v && v.length) ? String(v).slice(0, 200) : null;
        }
        return null;
      }

      var previous = document.querySelectorAll('[data-aura-el]');
      for (var p = 0; p < previous.length; p++) previous[p].removeAttribute('data-aura-el');

      var sel = 'a[href], button, input, textarea, select, ' +
                '[role="button"], [role="link"], [role="checkbox"], [role="tab"], [onclick]';
      var nodes = document.querySelectorAll(sel);
      var out = [];
      var n = 0;
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (!visible(el)) continue;
        n++;
        el.setAttribute('data-aura-el', String(n));
        out.push({
          selector: '[data-aura-el="' + n + '"]',
          role: role(el),
          label: label(el),
          value: value(el),
          disabled: !!(el.disabled || el.getAttribute('aria-disabled') === 'true')
        });
      }

      var body = document.body ? (document.body.innerText || '') : '';
      return JSON.stringify({
        url: location.href,
        title: document.title || '',
        text: body,
        elements: out
      });
    })()
    """.trimIndent()

    /** Build the action script for [selector]. [action] is a lowercase verb. */
    fun act(selector: String, action: String, value: String?): String {
        val sel = jsString(selector)
        val v = value?.let { jsString(it) } ?: "null"
        return """
        (function() {
          var el = document.querySelector($sel);
          if (!el) return JSON.stringify({ ok: false, error: 'element no longer on the page' });
          try { el.scrollIntoView({ block: 'center' }); } catch (e) {}

          function setNativeValue(node, text) {
            // Frameworks (React et al.) override the value setter to track state.
            // Writing through the *prototype's* setter updates the DOM the way the
            // framework expects, then 'input' tells it to re-read.
            var proto = Object.getPrototypeOf(node);
            var desc = Object.getOwnPropertyDescriptor(proto, 'value');
            if (desc && desc.set) { desc.set.call(node, text); } else { node.value = text; }
            node.dispatchEvent(new Event('input', { bubbles: true }));
            node.dispatchEvent(new Event('change', { bubbles: true }));
          }

          var action = ${jsString(action)};
          if (action === 'click') {
            el.click();
          } else if (action === 'type') {
            el.focus();
            setNativeValue(el, $v);
          } else if (action === 'select') {
            setNativeValue(el, $v);
          } else if (action === 'submit') {
            if (el.form) { el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit(); }
            else { el.click(); }
          } else {
            return JSON.stringify({ ok: false, error: 'unsupported action ' + action });
          }
          return JSON.stringify({ ok: true });
        })()
        """.trimIndent()
    }

    /**
     * Is [text] anywhere in the rendered document?
     *
     * Used as the cheap probe for both `find` (before deciding to scroll a lazily
     * loaded page) and `wait_for` (polled until the content arrives). Deliberately
     * separate from extraction: this must stay cheap enough to call in a loop.
     */
    /**
     * Find the page's dominant repeated structure and return each block.
     *
     * The heuristic, and why it is this one: a listing page is built by a loop, so its
     * items are siblings sharing a tag and class signature. Grouping siblings by that
     * signature and taking the largest group finds "the list" without knowing anything
     * about the site.
     *
     * Scoring is count times median text length, NOT count alone. Navigation bars, footer
     * link rows and tag chips are also large sibling groups — they lose because their items
     * are two words each, while product cards carry a title, a price and a rating.
     */
    fun extractRows(maxRows: Int): String = """
    (function() {
      function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }

      var groups = {};
      var all = document.body ? document.body.getElementsByTagName('*') : [];
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        var parent = el.parentElement;
        if (!parent) continue;
        var cls = (typeof el.className === 'string' ? el.className : '').trim().split(/\s+/).sort().join('.');
        var sig = (parent.__auraKey || (parent.__auraKey = 'p' + i)) + '|' + el.tagName + '|' + cls;
        (groups[sig] = groups[sig] || []).push(el);
      }

      var best = null, bestScore = 0;
      for (var key in groups) {
        var items = groups[key];
        if (items.length < 3) continue;

        var lens = [];
        for (var j = 0; j < items.length; j++) lens.push(clean(items[j].innerText).length);
        lens.sort(function(a, b) { return a - b; });
        var median = lens[Math.floor(lens.length / 2)];
        if (median < 12) continue;

        var score = items.length * median;
        if (score > bestScore) { bestScore = score; best = items; }
      }

      if (!best) return JSON.stringify({ ok: true, rows: [] });

      var rows = [];
      for (var k = 0; k < best.length && rows.length < $maxRows; k++) {
        var node = best[k];
        var text = clean(node.innerText);
        if (!text) continue;
        var a = node.tagName === 'A' ? node : node.querySelector('a[href]');
        rows.push({ text: text, link: a ? a.href : null });
      }
      return JSON.stringify({ ok: true, rows: rows, total: best.length });
    })();
    """.trimIndent()

    fun containsText(text: String): String = """
    (function() {
      var needle = ${jsString(text.lowercase())};
      var body = document.body ? (document.body.innerText || '') : '';
      var found = body.toLowerCase().indexOf(needle) >= 0;
      if (!found) {
        // Controls carry their name in attributes rather than innerText, and those
        // are exactly what an agent searches for ("Apply", "Add to cart").
        var nodes = document.querySelectorAll('a, button, input, textarea, select, [role]');
        for (var i = 0; i < nodes.length && !found; i++) {
          var n = nodes[i];
          var hay = ((n.getAttribute('aria-label') || '') + ' ' +
                     (n.getAttribute('placeholder') || '') + ' ' +
                     (n.getAttribute('title') || '') + ' ' +
                     (n.value || '')).toLowerCase();
          if (hay.indexOf(needle) >= 0) found = true;
        }
      }
      return JSON.stringify({ ok: true, found: found });
    })()
    """.trimIndent()

    /**
     * Locate [text] among the **already-stamped** interactive elements and scroll it
     * into view, returning its selector.
     *
     * Must run after [EXTRACT], which is what applies the `data-aura-el` stamps. A
     * text-only match (a heading, a price in a paragraph) scrolls into view but
     * returns no selector — found, but nothing to click, which is a genuinely
     * different answer and worth telling the agent apart from "not found".
     */
    fun locate(text: String): String = """
    (function() {
      var needle = ${jsString(text.lowercase())};

      function labelOf(el) {
        return ((el.getAttribute('aria-label') || '') + ' ' +
                (el.innerText || '') + ' ' +
                (el.getAttribute('placeholder') || '') + ' ' +
                (el.getAttribute('title') || '') + ' ' +
                (el.value || '')).toLowerCase();
      }

      var stamped = document.querySelectorAll('[data-aura-el]');
      var exact = null, partial = null;
      for (var i = 0; i < stamped.length; i++) {
        var lbl = labelOf(stamped[i]).trim();
        if (lbl === needle) { exact = stamped[i]; break; }
        // Prefer the SHORTEST partial match: on a page full of "Add to cart",
        // the tightest label is the most likely intended target rather than some
        // huge container that happens to contain the words.
        if (!partial && lbl.indexOf(needle) >= 0) partial = stamped[i];
        else if (partial && lbl.indexOf(needle) >= 0 &&
                 lbl.length < labelOf(partial).trim().length) partial = stamped[i];
      }

      var hit = exact || partial;
      if (hit) {
        try { hit.scrollIntoView({ block: 'center' }); } catch (e) {}
        return JSON.stringify({
          ok: true,
          selector: '[data-aura-el="' + hit.getAttribute('data-aura-el') + '"]',
          interactive: true
        });
      }

      // Text-only fallback: scroll it into view so a screenshot shows it, but there
      // is nothing to act on.
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
      var node;
      while ((node = walker.nextNode())) {
        if ((node.nodeValue || '').toLowerCase().indexOf(needle) >= 0) {
          var parent = node.parentElement;
          if (parent) { try { parent.scrollIntoView({ block: 'center' }); } catch (e) {} }
          return JSON.stringify({ ok: true, selector: null, interactive: false });
        }
      }

      return JSON.stringify({ ok: false, error: 'not found' });
    })()
    """.trimIndent()

    /**
     * Resolve [selector]'s on-screen centre, in CSS pixels, for a *native* touch.
     *
     * Exists solely for `browser_upload`. Chromium refuses to open a file chooser in
     * response to a script-originated `el.click()` — "File chooser dialog can only be
     * shown with a user activation" — so the input needs a real [android.view.MotionEvent]
     * through the WebView's own input pipeline instead of a JS click. This resolves
     * *where* to tap; the tap itself is dispatched natively.
     */
    fun elementRect(selector: String): String {
        val sel = jsString(selector)
        return """
        (function() {
          var el = document.querySelector($sel);
          if (!el) return JSON.stringify({ ok: false, error: 'element no longer on the page' });
          try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
          var r = el.getBoundingClientRect();
          if (r.width <= 0 || r.height <= 0) {
            return JSON.stringify({ ok: false, error: 'element has no visible area' });
          }
          return JSON.stringify({ ok: true, x: r.left + r.width / 2, y: r.top + r.height / 2 });
        })()
        """.trimIndent()
    }

    /** Page-level scroll, expressed as a fraction of the viewport. */
    fun scrollBy(fraction: Double): String =
        "(function(){ window.scrollBy(0, window.innerHeight * $fraction); " +
            "return JSON.stringify({ ok: true }); })()"

    /**
     * Minimal JS string literal escaping. Selectors are ours, but `value` is
     * model-supplied text that lands inside a script — quote it properly rather
     * than trusting it not to contain a quote or a newline.
     */
    private fun jsString(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            // U+2028/U+2029 terminate a line in JavaScript but are legal raw inside
            // a JSON string, so text carrying them would break the injected literal.
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        return "\"${escaped}\""
    }
}
