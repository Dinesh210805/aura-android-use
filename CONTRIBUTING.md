# Contributing to AURA

Thanks for helping. AURA has one maintainer, so every bug report, idea and pull request counts.

## Ways to help

- **Report a bug.** [Open an issue](https://github.com/Dinesh210805/aura-android-use/issues/new/choose).
  Say what you asked AURA to do, what happened instead, your phone model and Android version, and
  the AURA version (Settings → About). A screen recording helps more than anything.
- **Suggest an idea.** Open an issue, or talk it over on [Discord](https://discord.com/invite/H66ws9zMPF) first.
- **Fix something.** Pick an issue, or an unticked item in the [roadmap](README.md#roadmap). For
  anything bigger than a small fix, open an issue first so we agree on the approach before you
  spend time on it.
- **Security problems** go through [SECURITY.md](SECURITY.md), never a public issue.

## Set up

You need **JDK 17**, the **Android SDK (API 36)**, and **Node.js 18+** for the bridge.

1. Copy `aura-android/local.properties.example` to `aura-android/local.properties` and set your SDK path.
2. Copy `aura-android/app/google-services.example.json` to `aura-android/app/google-services.json`.
   A debug build works with it and lets you skip sign-in. For the full Firebase setup, see
   [Build from source](README.md#build-from-source).

## Build and test

```bash
cd aura-android
./gradlew :app:assembleDebug                      # gradlew.bat on Windows
./gradlew :app:testDebugUnitTest :mcp-server:test

cd ../aura-mcp-connect
npm install && npm test
```

All of these must pass before you open a pull request. If you change how the agent behaves,
also try it on a real phone and say in the PR what you ran and what you saw.

## Pull requests

- **Read [`AGENTS.md`](AGENTS.md) first.** It has the module layout, the comment standard and the
  documentation rules. It is written for humans and coding agents alike.
- **Keep it small.** One change per pull request.
- **Bigger changes start with a spec** in `docs/specs/`. [`CLAUDE.md`](CLAUDE.md) describes the
  format: goal, end product, out of scope, and small steps each with a check you can run.
- **Update the folder README** in the same commit when you add, remove or rename what it describes.
- **Delete dead code** rather than leaving it for later.
- **Commit messages** follow `type: description`, where type is one of `feat`, `fix`, `refactor`,
  `docs`, `test`, `chore`, `perf`, `ci`.
- **Sign the CLA once.** On your first pull request a bot asks you to sign the [CLA](CLA.md). You
  keep your copyright; it lets the project offer a commercial licence next to the AGPL.
- **Add a line to [CHANGELOG.md](CHANGELOG.md)** under *Unreleased* if users will notice the change.

## Code of conduct

Everyone taking part agrees to the [Code of Conduct](CODE_OF_CONDUCT.md).
