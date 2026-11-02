import java.util.Properties

plugins {
    alias(libs.plugins.android.application)   // AGP 9 has Kotlin built in
    alias(libs.plugins.compose.compiler)
}

// DEMO ONLY: the example logs in by itself with the API secret from local.properties (git-ignored).
// A real app gets the access token from its own backend and never ships the secret.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.baato.trackme.example"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.baato.trackme.example"   // installs next to sample-android
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = libs.versions.trackme.get()

        buildConfigField("String", "TRACKME_API_SECRET", "\"${localProps.getProperty("trackme.apiSecret", "")}\"")
        buildConfigField("String", "TRACKME_API_URL", "\"https://tracker.baato.io/trackme/api\"")
        buildConfigField("String", "DEMO_EMAIL", "\"${localProps.getProperty("trackme.demoEmail", "android.example@trackme.dev")}\"")
        buildConfigField("String", "DEMO_NAME", "\"${localProps.getProperty("trackme.demoName", "Android Example")}\"")
        buildConfigField("String", "BAATO_ACCESS_TOKEN", "\"${localProps.getProperty("baato.accessToken", "")}\"")
    }

    // trackme.devTools=true | false in local.properties. Empty or missing = on for debug, off for release.
    val devTools = localProps.getProperty("trackme.devTools")?.trim()?.lowercase()?.toBooleanStrictOrNull()
    buildTypes {
        debug { buildConfigField("boolean", "DEV_TOOLS", (devTools ?: true).toString()) }
        release { buildConfigField("boolean", "DEV_TOOLS", (devTools ?: false).toString()) }
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
    implementation(libs.trackme.sdk)   // com.github.baato.trackme-sdk:trackme-sdk-android, from JitPack

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.maplibre.android)
}
