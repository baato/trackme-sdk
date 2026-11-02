# Releasing the SDK

How the repo goes public on GitHub, and how a version of TrackMe gets from this repo into other apps.

| Platform | Artifact | How apps add it |
|---|---|---|
| Android | `io.baato.trackme:trackme-sdk:<version>` (AAR + Gradle module metadata) | `implementation("io.baato.trackme:trackme-sdk:<version>")` from a Maven repository |
| iOS | `TrackMeSDK.xcframework` (static, device + Apple-silicon simulator) and the `TrackMeLiveActivity` Swift package | SwiftPM: the repo's git URL and a version tag |

The version comes from `-Ptrackme.version=…` (default `0.1.0` in `trackme-sdk/build.gradle.kts`). Use the same number on both platforms.

---

## 1. Before the repo goes public (once)

The security audit of 2 November 2026 found no secrets in the files that would be committed. These items remain:

| # | To do | Why |
|---|---|---|
| 1 | Have Baato's legal counsel review `LICENSE`, and fill in the contacts in `LICENSE` and `SECURITY.md` | It's a draft source-available licence: public to read, no reuse without permission |
| 2 | Paste `~/Documents/TrackMe-backend-issues.md` into a **private** issue for the backend team, then delete the file | It describes unfixed privacy gaps. They were moved out of the public docs |
| 3 | Add the Gradle wrapper checksum: `./gradlew wrapper --gradle-version 9.6.0 --gradle-distribution-sha256-sum <sha256>`. The sha256 for `gradle-9.6.0-bin.zip` is listed at https://gradle.org/release-checksums/ | Gradle then refuses a tampered download |
| 4 | Ask the backend to **rotate the TrackMe API secret** if it was ever shared outside the team (e.g. the usage notes file) | The secret was never in this repo, but it did travel in a document |
| 5 | If Baato's dashboard supports it, restrict the Baato map key to the sample app's package / bundle id | Map keys end up in app binaries |

`*.gpx` files are now git-ignored: `sample-ios/2025-05-01_09-44-58.gpx` is a real recorded track and stays on your Mac.

---

## 2. First commit and push

Run these on your Mac, in Terminal. Git creates and deletes lock files, which doesn't work from the remote shell.

### 2.1 Create the repository locally

```bash
cd ~/Documents/TrackMe
git init -b main
git config user.name  "Your Name"
git config user.email "<id>+<user>@users.noreply.github.com"   # GitHub → Settings → Emails: keeps your address private
```

### 2.2 Stage, then check before committing

```bash
git add -A
git status                      # read the list: ~100 files, no build/, .idea/, local.properties, Secrets.xcconfig, *.gpx

# 1) Nothing secret is staged by name:
git diff --cached --name-only | grep -iE 'local\.properties|secrets\.xcconfig|\.gpx|\.jks|\.keystore|\.p12|\.env' \
  && echo "STOP: remove these" || echo "OK: no secret files staged"

# 2) Ignored files really are ignored (this should list them):
git check-ignore -v local.properties sample-ios/Secrets.xcconfig sample-ios/*.gpx

```

If anything secret shows up, run `git rm --cached <file>`, add it to `.gitignore`, and check again. Then commit, and scan the commit **before pushing**:

```bash
git commit -m "TrackMe SDK 0.1.0: initial import"

brew install gitleaks
gitleaks git -v . || gitleaks detect -v     # newer / older gitleaks; must end with "no leaks found"
```

Nothing has left your Mac yet. If the scan finds something, remove the file (`git rm --cached <file>`, add it to `.gitignore`) and run `git commit --amend`.

### 2.3 Create the GitHub repo **private** first

1. On GitHub, in the **Baato** organisation, choose **New repository**. Name it `trackme-sdk`, make it **Private**, and add no README, licence or .gitignore (the repo has them).
   If you pick another name, also set `trackme.repoUrl=https://github.com/baato/<name>` in `gradle.properties`; it goes into the Maven POM.
2. Push:
   ```bash
   git remote add origin git@github.com:baato/trackme-sdk.git   # or https://github.com/baato/trackme-sdk.git
   git push -u origin main
   ```
3. Look through the repo on GitHub once more. Then do section 3, then switch it to **Public** under Settings → General → Danger zone → Change visibility.

---

## 3. GitHub settings (before switching to public)

| Where | Turn on | Why |
|---|---|---|
| Settings → Code security | **Secret scanning** and **Push protection** | Blocks a push that contains a key or token |
| Settings → Code security | **Dependabot alerts** and **security updates** | Flags vulnerable dependencies (Ktor, OkHttp, MapLibre, …) |
| Settings → Code security | **Private vulnerability reporting** | Matches `SECURITY.md`: reports arrive privately |
| Settings → Rules → Rulesets → branch `main` | Require a pull request, block force-push and deletion | `main` is what releases come from |
| Settings → Rules → Rulesets → tags `*` | Only maintainers may create or delete tags | **A tag is an iOS release**: SwiftPM installs by tag |
| Settings → Actions → General | "Read repository contents" as the default token permission | Least privilege for CI |

**Never** add the TrackMe API secret or the Baato key to GitHub Actions secrets for building the samples. The samples are demos; CI should only run `./gradlew :trackme-sdk:allTests`.

---

## 4. Day-to-day git

```bash
git switch -c feature/per-trip-sharing     # one branch per change
# … work, commit …
git push -u origin feature/per-trip-sharing
# open a pull request on GitHub → review → merge into main
```

- Commit messages: imperative and short (`Add per-trip sharing`), with details in the body.
- Releases are tags on `main`, written **without** a `v`: `0.1.0`, `0.2.0`. SwiftPM expects plain semantic versions.
- Bump the minor version (`0.2.0`) for features and the patch (`0.2.1`) for fixes. Below 1.0, breaking API changes also bump the minor version.

---

## 5. Before every release

```bash
./gradlew :trackme-sdk:allTests                 # Android JVM + iOS simulator
./gradlew :sample-android:assembleDebug          # the sample still compiles against the SDK
cd sample-ios && xcodegen && xcodebuild -scheme TrackMeSample -destination 'generic/platform=iOS Simulator' build
```

Then on real devices: create a trip and join it from a second device. Share with the screen off for 10 minutes or more. Try Pause / Resume / Stop from the notification (Android) and the Live Activity (iOS). Keep `docs/04-integration-guide.md` in sync: it's what integrating developers read.

---

## 6. Android: publishing the library

> **Decision needed: where the AAR lives.** Since **1 October 2026**, Maven Central requires the paid **Publisher Pro** subscription for artifacts of a commercial nature. Sonatype lists *service-dependent SDKs* as commercial, and TrackMe only works with Baato's backend. Closed-source and proprietary licences are allowed on Central ([FAQ](https://central.sonatype.org/faq/closed-source), [publisher tiers](https://central.sonatype.org/news/20260908_publisher_tiers_commercial_use/)). Confirm the classification with Sonatype before relying on the free tier.

**Is TrackMe "commercial"?** The deciding factor is the **TrackMe backend**, not the licence or the repo being public. The Baato map API only appears in the samples. Every SDK call goes to `tracker.baato.io` with an access token exchanged from a Baato-issued API secret. If the TrackMe service is a paid or commercial Baato offering, the SDK is a service-dependent SDK, which means Publisher Pro. If it isn't, ask Sonatype for a review.

| Option | For apps | Cost / effort |
|---|---|---|
| **J. JitPack** (chosen while Central would cost) | One extra line: `maven("https://jitpack.io")` | Free for public repos; no signing; JitPack builds from the git tag. `jitpack.yml` is in the repo |
| **A. Maven Central** | `mavenCentral()`, which every app already has | Publisher Pro subscription; namespace verification (DNS); GPG signing |
| **B. Baato's own Maven repo** (e.g. `https://maven.baato.io/releases`) | One extra line: `maven("https://maven.baato.io/releases")`, public read, no token | Free apart from hosting (S3 / Cloudflare R2 static files, or Reposilite / Nexus). Already supported by this build |
| **C. GitHub Packages** | Every app needs a GitHub token, even for a public package | Free, but awkward for customers |

### J. JitPack

`jitpack.yml` tells JitPack to use JDK 21 (the Gradle daemon requires it), accept the Android SDK licences for compileSdk 37, and run `publishToMavenLocal` for the tag being built. JitPack builds on Linux, so the iOS targets are skipped; iOS ships through SwiftPM (section 7).

1. **Domain:** Baato already publishes `io.baato` packages through JitPack's custom-domain setup, so the same domain applies: coordinates come out under `io.baato`, not `com.github.baato`. If that setup is tied to specific repos, add `baato/trackme-sdk` there too.
2. **Release:** push the tag (e.g. `0.1.0`, the same tag the iOS release uses), then open `https://jitpack.io/#baato/trackme-sdk`, click **Get it** next to the tag, and wait for the build log to go green. Do this yourself straight after tagging, so no customer waits for the first build.
3. **Read the coordinates off the build log.** A KMP library publishes two artifacts: `trackme-sdk`, the multiplatform entry, and `trackme-sdk-android`, the AAR. JitPack lists both with their final group and name. Apps should use the **`trackme-sdk-android`** one, which is a plain AAR with a POM. That works even if JitPack renames the group of the multiplatform entry.
4. **Apps:**
   ```kotlin
   dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
   dependencies { implementation("<group from the JitPack log>:trackme-sdk-android:0.1.0") }
   ```
5. **If the first build fails**, the log says why. The usual causes are a missing Android platform (add `sdkmanager "platforms;android-37"` to `before_install`) or the JDK. Fix it, tag a new patch version, and build again. **Never move a published tag:** iOS uses the same tags.

### A. Maven Central

1. Sign in at https://central.sonatype.com with a Baato account. Add the namespace **`io.baato`** and verify it with the TXT record it shows, added to **baato.io**'s DNS. Set up the subscription.
2. Create a signing key: `gpg --full-generate-key` (RSA 4096). Publish the public key: `gpg --keyserver keys.openpgp.org --send-keys <KEYID>`.
3. Generate a **user token** on central.sonatype.com (Account → Generate User Token).
4. Put the credentials in **`~/.gradle/gradle.properties`** on the release machine. Never put them in the repo's `gradle.properties`:
   ```properties
   mavenCentralUsername=<token user>
   mavenCentralPassword=<token password>
   signing.keyId=<last 8 of KEYID>
   signing.password=<key passphrase>
   signing.secretKeyRingFile=/Users/<you>/.gnupg/secring.gpg   # gpg --export-secret-keys -o ~/.gnupg/secring.gpg
   ```
5. The build needs the Central upload plugin (`com.vanniktech.maven.publish`, which handles KMP and the Central Portal). Not added yet: it's added once option A is chosen.
6. Publish: `./gradlew :trackme-sdk:publishToMavenCentral -Ptrackme.version=0.1.0`, then release the deployment on central.sonatype.com.

### B. Baato's own Maven repository

1. Create the repository: an HTTPS URL where `PUT` uploads need credentials and `GET` downloads are public.
2. In **`~/.gradle/gradle.properties`** (not the repo):
   ```properties
   trackme.publish.url=https://maven.baato.io/releases
   trackme.publish.user=<upload user>
   trackme.publish.token=<upload token>
   ```
3. Publish: `./gradlew :trackme-sdk:publishAllPublicationsToTrackMeRepository -Ptrackme.version=0.1.0`
4. Apps:
   ```kotlin
   dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://maven.baato.io/releases") } }
   dependencies { implementation("io.baato.trackme:trackme-sdk:0.1.0") }
   ```

### Testing locally (any option)

`./gradlew :trackme-sdk:publishToMavenLocal -Ptrackme.version=0.2.0-SNAPSHOT`, then add `mavenLocal()` in the consuming app.

**Consumer requirements:** `minSdk` 24 and `compileSdk` ≥ 37 (OkHttp 5.5 via Ktor). The SDK's manifest merges in the location, foreground-service and notification permissions, plus the service declaration.

---

## 7. iOS: publishing with Swift Package Manager

Apps add **one git URL** (`https://github.com/baato/trackme-sdk`) and get two products: `TrackMeSDK`, the Kotlin framework as a binary target, and `TrackMeLiveActivity`, the Swift UI package. This is free, and the binary is hosted on GitHub Releases.

1. On `main`, with the release commit checked out, build the framework, zip it, compute the checksum and generate `Package.swift`:
   ```bash
   scripts/release-ios.sh 0.1.0 https://github.com/baato/trackme-sdk/releases/download/0.1.0
   ```
   This writes `build/release-ios/0.1.0/TrackMeSDK.xcframework.zip` and a root `Package.swift` with that URL and the zip's checksum.
2. Commit `Package.swift` (`git commit -m "Release 0.1.0"`), tag the commit, and push both:
   ```bash
   git tag 0.1.0 && git push origin main 0.1.0
   ```
3. On GitHub, open **Releases → Draft a new release**, choose tag `0.1.0`, and upload `TrackMeSDK.xcframework.zip`. The download URL must be exactly the one in `Package.swift`.
4. Check it as a customer would: in a new Xcode project, choose *File → Add Package Dependencies…*, paste the repo URL, pick version `0.1.0`, and build.
   - App target: `TrackMeSDK` + `TrackMeLiveActivity`
   - Widget Extension target: `TrackMeLiveActivity`

**Notes**
- The checksum must match the uploaded zip byte for byte. If you rebuild, re-run the script, re-commit, and re-upload. **Never move a tag that's already published**; release a new patch version instead.
- The XCFramework has `ios-arm64` and `ios-arm64-simulator` slices. Intel-Mac simulators aren't included; add `iosX64()` if needed.
- Use SwiftPM only. CocoaPods has announced that its trunk is becoming read-only, so it's not worth adding now.

---

## 8. Release checklist

1. Section 5 passes, and the integration guide is up to date.
2. Android: publish `<version>` (section 6) and check it resolves in a fresh app.
3. iOS: run the script, commit `Package.swift`, tag `<version>`, push, upload the zip to the GitHub release (section 7), then add the package in a fresh Xcode project.
4. Add release notes on the GitHub release: what changed, and anything breaking.
