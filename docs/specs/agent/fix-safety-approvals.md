# Spec: approvals that reach the user, and a simpler safety layer

> **Status:** in progress. **Written:** 2026-11-18. **Branch:** `feat/agentcore`.

## Goal

The user ran eval tasks 38 and 39 on the Part A APK (2026-11-17) and both stalled. From the
run logs:

- Opening a Settings screen was treated as sending data off the phone.
  `open_deeplink(uri = "android.settings.BATTERY_SAVER_SETTINGS")` came after a screen read,
  and the safety gate asked "AURA has read private content in this task. Open deeplink sends
  text off this phone. Allow it?". A settings action carries nothing anywhere.
  - **Root cause:** `ToolHub.HubTool.sendsOut` (`ToolHub.kt:916`) returns `spec.egress` for a
    call with no URL unless the tool is in `OPENS_LOCALLY`, and `open_deeplink` is in the
    hand-kept `PhoneToolSource.EGRESS_TOOLS` but not in `OPENS_LOCALLY`.
- The question never reached the user. It waited 120 s, the log said "You declined
  open_deeplink, or did not answer", and the model was told the user declined. This happened
  twice in task 38 and once in task 39.
  - These runs asked through `AskUserBroker.shared`, because `runTask` puts every overlay
    thread in `surfaceThreads` (`AgentCore.kt:392`). The overlay's `startHitlObserver` shows
    that broker's questions as the floating card.
  - The one question the user did answer (in 2.4 s) was asked while AURA's own screen was in
    front, where the in-app `VoiceAssistantOverlay` also draws it. Every unanswered one was
    asked while Settings was in front. So the floating card (`QuestionCardOverlay`) does not
    show over other apps.
  - The code does not explain why: the `OverlayCaptureGate` hide and restore are paired in a
    `finally`. It has to be reproduced on the phone.
- The run page looks wrong on time. The totals are right (task 38: 266 s on the eval clock,
  264 s in the log). But each row shows only its own work time, so "open_deeplink · 2m 0s" hides
  that those 2 minutes were a wait for the user, and nothing shows the time of day.

The user also wants the whole safety layer reviewed: "make it highly safe but the logic must be
simple, not over-engineered", and "stop hardcoding". Today, what is safe is decided by
hand-kept lists:
- `EGRESS_TOOLS` and `OPENS_LOCALLY` decide what sends data out.
- In `SensitivePolicy`, 22 package names and word fragments such as `"finance"`, `"wallet"` and
  `"citi"` decide what a money app is. They miss new banks and catch innocent apps: any package
  containing "cities".
- In `ForegroundGuard`, four tool-name lists decide what is refused while a money app is in
  front, and a new tool is allowed until someone adds it to a list.
- In `SafetyGate`, `APP_LINKS` decides which links a send approval covers.

## End product

On the Part A + fix APK, the user runs task 38. The agent opens Display and then Battery
settings with deep links, and no question appears. Later, in a WhatsApp task, a real "Send in
WhatsApp?" question comes up while WhatsApp is in front. The user sees the card at the top, a
notification with Allow and Deny buttons, and hears the question. Tapping Allow on either one
answers it. The run page shows `20:04:29 → 20:08:55 · 4m 26s`, each row with its time of day,
and a row "Waited 12s for your answer" where the card was up.

## Out of scope

- New safety features. This keeps what is protected and changes how it is decided. The one
  place it cannot keep coverage exactly is step 4b's open question.
- Commit words in other languages (`CommitLabels` stays English). That needs its own word lists.
- Part B's features (`feature-long-tasks.md`). Only the last step builds both APKs.
- `ActionGuard` in `:mcp-server`. It is the loop and grounding guard, not a safety rule.
- The approval mode text on the agent page (`AgentHubScreen.APPROVAL_CHOICES`). It is already
  one sentence per mode.

## Steps

Every step that changes `agentcore/` updates `agentcore/README.md` in the same commit. Another
session has uncommitted edits in `SafetyGate.kt`, `ToolHub.kt` and `ToolSources.kt`. Stage only
this spec's hunks there.

- [x] **1. Opening something on the phone is never a send-out.**
  - **What changes:**
    - `ToolHub.kt`: `sendOut(...)` (new): an `internal` pure function in the companion that
      `HubTool.sendsOut` calls; `OPENS_LOCALLY` is removed. The constructor parameter
      `opensInstalledApp` (`ToolHub.kt:335`) becomes `appFor: (url: String) -> InstalledApp?`.
    - `AgentCore.kt`: `opensInstalledApp` (`:177`) becomes `appFor(url): InstalledApp?`, the
      non-browser app a URL opens (label and package), or null. This is the one link resolver;
      steps 4b and 6 reuse it.
    - `ToolSources.kt`: `EGRESS_TOOLS` is removed, and `egress` becomes
      `tool.name == "web_search" || tool.name.startsWith("browser_")`.
    - `SafetyGate.blocked`: a denied contact is checked on every non-read call that names
      someone, not only on egress.
    - `ToolHubSendOutTest` (new); `agentcore/README.md` (safety rule 6).
  - **Logic:** `sendOut(egress, argsText, request, numbers, appFor)` is true when:
    - the tool fetches the web itself (`egress`, true only for `web_search`, `browser_*`, and
      `web_extract`, which already sets it); or
    - one of its string arguments is a URL that does not open in an installed app. An `intent:`
      link always counts, because it can name its own target. For any other URL, `appFor(url)`
      must name a non-browser app, and the link must pass the existing `dictatedOnly` check:
      its query holds only the user's own words.

    An Android action string (`android.settings.WIFI_SETTINGS`), a package name, a file or a
    share intent is not a URL, so it never counts. The app it opens needs its own Send, and that
    tap is a commit.

    The former `EGRESS_TOOLS`:

    | Tool | Before | After |
    |---|---|---|
    | `web_search` | flag | flag (unchanged) |
    | `open_deeplink` | flag | a URL in it counts; an action or package does not (the bug) |
    | `resolve_deeplink` | flag | only resolves, opens nothing; a URL in it counts |
    | `system_intent`, `open_file` | flag, but `OPENS_LOCALLY` cleared it | not a send-out (unchanged) |
    | `notification_action` | flag | its reply is a `COMMIT` and gets the commit card (unchanged) |

    Before, egress also made `SafetyGate.blocked` check denied contacts. Now `blocked` checks
    them on every call that is not a read (a `system_intent` SMS to a denied number is still
    refused).
  - **Done when:** `ToolHubSendOutTest` passes, calling `sendOut` directly:
    - `android.settings.BATTERY_SAVER_SETTINGS` gives false;
    - `https://evil.example/?q=hi` with `appFor` returning null gives true;
    - `https://wa.me/91…?text=<the user's words>` with `appFor` returning WhatsApp gives false;
    - `egress = true` (`web_search`) gives true.

    And `SafetyGateTest` passes one new case: a `system_intent` naming a denied contact's
    number is refused.

- [x] **2. Every question reaches the user, and silence is not a no.**
  - **Root cause (reproduced 2026-11-18, eval task 39 on the Part A APK, "About device" page in
    front):** the card's window was added and drawn, but `dumpsys window` showed it with
    `mForceHideNonSystemOverlayWindow=true`, `mPolicyVisibility=false`, `isOnScreen=false`.
    Sensitive Settings pages hide every `TYPE_APPLICATION_OVERLAY` window, which is what the card
    was. AURA's status strip stayed visible because it is a `TYPE_ACCESSIBILITY_OVERLAY` hosted by
    `AuraAccessibilityService`, and those are not hidden.
  - **What changes:**
    - `presentation/overlay/QuestionCardOverlay.kt`: `show` hosts the window on
      `AuraAccessibilityService.instance` as `TYPE_ACCESSIBILITY_OVERLAY` when the service is
      bound, as `AgentStatusOverlay` and `EdgeGlow` already do; else `TYPE_APPLICATION_OVERLAY`.
    - `overlay/QuestionNotification.kt` (new): the notification for a pending question.
    - `overlay/AuraOverlayService.kt`: `startHitlObserver` posts and removes it;
      `onStartCommand` handles `ACTION_ANSWER_QUESTION` (new) from its buttons.
    - `ToolHub.kt`: the gate card in `HubTool.gated` (phone and connector) calls `broker.ask`
      and treats `null` as no answer.
    - `SafetyGate.NO_ANSWER` (new): the model's message.
    - `ToolHubAskTest` (new), `agentcore/README.md`.
    - `UserBroker.confirm` keeps its `Boolean` for `memoryTools` and `skillTools`, where silence
      still means no.
  - **Logic:**
    - **The card** is an accessibility overlay, so a page that hides app overlays does not hide it.
    - **The notification.** If a question is still unanswered 10 s after it appeared, a
      high-priority notification shows it, with up to 3 of its options as action buttons
      (Allow / Deny for a gate card). A button sends `ACTION_ANSWER_QUESTION` to
      `AuraOverlayService`, which calls `AskUserBroker.answer`. It is removed when the question
      is answered or times out. Why 10 s: a heads-up notification would cover the card for a
      quick answer; the notification is the fallback for a card the user cannot see.
    - **Silence.** A gate card with no answer after 120 s writes the note "You did not answer
      within 2 minutes". The model gets `NO_ANSWER`: "The user did not answer. Nothing was sent
      or changed. Ask again later or tell the user what you need". It does not get `DECLINED`.
  - **Done when:**
    - `ToolHubAskTest` passes. It drives a commit `tap` through `ToolHub.registry` with a fake
      source, the same way `ToolHubSeenTest` does. With a broker that answers `null`, the result
      is `NO_ANSWER` and the source was not called. With "Deny", the result is `DECLINED`.
    - On the phone, task "Open About device in Settings, then ask me whether to open Wi-Fi or
      Bluetooth next, and open the one I pick": the card shows on top of the About-device page,
      and `dumpsys window` lists it as `ty=ACCESSIBILITY_OVERLAY`, `isOnScreen=true`. Left
      unanswered, the notification shows the two options after 10 s; tapping one opens that page.
      (The gate card's Allow path is the same broker and card, and `ToolHubAskTest` covers it.)
  - **Evidence (2026-11-19):** `ToolHubAskTest` 3/3 pass. On the phone, the task over About device
    showed the card as `ty=ACCESSIBILITY_OVERLAY`, `isOnScreen=true`; 15 s later no question
    notification was posted; the user tapped an option on the card and that page opened.
  - **Changed while building:** the notification is posted only when the card may be unseen
    (`QuestionCardOverlay.onTop` is false, or the screen is off or locked). On the phone the
    heads-up appeared behind the card: an accessibility overlay is above every notification layer,
    so the notification there only duplicated the card. The user chose this over hiding the card
    for the shade.
  - **Changed while building:** the spoken fallback was dropped. `VoiceLoop.reply` is a no-op
    without an open voice conversation, and an accessibility-overlay card can fail only when the
    accessibility service is off, when AURA cannot act at all.

- [ ] **3. The run log shows clock times and waits.**
  - **What changes:** `RunJournal.kt` (`RunLogBuilder`: each `ask` record becomes a `LogKind.WAIT`
    row (new)), `RunDetail.kt` (time of day on the header and each row), `RunLogTest`,
    `agentcore/README.md`. This replaces item 3.1 of `feature-reliable-agent.md`, which is
    marked so in the same commit.
  - **Logic:**
    - The header reads `20:04:29 → 20:08:55 · 4m 26s`, from the start record to the end of the
      last step.
    - Each row starts with its time of day, `20:05:53`, before today's `+1m 24s`.
    - **A wait is the gap after a question.** While a question is up the turn is blocked, so the
      next record of the same run in time order is written only once the user answered or the
      120 s ran out. An `ask` record becomes the row "Waited 12s for your answer: <question>",
      with that gap as its duration. This holds for the gate's cards, `propose_action` and
      `ask_user` alike, with no matching of note texts.
    - The tool row that waited shows its own work time without the wait: its duration minus the
      waits inside it, never below 0.
  - **Done when:** `RunLogTest` passes a new case. A tool record of 125 s holding an `ask` whose
    next record comes 120 s later gives a WAIT row of 120 s and a tool row of 5 s, and the
    header's start and end times match the records.

- [ ] **4a. Money and password apps come from Android.**
  - **What changes:**
    - `:mcp-server` `SensitivePolicy.kt`: `BLOCKED_FINANCIAL_PACKAGES`,
      `BANKING_PACKAGE_PATTERNS` and `BLOCKED_AUTH_PACKAGES` are removed.
      `var sensitiveApps: () -> Map<String, Category>` (new, package to category) takes their
      place, plus `AUTHENTICATORS`. `AUTHENTICATORS` is the one short list left, because Android
      has no signal for authenticator apps. `Category.BANKING_PATTERN` is removed.
    - `agentcore/SensitiveApps.kt` (new) builds the map; `AgentCore` sets it at start.
    - Tests: the `SensitivePolicy*` tests set a fake map (`SensitivePolicyForegroundTest:20`
      expects `FINANCIAL_APP` instead of `BANKING_PATTERN`); `SensitiveAppsTest` (new).
  - **Logic:** a package is sensitive when:
    - it handles `upi://pay` (`queryIntentActivities`): `FINANCIAL_APP`. That is every UPI
      payment and banking app;
    - it offers a tap-to-pay service (`HostApduService.SERVICE_INTERFACE`): `FINANCIAL_APP`;
    - it is a non-system autofill service (`AutofillService.SERVICE_INTERFACE`): `AUTH_APP`.
      That covers password managers;
    - it is in `AUTHENTICATORS`: `AUTH_APP`;
    - it is on the user's "Never" list (`AgentCore.deniedApps`, matched to launcher labels):
      `FINANCIAL_APP`.

    The map is rebuilt when older than 60 s, because apps install and uninstall while AURA runs.
    `QUERY_ALL_PACKAGES` is already granted.
  - **Done when:**
    - The `:mcp-server` `SensitivePolicy*` tests pass with a fake map: Google Pay
      (`com.google.android.apps.nbu.paisa.user`) is blocked, and `com.example.cities` is not.
    - `SensitiveAppsTest` (new, Robolectric) passes: a package registered for `upi://pay` and a
      third-party autofill service are in the map, and a system autofill service is not.

- [ ] **4b. Payment links and spoken requests use the same map.**
  - **What changes:** `SensitivePolicy.kt`: `BLOCKED_PAYMENT_SCHEMES`, `BANKING_HOST_PATTERNS` and
    `SPOKEN_SENSITIVE_APP_NAMES` are removed; `var linkPackage: (String) -> String?` (new), set
    in `AgentCore` to `{ appFor(it)?.packageName }`, step 1's resolver; `screenSpokenRequest`. Tests:
    `SensitivePolicyDeeplinkTest`, `SensitivePolicyShortcutUriTest`, `SensitivePolicyOutboundTest`.
  - **Logic:**
    - A deep link is blocked when the package it opens is sensitive. So `upi://pay?…` is blocked
      because its handler is a UPI app, with no scheme list.
    - A spoken request is blocked when it names the label of a sensitive app ("open Google Pay").
    - The text rules stay as they are: card numbers with Luhn, CVV, PIN, password, SSN and OTP
      sightings are patterns, not lists of apps.
    - Banks that handle neither UPI nor tap-to-pay (many banks outside India, investment apps) are
      covered by step 4c and the "Never" list.
  - **Done when:**
    - The tests pass: `upi://pay?pa=x`, with `linkPackage` returning a sensitive package, is blocked;
      "open google pay", with that label in the map, is refused.
    - On the phone: `launch_app("Google Pay")` is refused, and task 45 (Google Pay) is refused.

- [ ] **4c. An app whose screen refuses screenshots is treated as a money app.** (Chosen by the
  user, 2026-11-18, over a built-in bank list.)
  - **What changes:** `accessibility/AccessibilityScreenshotSource.kt` (`captureOnce`'s
    `onFailure`), `agentcore/SensitiveApps.kt` (a remembered set, prefs `agentcore_safety`, key
    `secure_apps` (new)), `SensitiveAppsTest`, `agentcore/README.md`.
  - **Logic:**
    - Banking apps mark their screens secure (`FLAG_SECURE`). An accessibility screenshot of such
      a screen fails with `AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`.
    - When that happens, the package in front is added to `secure_apps`, and `SensitiveApps`
      reports it as `FINANCIAL_APP` from then on, until the app is uninstalled.
    - It needs no list. It applies once AURA has tried to take a screenshot there (a tap's
      before-picture, `read_screen` with an image, `perceive_screen`).
    - The entry check still stops a known app first.
    - Video apps also set the flag on their players, so a streaming app may be caught too. The
      user can take it back with a "Not a money app" choice on the agent page's Never list, a
      `secure_apps` entry the user removed. That is the only exception.
  - **Done when:**
    - `SensitiveAppsTest` passes: a package recorded as secure is in the map, and is gone once
      the user removes it.
    - On the phone: after AURA tries a screenshot in a banking app that does not handle UPI (or
      in Netflix as a stand-in), the next `launch_app` for it is refused.

- [ ] **5. In a money app, every tool is refused except the way out.**
  - **What changes:** `:mcp-server` `ForegroundGuard.kt`: `GATED_GESTURES`, `GATED_PERCEPTION`,
    `UNGATED_WRITE_TOOLS` and `UNGATED_READ_TOOLS` become one `ALLOWED_IN_FRONT` list, and every
    tool not in it is gated. Also `ForegroundGuardTest` and `ForegroundGuardCoverageTest`.
  - **Logic:** while a sensitive app is in front, a tool is allowed only if it leaves the app or
    does not touch its screen. That is the names now in `UNGATED_WRITE_TOOLS` and
    `UNGATED_READ_TOOLS`: `press_key`, `launch_app`, `open_deeplink`, `volume`,
    `media_control`, `read_notifications`, `notification_action`, `dismiss_notification`,
    `system_intent`, `open_file`, `clipboard`, the `browser_*` tools (AURA's own browser), and
    the other reads listed there. Every other tool is refused, including any tool added later.
    Today a new tool is allowed until someone lists it; this refuses it.
  - **Done when:**
    - `ForegroundGuardTest` passes: with a sensitive app in front, `read_screen`, `tap` and a
      made-up `new_tool` are refused, and `press_key` is allowed.
    - `ForegroundGuardCoverageTest` passes: every name in `ALLOWED_IN_FRONT` is a registered
      tool, so a typo can't open a hole.

- [ ] **6. A send approval covers a link by where it opens, not by a host list.**
  - **What changes:** `SafetyGate.kt` (`APP_LINKS` removed; `linkCarriesOnly` and `covered` take
    `appLabel: (url: String) -> String?`), `ToolHub.kt` (passes `{ appFor(it)?.label }`, step 1's
    resolver), `SafetyGateTest`.
  - **Logic:** an approved message to Priya in WhatsApp covers a link only when that link opens
    WhatsApp, found by resolving it on the phone, and carries only the approved text and a known
    number. This works for any messaging app without a list. `LINK_ROUTING` stays: it is the
    link's own fixed words (`send`, `text`), not apps.
  - **Done when:** `SafetyGateTest` passes: a `wa.me` link where `appLabel` returns "WhatsApp" is
    covered, and the same link where `appLabel` returns null is not.

- [ ] **7. The approval modes are one rule in code.**
  - **What changes:** `SafetyGate.kt` (`needsCard(kind, mode, strangerText)` (new) replaces
    `asks` and the separate ASK_OFTEN open rule in `evaluate`), `ApprovalMode` KDoc,
    `SafetyGateTest`.
  - **Logic:** one function, one line per mode, with the same behaviour as today. The
    private-content send-out question is apart from the modes and unchanged.

    | Mode | Asks before |
    |---|---|
    | Ask often | every commit, connector write, and anything that opens an app, link or intent |
    | Confirm sends | every commit and connector write |
    | Hands-free | orders, deletes, unlabelled commits, and any commit after reading a stranger's text |

  - **Done when:** every existing `SafetyGateTest` case passes unchanged, plus one table-driven
    case per mode.

- [ ] **8. Two APKs with the fix, for a fair Part A and Part B run.**
  - **What changes:** nothing in code. `feature-long-tasks.md` step 5 says the baseline uses the
    "Part A + fix" APK.
  - **Logic:**
    - "Part A + fix": a clean worktree at `c07c70c` (Part A), with this spec's commits
      cherry-picked onto it. Conflicts are resolved toward this spec's logic. Built to
      `dist/apks/feature-long-tasks-part-a-fix.apk`.
    - "Part B + fix": `feat/agentcore` HEAD, built in a clean worktree to
      `dist/apks/feature-long-tasks-part-b-fix.apk`.
    - The user runs tasks 38–45 on A+fix, then installs B+fix and runs them again. One phone
      holds one AURA, so the runs are one after the other; the logs survive the install.
  - **Done when:** both APKs build, `:app:testDebugUnitTest` and `:mcp-server:testDebugUnitTest`
    pass in both worktrees, and A+fix is installed on the phone.

## Left out on purpose

- **A per-locale commit word list:** out of scope above.
- **Asking the model whether a call is safe:** the gate stays deterministic, so nothing on a
  screen can argue past it.
- **Bringing AURA to the front to ask:** it would take the user away from the app the task is
  in. The notification reaches them without that.
- **A new `Answer` type for `confirm`:** `ask` already returns null for no answer. The gate reads
  that directly, and the memory and skill confirmations keep their simple yes or no.
