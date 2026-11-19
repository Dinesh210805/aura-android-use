# `remote/`: owner controls over installed copies

> **Keep this file current.** If you add, remove, rename or repurpose anything in this folder,
> update this README in the same commit.
>
> **Last reviewed:** 2026-11-19.

How the owner of a Firebase project controls and counts the AURA installs built against it:
kill switch, minimum version, update prompt and in-app update, per-device blocking, the install registry, and
Google sign-in. **Everything here depends on Firebase** (Auth, Firestore, Remote Config).

## Files
| File | What it does |
|---|---|
| `RemoteGateManager.kt` | Combines Remote Config (kill switch, version floor, update nudge) and `Blocklist` into one `GateState`. Remote Config fails **open**. |
| `GateState.kt` | The states (`Allowed`, `KillSwitchBlocked`, `VersionBlocked`, `UpdateAvailable`) and the pure precedence rule `evaluateGate` |
| `RemoteGateViewModel.kt` | Hands the state and the update progress to `AuraAppShell`, which shows `BlockScreen` while blocked and `UpdateDialog` when an update is out; owns the single update job |
| `AppUpdater.kt` | The in-app update: downloads `apk_url`, checks it, and commits it to a `PackageInstaller` session. Publishes `UpdateProgress`; every failure offers the browser (`update_url`). |
| `ApkDownload.kt` | `downloadApk`: streams the APK to disk with a percent callback; deletes the file on error or cancel |
| `ApkCheck.kt` | `checkUpdateApk`: the file must be this package, newer, and signed with exactly the running app's certificates |
| `UpdateInstallReceiver.kt` | The installer's answer: opens Android's confirm screen, or reports the failure to `AppUpdater` |
| `GateEnforcer.kt` | Acts on a blocking state: stops the MCP server, wake word, overlay and agent, posts a notification, and opens `BlockScreen` when the user tries to start AURA. Restores the wake word when the block lifts. |
| `Blocklist.kt` | `blocklist/{hash}` (device or account hash) plus this install's `devices/{uid}.blocked`, checked at most once a day. Fails **closed** once blocked. |
| `DeviceIdentity.kt` | Salted SHA-256 of `ANDROID_ID` and of the uid: the blocklist keys |
| `DeviceRegistry.kt` | Writes one row per install to `devices/{uid}`: once a day, or at once when the row changes; never reads (diagnostics on) |
| `ReportDecision.kt` | The pure throttle rule for `DeviceRegistry`: `shouldReport` |
| `AuraSignIn.kt` | Google sign-in via Credential Manager. Links to the anonymous account so the uid stays the same. Also `SignInOutcome` and `AuthState`. |

Pure logic sits in its own files (`GateState`, `ReportDecision`, `Blocklist.decide`,
`Blocklist.isCheckDue`, `DeviceIdentity.hashOf`, `checkUpdateApk`, `downloadApk`) and is unit tested in `app/src/test/.../remote/`.

## Enforcement
A blocking `GateState` (kill switch, blocklist or suspension, version floor) means AURA does
nothing until it clears:
- `RemoteGateManager.hydrate` restores the last verdict from disk in `AuraApplication.onCreate`,
  before anything starts, so a blocked install is blocked even offline and before any fetch. The
  version floor is re-checked against the running build, so installing the update lifts it at once.
- `GateEnforcer.watch` reacts to every change of `RemoteGateManager.state`.
- Every entry point also checks `RemoteGateManager.isBlocked()` itself: `AuraOverlayService`
  (show, show-and-listen, run-task, and any direct start: this covers the wake word, the volume
  shortcut, the assist gesture and the pause pill), `WakeWordListeningService` (start and sticky
  restart), `AssistantForegroundService` (MCP start, restart, keep-alive), and `BootReceiver`.
- Inside the app, `AuraAppShell` shows `BlockScreen` instead of any screen.

## Free-tier budget
The project runs on the free Firebase plan, shared by every install. Per install per day:
- Remote Config: fetched at most every 15 minutes. Free and unlimited, so every global control
  lives there.
- Firestore: about 2 reads (`Blocklist`) and 1 write (`DeviceRegistry`). The free tier's 50,000
  reads and 20,000 writes a day cover roughly 20,000 daily installs. Past that, lookups and writes
  fail quietly: the gate keeps its last verdict and the app keeps working.
- Auth: one anonymous account per install, created once.
- The Realtime Database is not used. The MCP link is local-network only (`com.aura.mcp.lan`).

## Firebase state this folder depends on
| Where | Keys / paths | Written by |
|---|---|---|
| Remote Config | `kill_switch_enabled`, `kill_switch_message`, `min_supported_version_code`, `version_gate_message`, `latest_version_code`, `update_url`, `apk_url` (the APK itself, for the in-app install; blank falls back to `update_url` in the browser), `latest_version_name`, `release_notes` (the update pop-up; blank hides each) | owner; `release-app.sh` sets every key from `latest_version_code` on, each release |
| Firestore `devices/{uid}` | the registry row, plus `blocked` / `blocked_message` (a suspension; ends if app data is cleared) | app (the row), owner (`blocked`) |
| Firestore `blocklist/{hash}` | document existence, optional `message` | owner only |

The Firestore security rules and the owner dashboard that edit this state are **not in this
repo**. A fork using its own Firebase project needs its own rules: clients may create and update
only the whitelisted fields of their own `devices/{uid}`, may read their own row and `blocklist`,
and may never write `blocklist`.

## Known issues
- **Firebase-only:** a planned `foss` build flavour has no Firebase, so this folder needs a no-op
  implementation there (gate always `Allowed`, no registry, no sign-in).
