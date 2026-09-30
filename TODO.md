# TODO: open-source prep

Started 2026-09-26 on the `prod` branch. The old planning `TODO.md` is private now
(`_private/TODO.md`).

---

## 1. Documentation

**Rule of thumb:** write docs for the *questions people ask*, not for the *files that exist*. No
doc per file.

### Four layers
- [x] **The map:** a root `README.md` rewritten for strangers (2026-09-26)
- [ ] `docs/architecture.md` (how app, agent, MCP server and voice fit together)
- [ ] **Folder READMEs:** a few paragraphs each for the major folders (`agent/`, `mcp-server/`,
      `accessibility/`, `overlay/`, `presentation/`, `voice/`): what lives there, the 3–5 key
      files, data in and out
- [ ] **KDoc in code:** `/** … */` on key classes and functions, explaining *why* rather than
      *what*. Can be turned into a website later with Dokka.
- [ ] **Guides:** task-shaped docs (see below)

### `docs/` layout (Diátaxis)
```
docs/
├── README.md          start here
├── architecture.md
├── getting-started/   tutorials: build, run, first task
├── guides/            how-tos
├── reference/         facts: tools, settings, permissions
├── explanation/       the "why": agent loop, perception, safety, privacy
└── decisions/         ADRs: one short note per big decision
```

### First docs to write
- [ ] `getting-started/build-and-run.md`: clone, `local.properties`, Firebase or `foss` build,
      run on a phone
- [ ] `guides/connect-a-pc-client.md`: Claude Code / Cursor → phone over MCP
- [ ] `guides/add-an-mcp-tool.md`
- [ ] `guides/add-an-llm-provider.md`
- [ ] `reference/tools.md`: all MCP tools and their arguments
- [ ] `reference/permissions.md`: every permission in the manifest and why it's needed
- [ ] `explanation/agent-loop.md`, `explanation/perception.md`, `explanation/safety.md`
      (ActionGuard, control lock, completion judge), `explanation/privacy.md` (what leaves the phone)
- [ ] `decisions/`: Koog over Google ADK · AGPL + dual licence · in-process MCP server ·
      sherpa-onnx over Porcupine · accessibility service over root/ADB
- [x] Public-safe `AGENTS.md`: build commands, module layout, doc rules (2026-09-26)
- [ ] A folder README (with the "keep current" banner and a Last reviewed date) for each major
      folder as we clean it
- [ ] `CONTRIBUTING.md`, `SECURITY.md`, `PRIVACY.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md`

### Old doc links to fix
- [x] Root `README.md` links into the old `docs/` and `TODO.md`
- [x] `aura-mcp-connect/README.md` pointed at a private research doc
- [ ] Eval scripts read and write `docs/agent-review/EVAL_TASKS.md` (`agent_eval.py`,
      `gen_eval_tasks_md.py`, `run_eval_suite.ps1`): give that file a new home

---

## 2. Cleanup: done so far
- [x] Licence files (AGPL-3.0, linking exception, CLA, commercial licence, third-party notices)
- [x] Marketing, internal docs, AI tooling and Python leftovers out of `prod`; publishing config
      local-only (git-ignored)
- [x] `gesture-probe`, `permission_prompts`, and the 28 MB sherpa `.aar` untracked
- [x] `app/src/main`: unused assets, drawables, colours, and 5 permissions removed. Debug build
      passes.
- [ ] Run `gradlew.bat :app:testDebugUnitTest` to confirm the non-java clean-up

## 3. Cleanup: `java/`, the kitchen
Order: dead code first, then the small folders, then medium, and `agent/` last.
- [x] Dead pre-redesign UI in `presentation/`: 23 files, 6,793 lines removed, plus
      `presentation/README.md` added (2026-09-26)
- [ ] Rebuild and run the unit tests after the `presentation/` removal
- [x] `remote/`: comments rewritten to the AGENTS.md standard, README added, dead `created`
      pref and unused `Blocklist.clearCache` removed, uid dropped from the info log (2026-09-26)
- [x] `remote/`: one global kill switch, Remote Config (Firestore `config/gate` no longer read)
- [ ] `automation/AppAutomationManager.kt`, `data/audio/AmplitudeAnalyzer.kt` (dead)
- [ ] `agent/conversation/`: `EphemeralTokenClient`, `LiveCues`, `TaskNarrationGate` aren't used
      by the app, but the last two have tests. Probably built-but-not-wired. Decide during the
      `agent/` round.
- [ ] `di/AppModule.kt`: Room database and repository providers only fed the deleted
      `AssistantViewModel`
- [x] `PorcupineWakeWordDetector` + the `ai.picovoice` dependency, then remove Picovoice from
      `LICENSE-EXCEPTION.md` and `THIRD_PARTY_NOTICES.md`
- [ ] Location permission: requested, but no code reads the location
- [ ] Legacy Python-backend WebSocket path is dead since the backend left the repo:
      `ConnectionManager`, the `server_url` pref, `aura.defaultServerUrl` in `app/build.gradle.kts`,
      `PREF_LEGACY_PYTHON_BACKEND` in `AssistantForegroundService`
- [ ] Old warnings: `@param:` annotation targets (`RemoteGateManager.kt:29` and others);
      deprecated `Notification.Action.Builder` in `McpApprovalNotifier.kt:95-96`

- [ ] About 120 comments in 86 Kotlin files point at private docs (`docs/superpowers/specs/…`,
      `docs/agent-review/…`, "Spec 2026-…"). Rewrite them inline per `AGENTS.md`, folder by folder.

## 4. Code changes before going public
- [ ] Settings switch for web research (`agent/research/`): today every run searches Google with
      a scrubbed "how do I…" question, and there is no way to turn it off
- [ ] `foss` build flavour that builds without `google-services.json`
- [ ] Privacy and terms URLs from build config (`presentation/screens/legal/LegalCopy.kt:12-13`)
- [ ] Decouple `versionCode` from the git commit count before the fresh public history

## 4b. Firebase security, free tier, update/block, analytics (started 2026-09-26)
Security (step ①), done in code:
- [x] Pairing protocol 2: PIN-derived verification code on first pairing, HMAC proof on
      reconnect, both bound to the DTLS fingerprints (`PairingCrypto.kt`, `pairing-crypto.js`,
      shared test vectors)
- [x] Analytics + Crashlytics obey the diagnostics switch; advertising ID / SSAID / ad signals off

Free tier (step ②), done in code:
- [x] MCP link is local-network only: phone-side HTTP signaling (`com.aura.mcp.lan`), mDNS +
      subnet scan in the bridge, no STUN/TURN, non-LAN candidates dropped both sides. No
      Realtime Database at all, so no 100-connection limit. Tailscale for remote use.
- [x] Single-use PIN with lockout; only the PIN can open the new-PC approval dialog
- [x] Bridge 0.9.0: no Firebase; `pair --host`; real-WebRTC end-to-end test
- [x] Kill switch and version floor only from Remote Config (free, unlimited)
- [x] Blocklist + suspension read at most once a day, server-only reads, cached verdict
- [x] Registry: no reads; one write a day or on a real change; no per-interaction writes
- [x] **You:** build + test the app on a phone and a PC on the same Wi-Fi; `npm publish`
      aura-mcp-connect 0.9.0; `npm deprecate aura-mcp-connect@"<0.9.0" "Update: npm i -g
      aura-mcp-connect@latest"`
- [x] **You, after the release:** deploy the new rules (`firebase deploy --only
      firestore:rules,database`). The Realtime Database rules deny everything; old app versions
      lose MCP pairing then, which step ③'s forced update covers.
- [x] **You:** the dashboard's kill-switch button writes Firestore `config/gate`, which the app
      no longer reads. Use Remote Config `kill_switch_enabled` instead.

Update/block enforcement (step ③), done in code:
- [x] Last verdict restored at process start (`RemoteGateManager.hydrate`); `GateEnforcer`
      stops MCP, wake word, overlay and agent on a block and restores the wake word after
- [x] Every entry point refuses while blocked and opens the full-screen `BlockScreen`
- [x] **You:** in Remote Config set `min_supported_version_code` to this release's version code
      once it is out, and `update_url` to the download page, so old versions must update

Analytics (step ④), done in code:
- [x] `telemetry/AuraAnalytics`: `aura_open`, `agent_task`, `mcp_session_start/end`,
      `mcp_paired`, `mcp_connect_denied`, `onboarding_step/complete`; counts and bands only;
      privacy policy lists them
- [ ] **You:** in the Firebase console, mark `source`, `outcome`, `provider`, `duration_band`,
      `error_category` and `pc_os` as custom dimensions, and `steps`/`tool_calls` as metrics,
      so the reports can break events down by them

## 5. Licence checks
- [x] 8 voice previews in `res/raw/` named after Microsoft neural voices: dropped; Preview speaks through the phone TTS
- [x] `res/raw/overlay_open_chime.mp3`: source unknown, dropped; the overlay opens with the haptic only
- [x] Copyright holder's legal name in `CLA.md` and `LICENSE-EXCEPTION.md`
- [ ] Have `CLA.md` reviewed

## 6. Going public
- [x] Secret scan over the final tree (`gitleaks`)
- [x] Create the public repo, push `prod` as one squashed commit
- [x] Remove `prod/` before publishing
