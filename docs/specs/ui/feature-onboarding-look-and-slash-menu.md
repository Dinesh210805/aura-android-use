> **Status:** in progress. **Written:** 2026-11-18. **Branch:** feat/agentcore.

# Onboarding in the app's look, and a slash-command menu in the chat

## Goal
Two UI gaps the user hit:

1. **Onboarding looks like a different app.** `OnboardingScreen.kt` paints its own cream page
   (`Color(0xFFFCF4E6)`), black ink buttons, a dark red "reason" card and Material typography.
   The rest of the app (the chat, which is Home) uses the app theme from `ui/theme/AuraTheme.kt`:
   the Sky gradient or Classic canvas, light and dark, the user's accent colour, Instrument Serif
   titles and Instrument Sans text, frosted cards. A new user's first screens should look like the
   app they are about to use.
2. **Slash commands are invisible.** The chat already understands `/goal <text>` (`Goals.kt`) and
   `/btw <question>` (`Btw.kt`), but typing `/` shows nothing, so nobody finds them. The user wants
   a menu that lists what is available, with a short explanation next to each command, plus a few
   useful new ones for chat actions.

## End product
- **Onboarding:** on a phone in dark mode with the Sky theme, the first-run steps draw on the
  same sky as the chat. Step titles are in the serif, cards are the chat's cards, the main button
  is a pill in the accent colour, the back button and step dots use theme colours. In Classic
  light mode it is the plain canvas with the same pieces. Steps, wording and order are unchanged.
- **Slash menu:** in the chat, typing `/` opens a frosted list just above the composer:

  ```
  /new     Start a new chat
  /find    Search this chat
  /model   Change the model
  /files   Files in this chat
  /stop    Stop the running task
  /goal    Keep working until a goal is done
  /btw     Quick side question; doesn't interrupt the task
  ```

  Typing `/go` narrows it to `/goal`. Tapping an action command (`/new`, `/find`, `/model`,
  `/files`, `/stop`) runs it at once and clears the box. Tapping a command that takes text
  (`/goal`, `/btw`) puts `/goal ` in the box with the cursor after it, and the menu closes.
  A command that does not apply right now (`/stop` with nothing running, `/files` with no files,
  `/find` in an empty chat) is not listed.

## Out of scope
- New steps, removed steps or new onboarding copy: the user chose the look only.
- Panel and app-screen commands (`/memory`, `/skills`, `/settings`, …) and running saved skills
  by name: not chosen this time; the command list is one table, so they are a line each later.
- The slash menu in the overlay prompt box (`VoiceAssistantOverlay.kt`): the user asked for the chat.
- Sending a bare action command as a message (typing `/new` and pressing Send): see step 2.

## Steps

- [ ] **1. Onboarding draws with the app theme.**
  - **What changes:** `presentation/screens/onboarding/OnboardingScreen.kt`.
  - **Logic:**
    - The page: drop `onboardingCanvas`; the root `Column` uses `Modifier.auraBackground()` (Sky
      gradient or Classic canvas, from `AuraTheme.kt`) instead of `.background(onboardingCanvas)`.
    - Text on the page itself uses `aura.onSky` / `aura.onSkyMuted`; text inside a card uses
      `aura.label` / `aura.secondary` (the rule in the `AuraColors` KDoc).
    - Step titles (`MaterialTheme.typography.headline*` / `display*`) become `AuraType.largeTitle`;
      body text `AuraType.body` / `callout` / `footnote`; button labels `AuraType.headline`.
    - Cards (permission rows, `HowAuraWorksStep` card, sign-in card, key card, restricted-apps rows)
      use `Modifier.auraCard()`; the shape is `AuraShape.Card` / `AuraShape.Inner`.
    - Primary button (bottom pill and `LandingStep`'s button): background `aura.accent`, text
      `aura.onAccent`, disabled `aura.well` / `aura.tertiary`, as the chat's `RoundButton`. Its
      inner back button: `aura.onAccent` at low alpha behind an `aura.onAccent` arrow.
    - Back button on permission steps and the step dots: `aura.well` / `aura.label`, inactive dots
      `aura.hairline`.
    - `FloatingReasonCard`: `Modifier.auraCardStrong()`, title `aura.label`, body `aura.secondary`,
      icon `aura.danger` on `aura.danger.copy(alpha = 0.12f)`.
    - Sign-in buttons (white with `0xFFE1E4EA` / `0xFFDADCE0` borders): `aura.surfaceStrong` with
      `aura.hairline`. The Google "G" (`R.drawable.ic_google_g`) is a drawable and stays as is.
    - Every remaining `Color(0x…)` and `Color.White` in the file is replaced by a token.
    - `scheme` (`rememberMonoScheme()`) stays where it is already used: it is derived from
      `LocalAura`, so it already follows the theme.
    - `presentation/README.md`: the "First run" row says onboarding draws with the app theme
      (`auraBackground`, `AuraType`, accent button); bump **Last reviewed**.
  - **Changed while building (user's call):** onboarding is always Classic, light or dark with the
    system (`AuraUITheme(styleOverride = StyleChoice.CLASSIC)` in `MainActivity`). The step
    animations in `assets/onboarding/*.html` draw on a transparent page, so each defines the seven
    Classic colours as CSS variables with a dark set; `OnboardingAnimationCard` loads them with
    `?theme=dark` in dark mode. The animations' warm cream and wine-red palette is mapped onto Classic tokens (ink,
    `#5D5D5D`, `#F0F0F0`, `#E3E3E3`), the landing rings fade ink to grey, and every animation uses the
    one-colour mark (`aura_logo_mono.png`; `aura_logo_color.png` deleted). New installs start in
    Classic (`AppearanceStore` default `StyleChoice.CLASSIC`).
  - **Done when:** `grep -n "Color(0x" OnboardingScreen.kt` matches only the WebView's
    `setBackgroundColor(0x00000000)` line, `./gradlew :app:assembleDebug` builds, and on the phone
    (clear app data, open AURA) every step from the landing page to "Launch AURA" is Classic with
    readable text in both light and dark mode, the one-colour mark and grey rings on the landing page, and
    the accent-coloured main button.

- [x] **2. The command list and its filter.**
  - **What changes:** `agentcore/SlashCommands.kt` (new): `enum class SlashAction { NEW, FIND,
    MODEL, FILES, STOP }`, `data class SlashCommand(val name: String, val hint: String,
    val takesText: Boolean, val action: SlashAction?)`, `object SlashCommands` with `val ALL`
    and `fun matching(text: String, available: (SlashCommand) -> Boolean): List<SlashCommand>`.
    The two text commands are named `Goals.PREFIX.trimEnd()` and `Btw.PREFIX.trimEnd()` (the
    prefixes are `"/goal "` and `"/btw "`), so the menu and the parsers cannot drift.
    `app/src/test/java/com/aura/aura_ui/agentcore/SlashCommandsTest.kt` (new).
    `agentcore/README.md`: a `SlashCommands.kt` row; bump **Last reviewed**.
  - **Logic:**
    ```
    matching(text):
      if text doesn't start with "/" or contains whitespace -> empty   // "/goal buy milk" closes it
      q = text.drop(1).lowercase()
      ALL.filter { it.name.drop(1).startsWith(q) && available(it) }
    ```
    The list is the table in **End product**, in that order. A bare action command sent as a
    message (`/new` + Send) is not intercepted: the menu is the way in, and a message beginning
    with `/` stays a normal message so "/r/android is down?" still reaches the agent.
  - **Done when:** `./gradlew :app:testDebugUnitTest --tests "com.aura.aura_ui.agentcore.SlashCommandsTest"`
    passes, with cases: `"/"` returns all available commands; `"/go"` returns only `/goal`;
    `"/goal buy"` returns none; `"hello"` returns none; `"/stop"` with `available` false for STOP
    returns none.

- [ ] **3. The menu above the composer.**
  - **What changes:** `agentcore/ChatUi.kt`: `Composer` gets `onCommand: (SlashAction) -> Unit`
    (NEW, FIND, FILES only) and `available: (SlashCommand) -> Boolean`; new private
    `@Composable SlashMenu(...)` drawn above the composer's glass. MODEL reuses the existing
    `onModels`, STOP calls `vm.stop()` as the Stop button already does. `AgentChatScreen` passes
    NEW → `vm.open(null)`, FIND → `finding = ""`, FILES → `filesOpen = true`. `available`: STOP
    only while `running`, FILES only when `files` is not empty, FIND only when `items` is not empty.
    `agentcore/README.md`: the `ChatUi.kt` row mentions the slash menu.
  - **Logic:** `val shown = SlashCommands.matching(text, available)`; when it is not empty, a
    column on `Modifier.glass(AuraShape.Card)` lists one row per command: name in `AuraType.headline`
    `aura.label`, hint in `AuraType.footnote` `aura.secondary`, at least 48dp tall, the whole row
    clickable with `onClickLabel = hint`. Tap: `takesText` → `onText(name + " ")` and keep focus;
    otherwise `onText("")` then run the action. At most 6 rows show; the list scrolls past that.
  - **Done when:** on the phone, during a running task with files in the chat (so all seven
    commands are listed): typing `/` shows the menu with hints, six rows visible and the list
    scrolls to `/btw`, each row comfortably finger-sized; `/go` leaves only `/goal`; tapping `/goal`
    leaves `/goal ` in the box with the keyboard up; tapping `/model` opens the model sheet and
    empties the box; `/stop` stops the task; `/new` starts an empty chat; in the new empty chat
    `/` no longer lists `/stop`, `/find` or `/files`. TalkBack on a row reads its hint.

## Left out on purpose
- A slash-command registry the agent or skills can add to: one fixed list is all that is needed now.
- Fuzzy matching (`/mdl` → `/model`): prefix match is predictable with seven commands.
- Keyboard arrow navigation in the menu: phones type with a touch keyboard.
- Restyling the HTML animation inside `OnboardingAnimationCard`: it is a bundled asset, not Compose.
