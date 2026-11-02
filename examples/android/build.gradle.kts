plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    // AGP 9 compiles Kotlin itself. Declaring the Kotlin plugin here (not applied) pins the Kotlin version
    // it uses, so it matches the Compose compiler and can read the SDK (built with Kotlin 2.4).
    alias(libs.plugins.kotlin.android) apply false
}
