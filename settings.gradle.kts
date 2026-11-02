// Where Gradle looks for *plugins* (Kotlin, Android Gradle Plugin).
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

// Where Gradle looks for *libraries* (Ktor, coroutines, ...). Modules may not declare their own repos.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TrackMe"

// The shared KMP library that becomes the Android AAR and the iOS XCFramework.
include(":trackme-sdk")

// Demo app that consumes the SDK as a project dependency (not published).
include(":sample-android")
