# `presentation/`: the app's UI

> **Keep this file current.** If you add, remove, rename or repurpose anything in this folder,
> update this README in the same commit. A stale map is worse than no map.
>
> **Last reviewed:** 2026-11-13.

Everything the user sees in the AURA **app** (Settings, onboarding, the MCP centre, traces, eval),
plus the **overlay UI** the assistant draws on top of other apps. All of it is Jetpack Compose.

Colours, typography and shapes come from `../ui/theme/` (`Mono`, `MonoScheme`, `Capsule`), not
from this folder. There is no `res/values/colors.xml`; the launcher icon is bitmap layers in
`res/mipmap-*`.

---

## Two ways in

| Surface | Entry point | Hosted by |
|---|---|---|
| **The app** (normal Activity UI) | `navigation/AuraAppShell.kt` | `MainActivity.kt` → `AuraAppShell(...)` |
| **First run** | `screens/onboarding/OnboardingScreen.kt` | `MainActivity.kt`, shown before the shell |
| **The overlay** (floating assistant over other apps) | `screens/VoiceAssistantOverlay.kt`, `overlay/*` | `overlay/AuraOverlayService.kt` (outside this folder) |

⚠️ **The overlay has no Activity window.** Composables used by the overlay can't assume
`LocalView` has a window, can't use Activity-scoped ViewModels, and can't use `hiltViewModel()`.
`AuraUITheme` already guards its status-bar code with `activity != null`; do the same.

---

## Map

```
presentation/
├── navigation/   the app's router
├── screens/      one file per screen (subfolders for multi-file screens)
├── components/   small reusable UI pieces
├── overlay/      overlay-only UI: status strip, pause pill, run report card, open cue
├── permissions/  the permission catalogue and its toggle rows
└── utils/        HapticUtils (vibration profiles)
```

### `navigation/`
| File | What it does |
|---|---|
| `AuraAppShell.kt` | The nav host. `object AuraRoutes` lists every route; the bottom bar has 4 tabs: **Home, Agent, Activity, Settings**. |
| `BlockScreen.kt` | Full-screen, non-dismissible block (kill switch, blocklist or minimum version), from `remote/RemoteGateManager`. Replaces the nav host when active; `remote/GateEnforcer` brings the user here from outside the app. Also `UpdateBanner`. |

### Routes → screens
| Route | Screen |
|---|---|
| `home` | `agentcore/ChatUi.kt` (outside this folder): the chat with the agent, with its own light and dark theme (`agentcore/ChatTheme.kt`). The bottom bar is hidden here; the chat's drawer links to the other screens |
| `home/dashboard` | `screens/home/AuraHomeScreen.kt` (+ `AppPromptMarquee.kt`, the scrolling example prompts): the status dashboard, opened from the chat's drawer |
| `agent` | `screens/agent/AgentHubScreen.kt`, the hub linking to everything below |
| `agent/brain` | `ProviderSettingsScreen.kt`: LLM provider, model and keys |
| `agent/tools` | `screens/agent/ToolsScreen.kt`: MCP tool list, read/write/agent tags |
| `agent/hooks` | `screens/agent/HooksScreen.kt`: the tool-call hook chain |
| `agent/skills` | `SkillsScreen.kt` + `SkillsViewModel.kt` |
| `agent/memory` | `MemoryScreen.kt` + `MemoryViewModel.kt`: read-only memory inspector |
| `agent/mcp_servers` | `McpServersScreen.kt` + `McpServersViewModel.kt`: external MCP servers |
| `agent/companion` | `CompanionScreen.kt` + `CompanionViewModel.kt`: voice mode and Gemini key |
| `agent/voice` | `VoiceSettingsScreen.kt`: TTS voice and previews (`res/raw/voice_preview_*`) |
| `activity` | `McpSessionLogScreen.kt`: past sessions, newest first |
| `activity/session/{sessionId}` | `McpSessionDetailScreen.kt`; agent runs render through `AgentTraceScreen.kt` |
| `activity/retention` | `McpLogRetentionScreen.kt` |
| `settings` | `screens/settings/MonoSettingsScreen.kt` |
| `settings/permissions` | `screens/settings/PermissionsHealthScreen.kt` |
| `settings/mcp_center` | `McpCenterScreen.kt`: the phone-as-MCP-server controls |
| `settings/trusted_devices` | `TrustedDevicesScreen.kt`: computers allowed to drive the phone, and the optional tool groups (`Toolset`) each one gets |
| `settings/restricted_apps` | `RestrictedAppsScreen.kt`: apps AURA must stay out of |
| `settings/tavily` | `TavilyKeyScreen.kt`: key for the `web_search` tool |
| `settings/privacy`, `/terms`, `/licenses` | `screens/legal/LegalScreens.kt`; text in `LegalCopy.kt` |
| `eval` (debug builds) | `screens/eval/EvalSuiteScreen.kt` + `EvalRunPicker.kt` + `EvalTaskSheet.kt` |

`screens/trace/` holds the shared trace rendering: `TraceAtoms` (UI pieces), `TraceFormatting`
(payload → readable text) and `TraceVisibility` (whether the trace opens on the full log or the
story view). Every build records, shows and exports the same full log.

### Overlay UI
| File | What it does |
|---|---|
| `screens/VoiceAssistantOverlay.kt` | The floating prompt box: close, text field (Enter sends), AURA button; while listening, the white voice ribbon and a "Type" button. Lifted above Resume/Cancel while a run is paused. |
| `overlay/QuestionCardOverlay.kt` | The question card at the top when AURA's agent or an MCP client calls `ask_user`: choices, Type (opens the prompt box), Speak, Skip. Answers go to `AskUserBroker.shared`; voice conversations also ask it aloud. |
| `overlay/AgentStatusOverlay.kt` | The strip at the top (`services/AgentStatusRegistry`): a small centered "Thinking…" pill while the model thinks, nothing while a task drives the phone, and a rounded glass card for what the person said and what AURA is saying |
| `overlay/PausePillOverlay.kt` | Pause/stop controls for a running task. The browser window reads its geometry. |
| `overlay/RunReportOverlay.kt` | The report card when a run ends (done, partial, failed, stopped, or a remote client went quiet) or the user presses Pause on a remote run. Draws the `end_session` `report` markdown via `overlay/ReportMarkdown.kt`, else AURA's reply, else the last steps. State comes from `services/RemoteRun.kt`, which also puts remote runs on the status strip and run controls. On-device runs reach it through `agent/OnDeviceReport.kt`: from `AuraAgent.runSpike`, and from the overlay when a task it ran drove the phone (`OnDeviceReport.forTurn`, steps in the cursor's words from `services/StepPill`). AURA's own reports are drawn on the prompt box in `VoiceAssistantOverlay` (this window is for remote runs). Tasks the chat handed off get a chat card instead, and benchmark runs none. The next task closes it. |
| `overlay/OverlayOpenCue.kt` | One-shot sound (`res/raw/overlay_open_chime.mp3`) + haptic when the overlay opens |

### `components/`
| File | What it is |
|---|---|
| `MonoComponents.kt` | Primary/secondary pill buttons and other Mono basics. Use these before writing new ones. |
| `MonoList.kt` | The grouped-list scheme used by every settings-style screen |
| `CapsuleComponents.kt` | The dark gradient "Capsule" card |
| `AuraScreenScaffold.kt` | Standard screen frame: title and content padding. Back navigation comes from `AuraAppShell`'s contextual bar, not a top arrow. |
| `AuraSwitch.kt`, `PermissionToggleRow.kt`, `PermissionIcons.kt` | Toggles and permission rows |
| `AuraLogoMark.kt` (animated AGSL shader), `AuraLogoBubble.kt` | The AURA logo |
| `AuraSplash.kt` | The launch animation: light draws the mark, the glass fills, the word AURA lands. `MainActivity` adds it over its window on a launcher start only (`shouldPlayLaunchSplash`); the system splash before it is blank black (`Theme.Aura.Splash`). |
| `AiDisclaimer.kt` | The "AI can make mistakes" line |

### `permissions/`
`PermissionRegistry.kt` is the single catalogue of every permission AURA asks for (runtime vs.
special). `PermissionSystemLauncher.kt` opens the right system settings page, and
`PermissionToggleModel.kt` maps state to toggle UI. Turning a granted runtime permission off opens
AURA's App info page, because Android gives apps no way to revoke their own grant immediately.
**Adding a permission means editing the registry and `AndroidManifest.xml` together.**

---

## Design rules (the "Mono" language)
- **Canvas:** warm off-white, hairline outlines, near-black text. **Ink:** black cards, pills and
  primary buttons.
- **Blood red (`0xFFA31621`) is for attention only:** live/recording, destructive actions, broken
  permissions, the active-run dot. Never decorative.
- One radius language, from `Mono`: `ShapeCard` 24dp, `ShapeCardSmall` 16dp, `ShapeChip` 12dp,
  `ShapePill`. (`Capsule` has its own `ShapeCard` at 22dp, for Capsule cards only.)
- Every overlay window (prompt box, strip, question card, report card) follows the user's light or
  dark mode in the Classic style, never the sky: `overlayIsDark` / `overlayColors` /
  `rememberOverlayDark` in `../ui/theme/AuraTheme.kt`. A theme change applies when a window is next shown.

## How to add a screen
1. Create `screens/<area>/<Name>Screen.kt` (a ViewModel next to it only if it owns state).
2. Add a route constant to `AuraRoutes` and a `composable(...)` entry in `AuraAppShell.kt`.
3. Link to it from the right hub (`AgentHubScreen` or `MonoSettingsScreen`).
4. Build it with `AuraScreenScaffold` + `MonoList` / `MonoComponents` so it matches the rest.
5. Add a row to the **Routes → screens** table above.

## History
- 2026-09-26: removed the pre-redesign UI (23 files, ~6,800 lines): the old `HomeScreen`,
  `FloatingAssistantBubble`, `WelcomeScreen`, `McpActivityLogScreen`, `AssistantViewModel`, the
  whole old `presentation/ui/` theme, `presentation/state/`, and 11 old components. If you find
  a reference to any of them, it's stale.
