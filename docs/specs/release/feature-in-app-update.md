> **Status:** in progress. **Written:** 2026-11-19. **Branch:** feat/agentcore.

# One-tap in-app update

**Where it stands (2026-11-23):**
- Done and proven: steps 1–4 (unit tests, and step 4's phone test; evidence under each step).
- Code done, check not yet run: step 5 (needs a rehearsal on a clean tree).
- Not started: step 6 (needs two real releases).
- Next, in order: step 5's rehearsal on the next release → step 6.

## Goal
AURA is sideloaded from GitHub Releases. When a new version ships, Firebase Remote Config
(`latest_version_code`, `update_url`) makes the app show an "Update available" dialog, or the
"Update required" screen below `min_supported_version_code`. Today both buttons open
`update_url` in the browser. The user then has to download a 156 MB APK, find it and install it
by hand, and many stop halfway, so they stay on old versions. The user wants one tap: the app
downloads the update and hands it to Android's own install screen. They also want the release
notes in the update dialog. The dialog can already show them, but the release script never
sets them.

## End product
A user on 1.0.600 opens AURA after 1.0.612 ships. The dialog says "Update available ·
Version 1.0.612" and lists the "What's new" bullets. They tap **Update**. The first time,
the dialog says "Allow AURA to install updates", and **Allow** opens Android's "Install unknown
apps" setting for AURA. They switch it on, come back and tap **Update** again. A progress bar
runs from "Downloading 0%" to 100%. The app checks that the file is AURA, newer, and signed with
the same key. Then Android's "Do you want to update this app?" screen appears. They tap
Update, and Settings → About shows 1.0.612 when they reopen AURA. If anything fails (no
network, a bad file, they decline), the dialog says why in one line and offers **Try again** and
**Download in browser**, which opens the release page as today.

## Out of scope
- Background download, a download notification, automatic or silent updates. The user picked an
  in-dialog progress bar. The download lives as long as the app process; closing the dialog or
  Cancel stops it, and nothing resumes it later.
- Reopening AURA by itself after the install. Android ends the old process, and starting an
  activity from the background is restricted.
- A SHA-256 key in Remote Config. The signing-key check already rejects a tampered file, and
  Android's installer rejects a corrupt one.
- Installs older than this feature. They keep the browser flow until they update once.

## Steps

- [x] **1. `apk_url` reaches the gate**
  - **What changes:** `remote/GateState.kt`: `GateInputs.apkUrl` (new),
    `GateState.UpdateAvailable.apkUrl` (new), `GateState.VersionBlocked.apkUrl` (new), and an
    `apkUrl` parameter on both `evaluateGate` overloads. `remote/RemoteGateManager.kt`:
    `KEY_APK_URL = "apk_url"` (new), default `""`, read in `refresh`, saved in `saveInputs`,
    restored in `hydrate`. `remote/README.md`: add `apk_url` to the Remote Config row and bump
    **Last reviewed** after checking the file against the code.
    `test/.../remote/GateStateTest.kt`: two new cases.
  - **Logic:** `apk_url` is the direct asset link
    (`https://github.com/<repo>/releases/download/v1.0.N/aura-1.0.N.apk`). It travels next to
    `update_url` exactly as `update_url` does. A blank value means "no in-app update; use the
    browser", which is what every release published before step 5 gives.
  - **Done when:** `./gradlew :app:testDebugUnitTest --tests "com.aura.aura_ui.remote.GateStateTest"`
    passes, including the new `update available carries apk url` and
    `version block carries apk url`.
  - **Evidence:** `GateStateTest` 17 tests, 0 failures.

- [x] **2. Check a downloaded APK before installing it**
  - **What changes:** `remote/ApkCheck.kt` (new): `data class ApkFacts(packageName,
    versionCode: Long, signers: Set<String>)`, `sealed interface ApkVerdict { Ok, Unreadable,
    WrongApp, NotNewer, WrongSigner }`, pure `fun checkUpdateApk(candidate: ApkFacts?,
    installed: ApkFacts): ApkVerdict`, and the Android readers `readApkFacts(context, file)`
    and `installedApkFacts(context)`. `test/.../remote/ApkCheckTest.kt` (new).
  - **Logic:** `candidate == null` → `Unreadable`. A different `packageName` → `WrongApp`.
    `versionCode <= installed` → `NotNewer`. `signers != installed.signers` → `WrongSigner`.
    Otherwise `Ok`. `signers` holds the SHA-256 hex digests of the signing certificates, read with
    `GET_SIGNING_CERTIFICATES` on API 28+ and `GET_SIGNATURES` on 26–27 (`minSdk` is 26).
    Equal sets, not "any overlap", because AURA has one key and never rotates it.
  - **Done when:** `./gradlew :app:testDebugUnitTest --tests "com.aura.aura_ui.remote.ApkCheckTest"`
    passes, with one case per verdict.
  - **Evidence:** `ApkCheckTest` 7 tests, 0 failures.

- [x] **3. Download the APK with progress**
  - **What changes:** `remote/ApkDownload.kt` (new): `suspend fun downloadApk(client:
    OkHttpClient, url: String, dest: File, onPercent: (Int?) -> Unit): Boolean`.
    `test/.../remote/ApkDownloadTest.kt` (new), using `mockwebserver3`, which
    `app/build.gradle.kts` already has.
  - **Logic:** GET `url` and stream the body to `dest`, calling `onPercent` with
    `bytesRead * 100 / Content-Length`, or with `null` when the length is unknown. A non-2xx
    response or an `IOException` deletes `dest` and returns `false`. Cancelling the coroutine
    deletes `dest` and rethrows `CancellationException`. The caller passes the unqualified client
    from `AppModule.provideOkHttpClient()` with `newBuilder().readTimeout(60, SECONDS)`: a stall
    limit per read, not a total limit, because the file is 156 MB.
  - **Done when:** `./gradlew :app:testDebugUnitTest --tests "com.aura.aura_ui.remote.ApkDownloadTest"`
    passes, with these cases: `200 writes the file and reaches 100`, `404 returns false and leaves
    no file`, `cancel deletes the partial file`, `cancel during a stalled read ends at once`.
  - **Evidence:** `ApkDownloadTest` 4 tests, 0 failures; the stalled-read cancel ends in 0.54 s.
    All 54 tests in `com.aura.aura_ui.remote` pass.

- [x] **4. Install it from the update dialog and the "Update required" screen**
  - **What changes:**
    - `remote/AppUpdater.kt` (new, `@Singleton`): `progress: StateFlow<UpdateProgress>` and
      `suspend fun install(apkUrl: String)`. `sealed interface UpdateProgress { Idle,
      NeedsInstallPermission, Downloading(percent: Int?), WaitingForInstall,
      Failed(reason: String) }`.
    - `remote/UpdateInstallReceiver.kt` (new, a plain `BroadcastReceiver` like `BootReceiver`).
      It reports through `AppUpdater`'s process-wide progress flow, which lives in its companion
      as `RemoteGateManager.state` does, so it needs no injection.
    - `AndroidManifest.xml`: `REQUEST_INSTALL_PACKAGES` and the receiver (`exported="false"`).
    - `remote/RemoteGateViewModel.kt`: inject `AppUpdater`, expose `updateProgress`, and add
      `startUpdate(apkUrl)` and `cancelUpdate()`, which start and cancel one job in
      `viewModelScope`.
    - `presentation/navigation/BlockScreen.kt`: `UpdateDialog` and `BlockScreen` take the
      progress plus `onUpdate` and `onCancel`. **Allow** opens the setting inside the shared
      private `UpdateActions`, so it needs no callback.
    - `presentation/navigation/AuraAppShell.kt`: wire them from `RemoteGateViewModel`.
    - `remote/README.md`: rows for `ApkCheck.kt`, `ApkDownload.kt`, `AppUpdater.kt` and
      `UpdateInstallReceiver.kt`. `presentation/README.md` describes neither screen, so it
      doesn't change.
  - **Logic, `AppUpdater.install`:**
    1. `apkUrl` blank → `Failed("No download link for this version.")`.
    2. `!packageManager.canRequestPackageInstalls()` → `NeedsInstallPermission` and stop.
    3. `downloadApk` to `cacheDir/update/aura-update.apk`, mapping `onPercent` to
       `Downloading`. `false` → `Failed("Download failed. Check your connection.")`. Cancel →
       `Idle`.
    4. `checkUpdateApk(readApkFacts(file), installedApkFacts())`. Anything but `Ok` → `Failed`
       with one line per verdict (e.g. `WrongSigner` → "This file isn't signed by AURA, so it
       wasn't installed."), and the file is deleted.
    5. Open a `PackageInstaller` session (`MODE_FULL_INSTALL`, `setAppPackageName`), copy the
       file in, and commit with an explicit `PendingIntent` (`FLAG_MUTABLE`) to
       `UpdateInstallReceiver`. Delete the file. State → `WaitingForInstall`.
    6. `UpdateInstallReceiver`: `STATUS_PENDING_USER_ACTION` → start the system confirm intent
       (`EXTRA_INTENT`, `FLAG_ACTIVITY_NEW_TASK`). `STATUS_SUCCESS` → nothing (Android replaces
       the process). `STATUS_FAILURE_ABORTED` (the user said no) → `Failed("The update was
       cancelled.")`. Any other status → `Failed("The update wasn't installed.")`, with
       `EXTRA_STATUS_MESSAGE` logged rather than shown, because it is an installer code, not a
       sentence.
  - **Logic, the screens:**
    - The dialog shows when `latest_version_code` is newer and at least one of `apkUrl` or
      `updateUrl` is set. This widens today's check at `AuraAppShell.kt:345`, which requires
      `updateUrl`.
    - **Update** runs the in-app install when `apkUrl` is set. Otherwise it opens `updateUrl`, as
      today.
    - **Download in browser** is shown only when `updateUrl` is set.
    - By `UpdateProgress`:
      - `Idle` → **Not now** / **Update**.
      - `NeedsInstallPermission` → "Allow AURA to install updates, then tap Update again." plus
        **Allow**, which opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for
        `package:<our id>`, and **Update**.
      - `Downloading(n)` → a progress bar showing "Downloading n%", or an indeterminate bar
        showing "Downloading…" when `n` is null, plus **Cancel**. While downloading, the dialog
        can't be dismissed by tapping outside it.
      - `WaitingForInstall` → "Confirm the update on the next screen."
      - `Failed` → the reason, plus **Try again** and **Download in browser**.
    - `BlockScreen` uses the same states, without **Not now**. The release notes block stays as
      it is.
  - **Done when:** this test passes on the phone. The precondition is that the newest release,
    version N, is already published:
    1. In the Firebase console, set `apk_url` to release N's asset link. Step 5 will set this
       value from then on.
    2. Uninstall AURA from the phone. This deletes its memory, skills, settings and trusted MCP
       clients, which is accepted on the test phone. Then build this branch signed with a chosen
       versionCode, without changing any file: `./gradlew :app:assembleRelease
       -Paura.versionBase=B`. That gives versionCode = B + `git rev-list --count HEAD`. Read
       `min_supported_version_code` (call it F) with `firebase remoteconfig:get --project
       aura-mcp-d9450`. Pick B so the versionCode is F + 1, which is above the floor and below N.
       `aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -1` must show that
       versionCode. Install it with `adb install`.
    3. Open AURA. The "Update available" dialog shows version N. Tap Update, then Allow and turn
       the permission on, then tap Update again. The bar goes to 100%, and Android's update screen
       appears. Confirm it. `adb shell dumpsys package com.aura.aura_ui.feature | grep
       versionName` prints `versionName=1.0.N`.
    4. Repeat step 2 with `-Paura.versionBase=0`, which gives a versionCode below F. The
       "Update required" screen appears instead. Turn on airplane mode and tap Update. You see
       "Download failed. Check your connection.", and **Download in browser** opens the release
       page. Turn airplane mode off and tap **Try again**. It installs N as in step 3.
  - **Evidence (2026-11-23, OnePlus CPH2661):** F = 534, N = 592, `apk_url` =
    `https://github.com/Dinesh210805/aura-android-use/releases/download/v1.0.592/aura-1.0.592.apk`
    (Remote Config v22).
    - Step 2–3: `aapt2 dump badging` showed `versionCode='535'`. The "Update available" dialog
      showed 1.0.592; Update → Allow → Update downloaded to 100% and Android's update screen
      installed it. `dumpsys` printed `versionCode=592`, `versionName=1.0.592`,
      `installerPackageName=com.aura.aura_ui.feature`.
    - Step 4: `-Paura.versionBase=0` gave `versionCode='176'`. "Update required" showed with no
      **Not now**. In airplane mode, Update failed with the connection message and offered
      **Download in browser**. With the network back, **Try again** installed it: `dumpsys`
      printed `versionName=1.0.592`, `installerPackageName=com.aura.aura_ui.feature`.

- [ ] **5. The release script sets the link, version name and notes**
  - **What changes:** `release-app.sh` at the repo root. It is git-ignored (`.gitignore:42`) and
    maintainer-only, so **this edit is not committed**. It lives only on the maintainer's machine,
    like the rest of the script. `.claude/skills/releasing-aura-apk/SKILL.md` (also git-ignored
    under `.claude/`, so also local only): step 4
    no longer says to set the version name and notes in the Firebase console, and it mentions
    `apk_url`.
  - **Logic:**
    - In preflight, right after the notes file is found, so a notes mistake stops the run
      before `--publish` tags and pushes: extract the notes from `"$ROOT/$NOTES"`. Take the
      lines after `## What's new` up to the next line that is neither blank nor starts with `- `.
      No bullets → abort. Strip `**` and backticks, turn `- ` into `• `, and
      print them under `dialog notes:`. The `SHA-256:` and `Known limitations:` lines are never
      included.
    - The publish run's Remote Config `node` block also sets `apk_url =
      https://github.com/$RELEASE_REPO/releases/download/$TAG/$APK` (the exact URL the anonymous
      download check just proved), `latest_version_name = $VERSION` and `release_notes` = the
      extracted text. It creates each parameter if the template lacks it.
  - **Done when:** on a clean tree, with `aura.releaseStoreFile` in `aura-android/local.properties`,
    `./release-app.sh _private/release-notes.md` prints `dialog notes:` followed by exactly the
    "What's new" bullets, with no `**` and no `SHA-256` line.
  - **Progress:** both edits are in the local `release-app.sh` and `SKILL.md`. Checked on their
    own: the extraction on `_private/release-notes.md` prints exactly its 4 bullets, a notes file
    with no bullets aborts, and the `node` block on a sample template sets all five keys and
    creates the missing ones. **Not yet run:** the full rehearsal, which refuses a dirty tree;
    run it as the first part of the next release.

- [ ] **6. First real release**
  - **What changes:** nothing in code. Publish the first release that contains this feature,
    install it on the phone, then publish the next release.
  - **Done when:** on the phone, the dialog shows the new version and its bullets. One tap
    downloads and installs it, and the `dumpsys` line from step 4 prints the new version.
    `firebase remoteconfig:get --project aura-mcp-d9450 | grep -A3 apk_url` shows the new tag's
    asset link.
  - **Progress:** not started. It needs step 4 passed and this work released.

## Left out on purpose
- **GitHub API lookup of the asset:** a second source of truth that could disagree with
  `latest_version_code`, and it's rate limited to 60 calls/hour per IP.
- **Resuming a partial download:** a restart costs one more download; add it if users report
  failures on slow links.
- **`DownloadManager` or a foreground service:** only needed for background download, which is
  out of scope.
- **Google Play In-App Updates API:** it works only for apps installed from Play.
- **A new versionCode build flag for testing:** the existing `aura.versionBase` property, passed
  with `-P`, already gives a low versionCode without editing any file.
