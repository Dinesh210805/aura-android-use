plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aura.mcp"
    compileSdk = 36

    defaultConfig {
        // 26 for java.util.concurrent.CompletableFuture timeouts and NsdServiceInfo.setAttribute.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,DEPENDENCIES,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
        }
    }
}

dependencies {
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    
    // The PC link: a WebRTC DataChannel, signaled over the local network (see com.aura.mcp.lan).
    implementation(libs.webrtc)

    // SLF4J — Ktor pulls slf4j-api; provide a noop binding so it doesn't crash on Android
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    // ToolPhaseSinkPreservationTest drives the server through a real MCP client.
    testImplementation(libs.mcp.kotlin.sdk.client)
}
