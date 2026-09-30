package com.aura.aura_ui.mcp.bridge

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aura.mcp.bridge.BrowserAction
import com.aura.mcp.bridge.BrowserErrorKind
import com.aura.mcp.bridge.BrowserResult
import com.aura.mcp.bridge.BrowserSessionMode
import com.aura.mcp.bridge.BrowserTabAction
import com.aura.mcp.bridge.HandoffEnd
import com.aura.mcp.bridge.RawElement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The JVM tests cover the projection from a scraped page to the model's view. They
 * cannot tell us whether the *engine* produces a scraped page at all — the JS blob
 * has to actually parse and run inside a real WebView for any of it to matter.
 *
 * The specific risk this exists to disprove: an offscreen [android.webkit.WebView]
 * that was never measured or laid out has no viewport, and the extraction script
 * rejects any element whose `getBoundingClientRect()` is 0×0. If layout never
 * happens, every page returns zero elements — a total failure that compiles
 * perfectly and passes every unit test.
 */
@RunWith(AndroidJUnit4::class)
class AppBrowserBridgeInstrumentedTest {

    private lateinit var bridge: AppBrowserBridge

    // NOTE: '#' must be percent-encoded. A raw '#' in a data: URL starts the
    // fragment, silently truncating the document mid-tag — which is exactly how
    // this fixture first "proved" the engine extracted zero elements.
    private val fixture = "data:text/html," +
        "<html><body>" +
        "<h1>Fixture heading</h1>" +
        "<p>Some body prose that should come back as page text.</p>" +
        "<a href='%23one'>First link</a>" +
        "<button id='b'>Press me</button>" +
        "<input type='text' name='q' placeholder='Search box'>" +
        "<button disabled>Disabled button</button>" +
        "</body></html>"

    // Target sits 4000px below the fold. It is still in the DOM the whole time —
    // that is precisely the property `find` relies on.
    private val tallFixture = "data:text/html," +
        "<html><body style='margin:0'>" +
        "<div style='height:4000px'>filler above the fold</div>" +
        "<button id='deep'>Add to cart</button>" +
        "</body></html>"

    // Swaps its own text at 1500ms — after open() settles at ~600ms.
    private val deferredFixture = "data:text/html," +
        "<html><body>" +
        "<div id='slot'>loading spinner</div>" +
        "<script>setTimeout(function(){" +
        "document.getElementById('slot').textContent='Results ready';" +
        "},1500);</script>" +
        "</body></html>"

    // Red page, black block: an all-white OR all-transparent capture both fail the
    // distinct-colour check, which a plain text page would not catch.
    private val paintedFixture = "data:text/html," +
        "<html><body style='margin:0;background:red'>" +
        "<div style='width:200px;height:200px;background:black'></div>" +
        "<h1 style='color:white'>Screenshot fixture</h1>" +
        "</body></html>"

    // What a failed hardware-accelerated draw looks like: one flat colour, nothing else.
    private val blankFixture = "data:text/html," +
        "<html><body style='margin:0;background:white'></body></html>"

    // A nav bar (5 short one-word links, all siblings under <nav>) plus a product-card
    // list (3 siblings under <body>, each carrying a name/price/rating sentence). The
    // extract heuristic scores count × median text length, so the nav's larger sibling
    // count must still lose to the cards' longer text — that is the exact claim under test.
    private val productListFixture = "data:text/html," +
        "<html><body>" +
        "<nav>" +
        "<a href='%231'>Home</a>" +
        "<a href='%232'>Shop</a>" +
        "<a href='%233'>About</a>" +
        "<a href='%234'>Blog</a>" +
        "<a href='%235'>Help</a>" +
        "</nav>" +
        "<div class='card'>Widget Pro - $19.99 - 4.5 stars</div>" +
        "<div class='card'>Gadget Max - $29.99 - 4.2 stars</div>" +
        "<div class='card'>Thing Ultra - $9.99 - 3.8 stars</div>" +
        "</body></html>"

    // Two distinguishable pages for tab-isolation tests — each carries a marker only it has.
    private val pageAFixture = "data:text/html," +
        "<html><body><h1>Page A</h1><p>This is page A content.</p></body></html>"
    private val pageBFixture = "data:text/html," +
        "<html><body><h1>Page B</h1><p>This is page B content.</p></body></html>"

    // A file input whose onchange handler writes the picked filename into visible DOM
    // text, so the test can assert the chooser callback actually delivered a file
    // rather than just that the call returned success.
    private val uploadFormFixture = "data:text/html," +
        "<html><body>" +
        "<input type='file' id='resume'>" +
        "<div id='result'></div>" +
        "<script>" +
        "document.getElementById('resume').onchange=function(e){" +
        "document.getElementById('result').textContent=e.target.files[0].name;" +
        "};" +
        "</script>" +
        "</body></html>"

    // A login wall by the only definition that matters: `looksLikeLoginWall` keys on an
    // element with role `password`, not on wording. The text field alongside it is what
    // proves adoption-without-reload — a password field's value is deliberately never
    // reported by the page projection (it must not exfiltrate a typed secret), so it
    // structurally cannot serve as the survives-the-handoff witness.
    private val loginFixture = "data:text/html," +
        "<html><body>" +
        "<h1>Sign in</h1>" +
        "<form id='f'>" +
        "<input type='text' id='user' placeholder='Username'>" +
        "<input type='password' id='pass' placeholder='Password'>" +
        "</form>" +
        "<button id='finish'>Simulate login complete</button>" +
        "<script>" +
        "document.getElementById('finish').onclick=function(){" +
        "document.getElementById('f').remove();" +
        "document.body.insertAdjacentHTML('beforeend','<p>Welcome back, you are signed in.</p>');" +
        "};" +
        "</script>" +
        "</body></html>"

    // No password field at any point — `loginFormGone` is true from the first read, which
    // isolates the *other* two auto-resume conditions (quiet, frontmost).
    private val noLoginFixture = "data:text/html," +
        "<html><body>" +
        "<h1>Already signed in</h1>" +
        "<p>Your dashboard is ready and there is nothing to log into here.</p>" +
        "<input type='file' id='attach'>" +
        "</body></html>"

    @Before
    fun setUp() {
        bridge = AppBrowserBridge(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() = runBlocking {
        bridge.close(BrowserSessionMode.SCRATCH)
        Unit
    }

    private fun openFixture() = runBlocking {
        bridge.open(fixture, BrowserSessionMode.SCRATCH)
    }

    /**
     * Writes a real file through MediaStore and returns its `content://` URI plus the
     * DISPLAY_NAME MediaStore actually assigned — the same shape `find_files` hands the
     * agent in production. A `file://` URI into the app's own cache dir was tried first
     * and silently failed: WebView's renderer runs in a separate sandboxed process
     * (`com.google.android.webview`) that cannot read another app's private files, so
     * `onReceiveValue` delivered nothing and the test could not tell "the round trip is
     * broken" from "the test fixture is unrealistic".
     *
     * The name is read back rather than assumed: MediaStore silently renames on a
     * collision with a prior run's leftover ("resume.txt" -> "resume (1).txt"), so
     * asserting against the requested name is flaky across repeated runs.
     */
    private fun writeTestFile(name: String, content: String): Pair<String, String> {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed for $name")
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        val actualName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: name
        return uri.toString() to actualName
    }

    @Test
    fun offscreenWebViewLaysOutAndYieldsInteractiveElements() {
        val result = openFixture()

        assertTrue("open failed: $result", result is BrowserResult.Success)
        val page = (result as BrowserResult.Success).value

        // THE assertion. Zero here means the offscreen WebView never laid out and
        // every element was filtered as 0x0 — the silent total failure.
        assertTrue(
            "no interactive elements extracted — offscreen WebView probably has no viewport",
            page.elements.isNotEmpty(),
        )
        assertEquals(4, page.elements.size)
    }

    @Test
    fun pageTextAndTitleSurvive() {
        val page = (openFixture() as BrowserResult.Success).value

        assertTrue("page text was empty: '${page.text}'", page.text.contains("body prose"))
    }

    @Test
    fun rolesAndLabelsAreResolved() {
        val page = (openFixture() as BrowserResult.Success).value

        assertTrue(page.elements.any { it.role == "link" && it.label == "First link" })
        assertTrue(page.elements.any { it.role == "button" && it.label == "Press me" })
        // Placeholder is the only name an unlabelled input has.
        assertTrue(page.elements.any { it.role == "input" && it.label == "Search box" })
    }

    @Test
    fun disabledControlsAreTaggedNotDropped() {
        val page = (openFixture() as BrowserResult.Success).value

        val disabled = page.elements.filter { it.disabled }
        assertEquals(1, disabled.size)
        assertEquals("Disabled button", disabled.first().label)
    }

    @Test
    fun typingReachesTheDomAndComesBackOnTheNextRead() = runBlocking {
        val page = (openFixture() as BrowserResult.Success).value
        val input = page.elements.first { it.role == "input" }

        val after = bridge.act(
            BrowserSessionMode.SCRATCH,
            input.selector,
            BrowserAction.TYPE,
            "hello world",
        )

        assertTrue("act failed: $after", after is BrowserResult.Success)
        val typed = (after as BrowserResult.Success).value.elements.first { it.role == "input" }
        assertEquals("hello world", typed.value)
    }

    @Test
    fun actingOnAVanishedElementFailsLoudlyInsteadOfSilently() = runBlocking {
        openFixture()

        val result = bridge.act(
            BrowserSessionMode.SCRATCH,
            "[data-aura-el=\"999\"]",
            BrowserAction.CLICK,
            null,
        )

        assertTrue(result is BrowserResult.Failure)
    }

    @Test
    fun readBeforeOpenReportsNoPage() = runBlocking {
        val fresh = AppBrowserBridge(InstrumentationRegistry.getInstrumentation().targetContext)

        val result = fresh.read(BrowserSessionMode.SCRATCH)

        assertTrue(result is BrowserResult.Failure)
    }

    // ── find ───────────────────────────────────────────────────────────

    /**
     * The design claims finding on a web page is a *query*, not a *search*: the DOM
     * holds the whole document regardless of scroll position, so text 4000px below
     * the fold is already there. If this ever needs scrolling, the claim is wrong and
     * `browser_find` costs N round trips instead of one.
     */
    @Test
    fun findLocatesTextBelowTheFoldWithoutScrolling() = runBlocking {
        bridge.open(tallFixture, BrowserSessionMode.SCRATCH)

        val result = bridge.find(BrowserSessionMode.SCRATCH, "Add to cart")

        assertTrue("find failed: $result", result is BrowserResult.Success)
        val outcome = (result as BrowserResult.Success).value
        assertTrue("no selector for on-page text", outcome.matchedSelector != null)
        assertTrue("the DOM already held it; scrolling means the query claim is false", !outcome.requiredScrolling)
    }

    @Test
    fun findReportsAMissWithoutFailingTheCall() = runBlocking {
        bridge.open(tallFixture, BrowserSessionMode.SCRATCH)

        val result = bridge.find(BrowserSessionMode.SCRATCH, "Nowhere on this page")

        // A miss is information, not an error — the agent still gets the page back.
        assertTrue(result is BrowserResult.Success)
        assertEquals(null, (result as BrowserResult.Success).value.matchedSelector)
    }

    // ── waitFor ────────────────────────────────────────────────────────

    /**
     * The whole point of `browser_wait`: content that arrives *after* load. `open`
     * settles at ~600ms and this fixture swaps its text at 1500ms, so a passing read
     * here can only have come from polling.
     */
    @Test
    fun waitForSeesContentThatArrivesAfterLoad() = runBlocking {
        val opened = bridge.open(deferredFixture, BrowserSessionMode.SCRATCH)
        val atLoad = (opened as BrowserResult.Success).value
        assertTrue("fixture already settled; the test proves nothing", !atLoad.text.contains("Results ready"))

        val result = bridge.waitFor(BrowserSessionMode.SCRATCH, "Results ready", 8_000L)

        assertTrue("waitFor failed: $result", result is BrowserResult.Success)
        assertTrue((result as BrowserResult.Success).value.text.contains("Results ready"))
    }

    @Test
    fun waitForTimesOutLoudlyRatherThanReturningAStalePage() = runBlocking {
        bridge.open(deferredFixture, BrowserSessionMode.SCRATCH)

        val result = bridge.waitFor(BrowserSessionMode.SCRATCH, "Never going to appear", 1_000L)

        assertTrue("a timeout must not read as success: $result", result is BrowserResult.Failure)
        assertEquals(BrowserErrorKind.TIMEOUT, (result as BrowserResult.Failure).kind)
    }

    // ── screenshot ─────────────────────────────────────────────────────

    /**
     * The risk flagged in the design doc: a hardware-accelerated WebView can draw
     * **blank** into a software canvas. That failure is invisible to every check the
     * engine currently makes — it only tests `encoded.isBlank()`, which is about the
     * *string* being empty, and an all-white PNG base64-encodes to a long, healthy
     * looking string. A VLM handed that image reads "empty page" and the agent
     * confidently acts on nothing.
     *
     * So this decodes the PNG and asserts the pixels actually differ. The fixture is
     * deliberately red-on-black so "all white" and "all transparent" both fail.
     */
    @Test
    fun screenshotCapturesRealPixelsAndNotABlankCanvas() = runBlocking {
        bridge.open(paintedFixture, BrowserSessionMode.SCRATCH)

        val result = bridge.screenshot(BrowserSessionMode.SCRATCH)
        assertTrue("screenshot failed: $result", result is BrowserResult.Success)

        val capture = (result as BrowserResult.Success).value
        assertTrue("engine flagged a painted page as blank", !capture.uniform)

        val bytes = Base64.decode(capture.pngBase64, Base64.NO_WRAP)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertTrue("PNG did not decode — the payload is not an image", bitmap != null)
        requireNotNull(bitmap)

        val colours = mutableSetOf<Int>()
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                colours.add(bitmap.getPixel(x, y))
                y += stepY
            }
            x += stepX
        }

        assertTrue(
            "every sampled pixel was the same colour (${colours.firstOrNull()}) — " +
                "the WebView drew blank into the software canvas",
            colours.size > 1,
        )
    }

    /**
     * The other half. A guard that never fires is indistinguishable from no guard —
     * which is precisely how the previous `encoded.isBlank()` check survived: it was
     * never tested against anything blank.
     *
     * An empty white page is what a failed hardware-accelerated draw looks like, so
     * this is the closest honest simulation available without a device that actually
     * exhibits the bug. Note it must still come back as Success: a blank page is a
     * legitimate page, and refusing it would misreport a working engine as broken.
     */
    @Test
    fun aFlatColourPageIsFlaggedUniformButStillReturned() = runBlocking {
        bridge.open(blankFixture, BrowserSessionMode.SCRATCH)

        val result = bridge.screenshot(BrowserSessionMode.SCRATCH)

        assertTrue("a blank page is legitimate; it must not be an error: $result", result is BrowserResult.Success)
        assertTrue(
            "flat white page was not flagged — the blank guard does not fire",
            (result as BrowserResult.Success).value.uniform,
        )
    }

    // ── extract ────────────────────────────────────────────────────────

    /**
     * The heuristic (`count × median text length`) has only ever run against hand-fed
     * JSON in JVM tests. On a real DOM the tempting wrong answer is the nav bar: it has
     * *more* siblings (5) than the card list (3), so a heuristic that scored on count
     * alone would pick it. The median-length term exists precisely to reject that —
     * this proves it does so against real `getBoundingClientRect`/`innerText` values,
     * not a fixture built to already look right.
     */
    @Test
    fun extractFindsTheDominantRepeatedBlockNotTheNavBar() = runBlocking {
        bridge.open(productListFixture, BrowserSessionMode.SCRATCH)

        val result = bridge.extract(BrowserSessionMode.SCRATCH, fields = emptyList())

        assertTrue("extract failed: $result", result is BrowserResult.Success)
        val rows = (result as BrowserResult.Success).value
        assertEquals("expected the 3 product cards, not the 5 nav links", 3, rows.size)
        assertTrue(
            "rows should be the price-bearing cards, not nav text",
            rows.all { it.text.contains("$") },
        )
    }

    // ── tabs ───────────────────────────────────────────────────────────

    /**
     * Each tab owns a real, independent offscreen WebView. The risk this disproves:
     * that `layOutOffscreen()` — called on every SWITCH to re-establish the viewport —
     * actually re-lays-out the tab being switched *into*, rather than leaving stale
     * state from whichever tab was touched last (the same class of bug as the original
     * 0×0 extraction hole, now per-tab).
     */
    @Test
    fun switchingTabsPreservesEachTabsOwnPageNotTheLastOne() = runBlocking {
        bridge.open(pageAFixture, BrowserSessionMode.SCRATCH)

        val opened = bridge.tabs(BrowserSessionMode.SCRATCH, BrowserTabAction.OPEN, url = pageBFixture)
        assertTrue("opening tab 2 failed: $opened", opened is BrowserResult.Success)
        assertTrue(
            "tab 2's own content should come back on open",
            (opened as BrowserResult.Success).value.page?.text?.contains("Page B") == true,
        )

        val switchBack = bridge.tabs(BrowserSessionMode.SCRATCH, BrowserTabAction.SWITCH, index = 0)
        assertTrue("switch to tab 1 failed: $switchBack", switchBack is BrowserResult.Success)
        assertTrue(
            "tab 1 lost its own content after switching away and back",
            (switchBack as BrowserResult.Success).value.page?.text?.contains("Page A") == true,
        )

        val switchForward = bridge.tabs(BrowserSessionMode.SCRATCH, BrowserTabAction.SWITCH, index = 1)
        assertTrue("switch to tab 2 failed: $switchForward", switchForward is BrowserResult.Success)
        assertTrue(
            "tab 2 lost its own content after switching away from it and back",
            (switchForward as BrowserResult.Success).value.page?.text?.contains("Page B") == true,
        )
    }

    /**
     * `MAX_TABS = 4` is a deliberate refusal, not a silent drop — opening past the cap
     * must neither crash nor discard the oldest tab out from under the agent.
     */
    @Test
    fun openingPastMaxTabsIsRefusedNotSilentlyDropped() = runBlocking {
        bridge.open(pageAFixture, BrowserSessionMode.SCRATCH) // tab 0
        bridge.tabs(BrowserSessionMode.SCRATCH, BrowserTabAction.OPEN, url = pageBFixture) // tab 1
        bridge.tabs(BrowserSessionMode.SCRATCH, BrowserTabAction.OPEN, url = pageAFixture) // tab 2
        bridge.tabs(BrowserSessionMode.SCRATCH, BrowserTabAction.OPEN, url = pageBFixture) // tab 3 (at cap)

        val fifth = bridge.tabs(BrowserSessionMode.SCRATCH, BrowserTabAction.OPEN, url = pageAFixture)

        assertTrue(
            "a 5th tab must be refused, not silently dropped or crashed: $fifth",
            fifth is BrowserResult.Failure,
        )
        assertEquals(BrowserErrorKind.BAD_REQUEST, (fifth as BrowserResult.Failure).kind)

        val list = bridge.tabs(BrowserSessionMode.SCRATCH, BrowserTabAction.LIST)
        assertTrue(list is BrowserResult.Success)
        assertEquals(
            "the refused 5th open must not have been appended to the tab list",
            4,
            (list as BrowserResult.Success).value.tabs.size,
        )
    }

    // ── upload ─────────────────────────────────────────────────────────

    /**
     * The ~30 lines the design doc flags as never having run on a device:
     * `PendingUpload.arm()` → click → `onShowFileChooser` → `consume()` →
     * `callback.onReceiveValue(...)`. Asserting on `page.text` (not just a Success
     * result) proves the *page* actually received the file object through Android's
     * real chooser API, not merely that the call didn't throw.
     */
    @Test
    fun uploadArmsAndDeliversARealFileThroughTheChooserCallback() = runBlocking {
        bridge.open(uploadFormFixture, BrowserSessionMode.SCRATCH)
        val (fileUri, actualName) = writeTestFile("resume.txt", "test resume content")

        val result = bridge.upload(BrowserSessionMode.SCRATCH, "#resume", fileUri)

        assertTrue("upload failed: $result", result is BrowserResult.Success)
        val page = (result as BrowserResult.Success).value
        assertTrue(
            "chooser callback never delivered the file — page never saw the filename " +
                "(expected '$actualName' in: '${page.text}')",
            page.text.contains(actualName),
        )
    }

    // ── handoff + the visible window ───────────────────────────────────

    /**
     * The handoff window is a real `TYPE_APPLICATION_OVERLAY`, so without this permission
     * every handoff test fails inside `attach()` and proves nothing about the handoff.
     *
     * The shell grant below works on stock Android and on emulators. **It does not work on
     * ColorOS** (CPH2661, the device this suite is verified on), where `appops set` from the
     * shell uid is refused for lack of `MANAGE_APP_OPS_MODES` — an OEM restriction, not a
     * missing flag, so there is no variant of this command that succeeds there.
     *
     * Hence the explicit check and the loud message rather than a silent skip: on a device
     * where it cannot self-grant, a handoff test that quietly passed would be worse than no
     * test at all. See the design doc's Chunk 3 section for the `am instrument` workflow that
     * keeps a hand-granted permission alive (`connectedAndroidTest` uninstalls both APKs when
     * it finishes, which resets it).
     */
    private fun grantOverlayPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
                .use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() } }
        }
        check(Settings.canDrawOverlays(context)) {
            "The browser handoff needs 'display over other apps' and this device refuses to " +
                "grant it from the shell (ColorOS). Grant it by hand for " +
                "${context.packageName}, then run this class with `adb shell am instrument` " +
                "rather than `connectedAndroidTest`, which uninstalls the app and drops the grant."
        }
    }

    /**
     * Find an element the way the agent has to: by [role] and visible [label], never by the
     * author's `#id`.
     *
     * `RawElement.selector` is an **engine-minted handle** (`[data-aura-el="7"]`), not the
     * CSS the fixture was written with — that is the whole `el_id` discipline, so that a page
     * can change its markup without changing the model's vocabulary. A test that addresses
     * `#user` is testing a vocabulary the engine deliberately does not speak.
     */
    private suspend fun element(role: String, label: String? = null): RawElement {
        val page = (bridge.read(BrowserSessionMode.SCRATCH) as? BrowserResult.Success)?.value
            ?: error("no page open")
        return page.elements.firstOrNull { el ->
            el.role == role && (label == null || el.label.contains(label, ignoreCase = true))
        } ?: error(
            "no '$role' element${label?.let { " labelled '$it'" } ?: ""} on the page — saw: " +
                page.elements.joinToString { "${it.role}/'${it.label}'" },
        )
    }

    /**
     * The single most important claim in Phase 2: **the window adopts the live tab, it does
     * not build a new one.** A second WebView loading the same URL would look identical in a
     * screenshot and would silently discard the session, the scroll position, and — the case
     * that actually matters — whatever the user had already typed. A reload mid-login throwing
     * away half-entered credentials is the one failure a login handoff must never have.
     *
     * Typing first and asserting the value *after* the window is up is what separates
     * "re-parented" from "recreated". Both render; only one remembers.
     */
    @Test
    fun handoffAdoptsTheLiveTabRatherThanReloadingIt() = runBlocking {
        grantOverlayPermission()
        bridge.open(loginFixture, BrowserSessionMode.SCRATCH)
        val user = element("input", "Username")
        bridge.act(BrowserSessionMode.SCRATCH, user.selector, BrowserAction.TYPE, "half-typed-name")
        assertEquals(
            "precondition: the value never reached the field, so the real assertion is void",
            "half-typed-name",
            element("input", "Username").value,
        )

        val handed = bridge.handoff(BrowserSessionMode.SCRATCH, "Please sign in")
        assertTrue("handoff failed: $handed", handed is BrowserResult.Success)

        assertEquals(
            "the window rebuilt the page instead of adopting it — a real login would have " +
                "lost its half-typed credentials here",
            "half-typed-name",
            element("input", "Username").value,
        )
    }

    /**
     * Condition 1 of three, in the direction that is dangerous to get wrong. Resuming while
     * the password field is still on screen means the agent starts clicking a page the user
     * is still typing into.
     *
     * The other two conditions are deliberately satisfied here — the window is untouched
     * (`msSinceLastTouch` is `MAX_VALUE`) and frontmost — so a resume could only come from
     * the form check failing open.
     */
    @Test
    fun autoResumeDoesNotFireWhileTheLoginFormIsStillUp() = runBlocking {
        grantOverlayPermission()
        bridge.open(loginFixture, BrowserSessionMode.SCRATCH)
        bridge.handoff(BrowserSessionMode.SCRATCH, "Please sign in")

        val state = bridge.handoffState(BrowserSessionMode.SCRATCH)

        assertTrue("handoffState failed: $state", state is BrowserResult.Success)
        assertTrue(
            "auto-resume fired with the password field still on the page — the agent would " +
                "be clicking while the user types",
            (state as BrowserResult.Success).value.handedOff,
        )
    }

    /**
     * The same three conditions in the other direction. A handoff that can begin but never
     * end is worse than no handoff: every login would strand the agent until the user found
     * the pill. Uses the *disappearance* of the password field, which is exactly the signal
     * `looksLikeLoginWall` was factored onto the port to provide.
     */
    @Test
    fun autoResumeFiresOnceTheFormIsGoneAndTheUserIsStill() = runBlocking {
        grantOverlayPermission()
        bridge.open(loginFixture, BrowserSessionMode.SCRATCH)
        bridge.handoff(BrowserSessionMode.SCRATCH, "Please sign in")

        // Stands in for the user completing the login: the form leaves the DOM.
        val finish = element("button", "Simulate login complete")
        bridge.act(BrowserSessionMode.SCRATCH, finish.selector, BrowserAction.CLICK, null)

        val state = bridge.handoffState(BrowserSessionMode.SCRATCH)

        assertTrue("handoffState failed: $state", state is BrowserResult.Success)
        val value = (state as BrowserResult.Success).value
        assertTrue("the handoff never ended once the login form was gone", !value.handedOff)
        assertEquals(HandoffEnd.AUTO, value.endedBy)
    }

    /**
     * Three heuristics over a human being, with no manual override, is a trap — so the
     * override must work in the case the heuristics can *never* resolve. The login form is
     * still up here, so auto-resume is structurally impossible; only the button can end it.
     */
    @Test
    fun theImDoneButtonEndsAHandoffThatAutoResumeNeverCould() = runBlocking {
        grantOverlayPermission()
        bridge.open(loginFixture, BrowserSessionMode.SCRATCH)
        bridge.handoff(BrowserSessionMode.SCRATCH, "Please sign in")

        assertTrue(
            "precondition: auto-resume should be impossible with the form still up",
            (bridge.handoffState(BrowserSessionMode.SCRATCH) as BrowserResult.Success)
                .value.handedOff,
        )

        tapImDoneButton()

        val state = bridge.handoffState(BrowserSessionMode.SCRATCH)
        val value = (state as BrowserResult.Success).value
        assertTrue("the manual override did not end the handoff", !value.handedOff)
        assertEquals(HandoffEnd.USER_DONE, value.endedBy)
    }

    /**
     * The cross-feature seam, and this workstream's entire failure history is cross-feature
     * seams that each passed their own tests.
     *
     * `browser_upload` now dispatches a **synthetic [MotionEvent]** into the WebView, because
     * Chromium refuses to open a file chooser for a script-originated click. The handoff
     * window separately counts touches to decide when the human has stopped. Those two facts
     * collide: if the upload's synthetic tap is counted as a human touch, an upload would
     * silently hold the handoff open for [BrowserHandoff.QUIET_MS] afterwards.
     *
     * It should be safe by construction — the tap goes straight to the WebView, while
     * `TouchReportingFrame` sits on the *container* above it — but "should be, by
     * construction" is precisely the claim that has been wrong five times here already.
     */
    @Test
    fun anUploadsSyntheticTapIsNotCountedAsTheHumanTakingTheWindow() = runBlocking {
        grantOverlayPermission()
        bridge.open(noLoginFixture, BrowserSessionMode.SCRATCH)
        val attach = element("input")
        bridge.handoff(BrowserSessionMode.SCRATCH, "Attach your file")
        val (fileUri, _) = writeTestFile("seam.txt", "cross-seam probe")

        bridge.upload(BrowserSessionMode.SCRATCH, attach.selector, fileUri)

        val state = bridge.handoffState(BrowserSessionMode.SCRATCH)
        val value = (state as BrowserResult.Success).value
        assertTrue(
            "the upload's synthetic MotionEvent registered as a human touch, so the handoff " +
                "stayed open — an agent-driven upload must not read as the user grabbing the " +
                "window",
            !value.handedOff,
        )
        assertEquals(HandoffEnd.AUTO, value.endedBy)
    }

    /**
     * `close()` during a handoff used to **permanently brick the browser plane**, and the
     * leaked overlay was the lesser half of it.
     *
     * `close()` destroyed every tab's WebView but never touched the window, so
     * `handoffPrompt` stayed non-null — `isHandedOff` stayed true and every write was
     * refused for the life of the process. Nothing could ever clear it either:
     * `handoffState` recovers the page through `active?.view`, `tabs` is empty after a
     * close, so `loginFormGone` is null forever and auto-resume correctly refuses to fire
     * on an unknown. The safety rule that makes the handoff trustworthy is exactly what
     * made this unrecoverable.
     *
     * Invisible to every existing test: five handoff tests end while handed off and then
     * call `close()` in teardown, and a leaked window fails no assertion.
     */
    @Test
    fun closingDuringAHandoffReleasesTheWindowInsteadOfStrandingIt() = runBlocking {
        grantOverlayPermission()
        bridge.open(loginFixture, BrowserSessionMode.SCRATCH)
        bridge.handoff(BrowserSessionMode.SCRATCH, "Please sign in")
        assertTrue("precondition: the handoff did not start", bridge.isHandedOff)

        bridge.close(BrowserSessionMode.SCRATCH)

        assertTrue(
            "close() left isHandedOff set — every write stays refused for the life of the " +
                "process and nothing remains that could clear it",
            !bridge.isHandedOff,
        )
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "the handoff window outlived the session it belonged to — an orphaned " +
                "full-screen overlay wrapping a destroyed WebView, with no owner left to " +
                "remove it",
            device.wait(Until.gone(By.text("I'm done — carry on")), 3_000),
        )
    }

    /**
     * Taps the window's escape hatch through the **real input pipeline** rather than by
     * invoking `onDone` directly. Calling the callback would prove only that the callback
     * works; it would say nothing about whether the button is reachable, on screen, and
     * receiving touches through an overlay window — which is the part that varies by OEM and
     * the part that has broken before on this device family.
     */
    private fun tapImDoneButton() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val button = device.wait(Until.findObject(By.text("I'm done — carry on")), 5_000)
            ?: error("the \"I'm done\" button was not on screen — the handoff window is not visible")
        button.click()
        device.waitForIdle()
    }

    @Test
    fun theUsersRealBrowserIsReportedUnavailableUntilSliceTwo() = runBlocking {
        // Must fail with a clear message rather than silently answering from the
        // scratch browser — a logged-out answer presented as a logged-in one is
        // the worst outcome available here.
        val result = bridge.open("https://example.com", BrowserSessionMode.MINE)

        assertTrue(result is BrowserResult.Failure)
    }
}
