# `presentation/`: the app's UI

> **Keep this file current.** If you add, remove, rename or repurpose anything in this folder,
> update this README in the same commit. A stale map is worse than no map.
>
> **Last reviewed:** 2026-09-26, against the `prod` branch.

Everything the user sees in the AURA **app** (Settings, onboarding, the MCP centre, traces, eval),
plus the **overlay UI** the assistant draws on top of other apps. All of it is Jetpack Compose.

Colours, typography and shapes come from `../ui/theme/` (`Mono`, `MonoScheme`, `Capsule`), not
from this folder. `res/values/colors.xml` is not the palette: it only holds the launcher icon
background.

---

## Two ways in

| Surface | Entry point | Hosted by |
|---|---|---|
| **The app** (normal Activity UI) | `navigation/AuraAppShell.kt` | `MainActivity.kt` → `AuraAppShell(...)` |
| **First run** | `screens/onboarding/OnboardingScreen.kt` | `MainActivity.kt`, shown before the shell |
| **The overlay** (floating assistant over other apps) | `screens/VoiceAssistantOverlay.kt`, `overlay/*`, `screens/AskUserCard.kt` | `overlay/AuraOverlayService.kt` (outside this folder) |

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
├── overlay/      overlay-only UI: status strip, pause pill, open cue
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
| `home` | `screens/home/AuraHomeScreen.kt` (+ `AppPromptMarquee.kt`, the scrolling example prompts) |
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
| `settings/trusted_devices` | `TrustedDevicesScreen.kt`: computers allowed to drive the phone |
| `settings/restricted_apps` | `RestrictedAppsScreen.kt`: apps AURA must stay out of |
| `settings/tavily` | `TavilyKeyScreen.kt`: key for the `web_search` tool |
| `settings/privacy`, `/terms`, `/licenses` | `screens/legal/LegalScreens.kt`; text in `LegalCopy.kt` |
| `eval` (debug builds) | `screens/eval/EvalSuiteScreen.kt` + `EvalRunPicker.kt` + `EvalTaskSheet.kt` |

`screens/trace/` holds the shared trace rendering: `TraceAtoms` (UI pieces), `TraceFormatting`
(payload → readable text), `TraceVisibility` (what a user sees vs. what gets exported) and
`TraceRedaction` (applies it on export).

### Overlay UI
| File | What it does |
|---|---|
| `screens/VoiceAssistantOverlay.kt` | The floating assistant: input pill, transcript, replies. The biggest file here. |
| `screens/AskUserCard.kt` | The card shown when the agent calls `ask_user` (human in the loop) |
| `overlay/AgentStatusOverlay.kt` | The status strip during a run; also driven by the Live voice controller |
| `overlay/PausePillOverlay.kt` | Pause/stop controls for a running task. The browser window reads its geometry. |
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
| `AiDisclaimer.kt` | The "AI can make mistakes" line |

### `permissions/`
`PermissionRegistry.kt` is the single catalogue of every permission AURA asks for (runtime vs.
special). `PermissionSystemLauncher.kt` opens the right system settings page, and
`PermissionToggleModel.kt` maps state to toggle UI. **Adding a permission means editing the
registry and `AndroidManifest.xml` together.**

---

## Design rules (the "Mono" language)
- **Canvas:** warm off-white, hairline outlines, near-black text. **Ink:** black cards, pills and
  primary buttons.
- **Blood red (`0xFFA31621`) is for attention only:** live/recording, destructive actions, broken
  permissions, the active-run dot. Never decorative.
- One radius language, from `Mono`: `ShapeCard` 24dp, `ShapeCardSmall` 16dp, `ShapeChip` 12dp,
  `ShapePill`. (`Capsule` has its own `ShapeCard` at 22dp, for Capsule cards only.)
- The app is light-only. The overlay has its own fixed colour scheme at the top of
  `VoiceAssistantOverlay.kt`.

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
