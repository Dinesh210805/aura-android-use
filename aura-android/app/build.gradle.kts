// `java` inside a build script resolves to the Java plugin extension, so the
// fully-qualified java.util.Properties is unreachable — import it explicitly.
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
    alias(libs.plugins.google.services)
    alias(libs.plugins.google.firebase.crashlytics)
}

// ─────────────────────────────────────────────────────────────────────────────
// Auto-versioning — derived from git on EVERY build, no manual bumps.
//   versionCode = aura.versionBase + number of commits on HEAD (monotonic on a linear history)
//   versionName = "1.0.<versionCode>"  (+ "-<shortSha>" on debug builds)
// Why the base: this repo's history starts after builds up to 534 had shipped, and
// Android refuses to install a lower versionCode over an existing one.
// Change together: release-app.sh reads the same property for the tag and Remote Config.
// Falls back to a safe default when git is unavailable (CI shallow clone, zip export).
// Uses providers.exec so it stays configuration-cache compatible.
// ─────────────────────────────────────────────────────────────────────────────
fun auraVersionCode(): Int =
    providers.gradleProperty("aura.versionBase").get().toInt() + gitCommitCount()

fun gitCommitCount(): Int = try {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toIntOrNull() ?: 1
} catch (e: Exception) {
    1
}

/**
 * Read a developer-local setting from the git-ignored `local.properties`.
 * Keeps machine-specific values (LAN IPs, dev endpoints) out of the APK that
 * ships to users, while leaving them one line away for local work.
 */
fun localProperty(key: String, default: String): String {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return default
    val props = Properties()
    file.inputStream().use { stream -> props.load(stream) }
    return props.getProperty(key, default)
}

fun gitShortSha(): String = try {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim().ifBlank { "nogit" }
} catch (e: Exception) {
    "nogit"
}

// ─────────────────────────────────────────────────────────────────────────────
// sherpa-onnx wake-word engine — vendored AAR fetch guard.
//   The 28 MB static-link AAR is git-ignored (keeps binaries out of history —
//   see the ktlint-blob incident). This runs at configuration time, BEFORE
//   The direct file dependency resolves after this guard, so a fresh clone / CI
//   downloads it once from the pinned release. Local dev builds skip the
//   download (file present).
// ─────────────────────────────────────────────────────────────────────────────
run {
    val aar = file("libs/sherpa-onnx-static-link-onnxruntime-1.12.28.aar")
    if (!aar.exists()) {
        val url =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.28/" +
                "sherpa-onnx-static-link-onnxruntime-1.12.28.aar"
        logger.lifecycle("Fetching sherpa-onnx wake-word AAR (once) from $url")
        aar.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input ->
            aar.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

android {
    namespace = "com.aura.aura_ui"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aura.aura_ui.feature"
        // Bumped to 26 — required by Netty (HTTPS server engine for the
        // on-device MCP server in :mcp-server). See that module for details.
        minSdk = 26
        targetSdk = 36
        versionCode = auraVersionCode()
        versionName = "1.0.${auraVersionCode()}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // sherpa-onnx wake-word ships a self-contained libsherpa-onnx-jni.so
            // (ONNX Runtime statically linked) for these ABIs — no separate
            // libonnxruntime.so, so it never collides with OmniParser's
            // onnxruntime-android. Legacy 32-bit x86 is intentionally excluded: it
            // is the ONE ABI where the static AAR still bundles a stray
            // libonnxruntime.so.
            //
            // x86_64 is NOT here — it is added in the debug build type below, so
            // emulators keep working while release users stop downloading 64 MB of
            // native code no physical phone can execute. It was measured as the
            // LARGEST ABI in the v1.0.291 APK (64.5 MB of a 221 MB download).
            //
            // Build-type `abiFilters` UNION with this list rather than replacing it,
            // so an ABI can only be added per-variant, never subtracted. That is why
            // the emulator ABI lives in `debug` instead of being excluded in `release`.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        // Legacy Python backend endpoint. EMPTY by default — the on-device MCP
        // server is canonical and the legacy WebSocket path is opt-in
        // (AssistantForegroundService.PREF_LEGACY_PYTHON_BACKEND, default off).
        //
        // A baked-in IP here is a release bug, not a convenience: it ships one
        // developer's LAN/Tailscale address to every user, who then sits through
        // connection timeouts to a host that does not exist for them. Set
        // `aura.defaultServerUrl=http://…` in the git-ignored local.properties
        // for local development instead.
        buildConfigField(
            "String",
            "DEFAULT_SERVER_URL",
            "\"${localProperty("aura.defaultServerUrl", "")}\"",
        )
    }

    // Release signing. Credentials live in the git-ignored `local.properties`, never
    // in git. When the keystore is absent (a fresh clone, CI without secrets) no
    // release config is created and `assembleRelease` produces an UNSIGNED apk
    // instead of failing configuration — so this machine isn't the only one that
    // can build the project. Set in local.properties:
    //   aura.releaseStoreFile=C:/path/outside/the/repo/aura-release.jks
    //   aura.releaseStorePassword=…
    //   aura.releaseKeyAlias=aura
    //   aura.releaseKeyPassword=…
    signingConfigs {
        val storePath = localProperty("aura.releaseStoreFile", "")
        if (storePath.isNotBlank() && file(storePath).exists()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = localProperty("aura.releaseStorePassword", "")
                keyAlias = localProperty("aura.releaseKeyAlias", "")
                keyPassword = localProperty("aura.releaseKeyPassword", "")
            }
        } else {
            logger.lifecycle(
                "No release keystore configured (aura.releaseStoreFile in local.properties) " +
                    "- release builds will be UNSIGNED.",
            )
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
            // Upload native symbols (OmniParser ONNX Runtime, sherpa-onnx JNI) so
            // JNI crashes symbolicate to function names, not raw addresses. The
            // Crashlytics plugin registers this as a per-build-type extension.
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // Stamp the exact commit onto debug builds so a running APK is traceable.
            versionNameSuffix = "-${gitShortSha()}"
            ndk {
                // Emulator ABI, debug only. Kept out of release because it is the
                // largest ABI in the APK and no physical phone can run it.
                abiFilters += "x86_64"
            }
        }
    }
    compileOptions {
        // Bumped 11 → 17 to match :mcp-server and satisfy Koog (koog-agents 1.0.0)
        // and its Ktor-client/coroutines stack, which target JVM 17. D8 desugars
        // to minSdk 26, so there is no device-level impact.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    installation {
        installOptions.add("-d")
    }
    testOptions {
        unitTests {
            // Pure JVM unit tests touch logic that logs via android.util.Log.
            // Without this, unmocked framework calls (e.g. Log.w) throw
            // "Method not mocked"; returning defaults makes them no-ops so
            // Android-light logic (ToolHookChain, ActionGuard, …) stays unit-testable.
            isReturnDefaultValues = true
        }
    }
    packaging {
        jniLibs {
            // The sherpa-onnx static-link AAR is self-contained on every ABI we
            // ship (arm64-v8a/armeabi-v7a/x86_64) — no libonnxruntime.so there.
            // ONLY legacy 32-bit x86 still bundles one, which collides with
            // OmniParser's onnxruntime-android at merge time. x86 is dropped from
            // the APK by abiFilters, so which copy "wins" here is irrelevant — this
            // just lets the merge step succeed.
            pickFirsts += "**/x86/libonnxruntime.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Netty (server engine for the on-device MCP HTTPS listener) ships
            // 13 jars, each with its own META-INF/INDEX.LIST — without these
            // excludes APK packaging fails with "13 files found with path
            // 'META-INF/INDEX.LIST'". The license/notice excludes are belt+
            // suspenders for the same Netty / dependency-jar duplication.
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }

    // ONNX model bytes are already quantized; .aab/.apk compression both
    // bloats install size and slows first-load. Ship raw.
    androidResources {
        @Suppress("UnstableApiUsage")
        noCompress.add("onnx")
    }
    lint {
        // Disable problematic lint detector that has compatibility issues with Kotlin 2.0.21
        disable.add("NullSafeMutableLiveData")
        // Disable other potentially problematic detectors
        disable.add("NonNullableMutableLiveData")
        // Continue on lint errors to prevent build failures
        abortOnError = false
    }
}

dependencies {
    // On-device MCP server (Phase 1 — echo tool only)
    implementation(project(":mcp-server"))

    // On-device AI agent framework — Koog 1.0.0 (JetBrains GA).
    // Umbrella artifact: agent core + MCP integration + provider clients.
    // KMP → Android .aar variant resolved automatically by Gradle.
    implementation(libs.koog.agents)

    // MCP client (0.8.3, version-matched to :mcp-server) — the on-device agent
    // connects to the in-process MCP server as a client. Koog's own agents-mcp
    // is JVM-only + beta (targets SDK 0.11.1), so we wire the client ourselves.
    implementation(libs.mcp.kotlin.sdk.client)

    // Ktor OkHttp engine for Koog's HTTP client. Added explicitly because R8
    // drops the AAR's KoogHttpClient.Factory provider registration on Android;
    // GroqProvider constructs the engine directly instead of relying on
    // classpath auto-resolution.
    implementation(libs.ktor.client.okhttp)

    // Core Android
    implementation(libs.androidx.core.ktx)

    // Remote control + analytics (Approach A) — see
    // docs/superpowers/specs/2026-07-23-remote-control-analytics-design.md
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.crashlytics.ndk)
    implementation(libs.firebase.analytics)
    // Install/device registry — Firestore, not RTDB. The RTDB instance is
    // already load-bearing for WebRTC/PIN signaling, and the Spark plan caps
    // that database at 100 SIMULTANEOUS CONNECTIONS. Putting a per-launch
    // heartbeat on it would spend the same budget the pairing handshake needs,
    // and the failure mode is a silently refused connection. Firestore's free
    // limits are daily operation counts with no connection ceiling.
    implementation(libs.firebase.firestore)
    // Anonymous auth only — no user-visible account. It exists so the security
    // rules have a uid to bind a device to its own document; without it the
    // API key inside the APK would be the only thing standing between anyone
    // and the whole registry.
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.identity.googleid)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    
    // UI and Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.0")
    
    // Traditional Views (for overlays)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.hilt.navigation.compose)
    
    // Dependency Injection - Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    
    // Network
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20230618")

    // Secure storage for the Tavily API key (Phase 6 web_search tool)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Phase 10B — WorkManager for periodic session-log cleanup
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)

    // Phase 7 — biometrics + self-signed cert generation for MCP server TLS
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
    // Phase 7 — QR code rendering for pairing UI
    implementation("com.google.zxing:core:3.5.3")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Audio and Media
    implementation("androidx.media:media:1.7.0")
    
    // Wake Word Detection — sherpa-onnx keyword spotting (free, offline, Apache-2.0).
    // Static-link AAR: ONNX Runtime baked into libsherpa-onnx-jni.so, so it does
    // NOT collide with OmniParser's onnxruntime-android:1.19.0 (that runtime is
    // left untouched). Vendored in app/libs/ (git-ignored, fetched by the guard
    // above).
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.12.28.aar"))
    
    // Data Storage
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // Double Metaphone phonetic matching (Apache Commons Codec)
    implementation("commons-codec:commons-codec:1.16.1")
    
    // Permissions
    implementation("com.guolindev.permissionx:permissionx:1.7.1")
    
    // Animation
    implementation("com.airbnb.android:lottie:6.1.0")
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.3")

    // Phase 5 — on-device perception (Microsoft OmniParser via ONNX Runtime).
    // Standard package: ships CPU + NNAPI + XNNPACK, so per-device calibration can
    // pick the fastest of the three on ANY Android device.
    //
    // NOTE: the `onnxruntime-android-qnn` package was tested for direct Hexagon-NPU
    // access on a Snapdragon 7+ Gen 3 and rejected: (1) on-device the NPU only TIED
    // CPU (~400 ms, no speedup) for this small INT8 model; (2) that package is NOT a
    // superset — it DROPS NNAPI + XNNPACK, which would strip the best engines from
    // non-Qualcomm devices fleet-wide; (3) larger APK. See docs/perceive-bench/REPORT.md §11.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    
    // Testing
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.mockito:mockito-core:5.6.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // A server that accepts a connection and never answers — the only honest way to
    // prove the agent's LLM client gives up on a stalled request instead of hanging.
    // mockwebserver3 (okhttp 5 line) — must track the RESOLVED okhttp version, not the
    // declared 4.12.0: okhttp-android drags the whole graph to 5.x, and the 4.x
    // MockWebServer fails at runtime on the removed `okhttp3.internal.Util`.
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.2.1")
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Drives the browser handoff window through the real input pipeline. Tapping its
    // "I'm done" button by invoking the callback would prove the callback works and
    // nothing about whether the button is reachable in an overlay window on this OEM.
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
