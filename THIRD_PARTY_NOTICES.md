# Third-party notices

AURA's own code is licensed under the GNU AGPL v3 or later (see [`LICENSE`](LICENSE)), with the
linking permission in [`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md). It includes and depends on
the third-party work listed below, each under its own licence. Where a licence requires its text to
travel with the work, it's in the upstream project linked here or inside the artifact itself.

Last checked 2026-09-26 against `aura-android/app/build.gradle.kts`,
`aura-android/mcp-server/build.gradle.kts` and `aura-android/gradle/libs.versions.toml`.
Update this file whenever a dependency or bundled model changes.

## Models and binaries in the repository

These ship inside the APK (`aura-android/app/src/main/assets/`, `aura-android/app/libs/`) or sit in
the repo.

| What | File(s) | Licence | Source |
|---|---|---|---|
| OmniParser icon detector (YOLO-based) | `assets/omniparser_icon_detect.onnx`, `models/omniparser/icon_detect/` | **AGPL-3.0** | [microsoft/OmniParser](https://github.com/microsoft/OmniParser) (full text: `models/omniparser/icon_detect/LICENSE`) |
| Silero VAD v5 | `assets/silero_vad.onnx` | MIT | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) |
| sherpa-onnx keyword spotter (zipformer, GigaSpeech 3.3M) | `assets/kws/*.onnx` | Apache-2.0 | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| sherpa-onnx runtime (static ONNX Runtime) | `app/libs/sherpa-onnx-static-link-onnxruntime-1.12.28.aar` | Apache-2.0 (ONNX Runtime inside: MIT) | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |

## Runtime libraries: Android app (`:app`)

| Library | Licence |
|---|---|
| Koog agents (`ai.koog`) | Apache-2.0 |
| MCP Kotlin SDK client (`io.modelcontextprotocol:kotlin-sdk-client`) | MIT |
| Ktor client (`io.ktor`) | Apache-2.0 |
| AndroidX: Core, Lifecycle, Activity, Compose, Material 3, Navigation, AppCompat, ConstraintLayout, SplashScreen, Hilt Navigation, Room, WorkManager, DataStore, Media, Biometric, Security Crypto, Credentials | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| Dagger / Hilt | Apache-2.0 |
| OkHttp, Retrofit, Gson converter (`com.squareup`) | Apache-2.0 |
| kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| Lottie for Android (`com.airbnb.android`) | Apache-2.0 |
| ZXing core | Apache-2.0 |
| Apache Commons Codec | Apache-2.0 |
| PermissionX (`com.guolindev.permissionx`) | Apache-2.0 |
| Compose Shimmer (`com.valentinilk.shimmer`) | Apache-2.0 |
| ONNX Runtime for Android (`com.microsoft.onnxruntime`) | MIT |
| Bouncy Castle PKIX (`org.bouncycastle:bcpkix-jdk18on`) | Bouncy Castle Licence (MIT-style) |
| JSON-java (`org.json:json:20230618`) | Public Domain |
| Porcupine Android SDK (`ai.picovoice`) | Apache-2.0 for the SDK. The engine requires a Picovoice AccessKey under Picovoice's terms. *Covered by the linking exception.* |
| Firebase: Analytics, Auth, Firestore, Remote Config, Crashlytics (+NDK) | Google terms / Apache-2.0 per artifact. *Covered by the linking exception.* |
| Google Play services and Sign in with Google (`googleid`, `credentials-play-services-auth`) | Google proprietary terms. *Covered by the linking exception.* |
| Google ML Kit Text Recognition | ML Kit Terms of Service. *Covered by the linking exception.* |

## Runtime libraries: MCP server (`:mcp-server`)

| Library | Licence |
|---|---|
| MCP Kotlin SDK server (`io.modelcontextprotocol:kotlin-sdk-server`) | MIT |
| Ktor, as pulled in by the MCP Kotlin SDK | Apache-2.0 |
| Stream WebRTC Android (`io.getstream:stream-webrtc-android`) | Apache-2.0 (based on Google WebRTC, BSD-3-Clause) |
| SLF4J API + NOP binding | MIT |

## Fonts

**JetBrains Mono** and **Plus Jakarta Sans** (both SIL Open Font License 1.1) are downloaded at
runtime through the Google Fonts provider. They aren't bundled.

## Separately licensed parts of this repository

- **`aura-mcp-connect/`** is licensed **MIT** (see `aura-mcp-connect/LICENSE`), not AGPL. It's
  already published to npm under MIT, and a small desktop bridge is more useful when anyone can
  embed it.

## Note on Picovoice

The Picovoice "Hey Aura" keyword file was removed on 2026-09-26. The wake word runs on the
sherpa-onnx keyword spotter above. The Porcupine SDK is still linked by an unused
`PorcupineWakeWordDetector` class. Deleting that class and the `ai.picovoice` dependency is the
next step, and after that Picovoice can come off this list and out of `LICENSE-EXCEPTION.md`.
