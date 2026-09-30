pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Local vendored AARs (sherpa-onnx static-link wake-word engine). The
        // .aar is git-ignored and fetched from its pinned GitHub release by the
        // configuration-time guard in app/build.gradle.kts — see fetchSherpaAar.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "aura_ui"
include(":app")
include(":mcp-server")
