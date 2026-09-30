# AGENTS.md

> **Keep this file current.** Update it in the same commit as any change it describes.
>
> **Last reviewed:** 2026-09-30.

Instructions for AI coding agents (and humans) working in this repo.

## What this is
AURA is an Android app that automates the phone by voice or text. Everything runs on the device.
The code is in `aura-android/`:
- `:app`: the app (UI, the on-device agent, accessibility and gesture execution)
- `:mcp-server`: the tool server the app hosts in-process, i.e. the agent's action space

`aura-mcp-connect/` is the desktop bridge (Node, MIT-licensed) that lets MCP clients on a
computer reach the phone.

`aura-android-use-website/` is the static website, deployed by Vercel from `main` (see its README).

## Build and test
Run from `aura-android/` (`gradlew.bat` on Windows):
```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :mcp-server:test
```
The build needs two git-ignored files:
- `aura-android/app/google-services.json`: copy the shape from `google-services.example.json`.
- `aura-android/local.properties`: copy `local.properties.example` (SDK path, optional release
  signing).

There is no `.env`. Provider API keys are entered in the app at runtime. The bridge reads a few
optional env vars (`AURA_MCP_PORT`, `AURA_MCP_ENABLE_ADB`, `AURA_ADB_SERIAL`, `AURA_DEBUG`);
see `aura-mcp-connect/README.md`.

## Documentation rules
- **Folder READMEs are the map.** Before editing a folder, read its `README.md` if it has one.
  Current ones, under `aura-android/app/src/main/java/com/aura/aura_ui/`: `presentation/`, `remote/`.
- **Update the README in the same commit** when you add, remove, rename or repurpose anything it
  describes, then bump its **Last reviewed** date.
- "Last reviewed" means *someone checked this doc against the code on that date*. Don't bump it
  without checking.
- Explain *why* in KDoc; the code already says *what*.

## Comment standard
Comments are read by humans **and** by coding agents arriving cold. Write them so that a reader
with no chat history, no private docs and no memory of past sessions can change the code safely.

- **First line: one sentence saying what this is.** No preamble.
- **Then only the labelled parts that apply**, as short bullets:
  - `Contract:` what callers can rely on (return values, threading, idempotence)
  - `Fails:` what happens on error, offline, or missing config (open or closed, retried, swallowed)
  - `Reads/Writes:` external state it touches: Firestore paths, prefs files, network hosts
  - `Change together:` other files that must be edited in the same commit
  - `Why:` the non-obvious reason, only if the code would otherwise look wrong
- **No history.** No dates, "we used to", "the old version", or "this fixes the bug where…".
  That's what git log is for. Keep only what is true now.
- **No pointers to anything that isn't in this repo** (private docs, dashboards, chat sessions).
  If it matters, say it inline or put it in the folder README.
- **Name exact identifiers** (`devices/{uid}`, `KEY_UPDATE_URL`) rather than describing them.
- Delete comments that restate the code.

## Before you commit
- Code that nothing references is dead. Delete it, don't leave it "for later".
- Don't hardcode an ONNX execution provider: `OnnxYoloRunner` benchmarks and picks per device.
- Overlay UI has no Activity window. See the `presentation/` README.
