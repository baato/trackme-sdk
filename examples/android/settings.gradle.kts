// Standalone example app: uses the PUBLISHED TrackMe SDK from JitPack, exactly like a customer app.
// (sample-android/ in the repo root is the same app built against the SDK source, for SDK development.)
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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack builds artifacts from any GitHub repo, so only let it serve the TrackMe SDK.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.baato.trackme-sdk") }
        }
    }
}

rootProject.name = "TrackMeExample"
include(":app")
