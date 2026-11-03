import java.util.Properties

plugins {
    alias(libs.plugins.android.application)   // AGP 9 has Kotlin built in: no org.jetbrains.kotlin.android needed
    alias(libs.plugins.compose.compiler)
}

// DEMO ONLY: the sample logs in by itself with the API secret from local.properties (never committed).
// A real app gets the access token from its own backend and never ships the secret.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.baato.trackme.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.baato.trackme.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.2.0"

        buildConfigField("String", "TRACKME_API_SECRET", "\"${localProps.getProperty("trackme.apiSecret", "")}\"")
        buildConfigField("String", "TRACKME_API_URL", "\"https://tracker.baato.io/trackme/api\"")
        // Pre-fills the demo login form (handy on emulators). Override in local.properties.
        buildConfigField("String", "DEMO_EMAIL", "\"${localProps.getProperty("trackme.demoEmail", "android.sample@trackme.dev")}\"")
        buildConfigField("String", "DEMO_NAME", "\"${localProps.getProperty("trackme.demoName", "Android Sample")}\"")
        // Baato map style key for the live map (https://api.baato.io/api/v1/styles/breeze?key=…). Kept out of git.
        buildConfigField("String", "BAATO_ACCESS_TOKEN", "\"${localProps.getProperty("baato.accessToken", "")}\"")
    }

    // DEV_TOOLS = show the Developer screens next to the Live map.
    // local.properties: trackme.devTools=true | false. Empty or missing = on for debug, off for release
    // (same rule as TRACKME_DEV_TOOLS in the iOS Secrets.xcconfig). Anything else falls back to that default too.
    val devTools = localProps.getProperty("trackme.devTools")?.trim()?.lowercase()?.toBooleanStrictOrNull()
    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_TOOLS", (devTools ?: true).toString())
        }
        release {
            buildConfigField("boolean", "DEV_TOOLS", (devTools ?: false).toString())
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":trackme-sdk"))   // in a real app: implementation("com.github.baato.trackme-sdk:trackme-sdk-android:0.2.0")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.maplibre.android)   // the live map (Baato breeze style)
}
