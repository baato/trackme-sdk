import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)              // the KMP compiler + source-set model
    alias(libs.plugins.android.kotlin.multiplatform.library) // AGP 9's KMP-aware Android library plugin
    alias(libs.plugins.kotlin.serialization)              // generates JSON (de)serializers for @Serializable
    `maven-publish`                                       // lets Android apps consume us via mavenLocal / a Maven repo
}

group = "io.baato.trackme"
// Release with: ./gradlew :trackme-sdk:publish -Ptrackme.version=0.3.0  (see docs/05-releasing.md)
version = providers.gradleProperty("trackme.version").getOrElse("0.3.0")

kotlin {
    // expect/actual *classes* (PlatformContext) are Beta; opt in to silence the warning.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // ---------- Android target ----------
    android {
        namespace = "io.baato.trackme"
        compileSdk = libs.versions.android.sdkCompileSdk.get().toInt()   // host apps need this or newer
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Host (JVM) unit tests are opt-in with the new plugin; this makes commonTest run on Android too.
        withHostTest {}

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // ---------- iOS targets ----------
    // iosArm64 = real iPhones, iosSimulatorArm64 = simulator on Apple-silicon Macs.
    // Both get bundled into ONE XCFramework that an Xcode project (or SwiftPM) can drop in.
    val xcfName = "TrackMeSDK"
    val xcf = XCFramework(xcfName)
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = xcfName   // Swift side: `import TrackMeSDK`
            isStatic = true
            // By default Swift may only call Kotlin `suspend` funcs from the main thread; lift that rule.
            binaryOption("objcExportSuspendFunctionLaunchThreadRestriction", "none")
            xcf.add(this)
        }
    }

    // ---------- Source sets (where code + dependencies live) ----------
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            api(libs.kotlinx.coroutines.core)   // api: StateFlow/SharedFlow appear in our public API
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)   // Android HTTP engine
            // Strict, so Ktor can't lift it to an OkHttp that needs compileSdk 37. An app can still choose its own
            // OkHttp with a strict version of its own.
            implementation("com.squareup.okhttp3:okhttp") { version { strictly(libs.versions.okhttp.get()) } }
            implementation(libs.androidx.core)        // LocationManagerCompat, NotificationCompat, ServiceCompat
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)   // iOS HTTP engine (NSURLSession)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// ---------- Publishing (Android / KMP artifacts) ----------
// `publishToMavenLocal` always works. A remote repository is added only when configured, e.g. GitHub Packages,
// in ~/.gradle/gradle.properties (never in the repo):
//   trackme.publish.url=https://maven.pkg.github.com/<owner>/<repo>
//   trackme.publish.user=<github-user>
//   trackme.publish.token=<token with write:packages>
publishing {
    publications.withType<MavenPublication>().configureEach {
        // Complete POM: Maven Central rejects artifacts without license, developers and scm.
        val repoUrl = providers.gradleProperty("trackme.repoUrl").getOrElse("https://github.com/baato/trackme-sdk")
        pom {
            name.set("TrackMe SDK")
            description.set("Live location sharing and trips for Android and iOS, built with Kotlin Multiplatform.")
            url.set(repoUrl)
            licenses {
                license {
                    name.set("TrackMe SDK Source-Available License")
                    url.set("$repoUrl/blob/main/LICENSE")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("baato")
                    name.set("Baato")
                    organization.set("Baato")
                    organizationUrl.set("https://baato.io")
                }
            }
            scm {
                url.set(repoUrl)
                connection.set("scm:git:$repoUrl.git")
                developerConnection.set("scm:git:$repoUrl.git")
            }
        }
    }
    repositories {
        val publishUrl = providers.gradleProperty("trackme.publish.url").orNull
        if (publishUrl != null) {
            maven {
                name = "TrackMe"
                url = uri(publishUrl)
                credentials {
                    username = providers.gradleProperty("trackme.publish.user").orNull
                    password = providers.gradleProperty("trackme.publish.token").orNull
                }
            }
        }
    }
}
