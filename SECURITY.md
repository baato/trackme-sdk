# Security policy

## Reporting a vulnerability

Please **don't open a public issue** for security problems. Email **support@baato.io** (subject: "Security: TrackMe SDK") with:

- what you found and where (file, endpoint, SDK version),
- steps to reproduce,
- the impact you expect.

We aim to acknowledge reports within 3 working days and to keep you informed until it's fixed.

## Scope

- The TrackMe SDK in this repository (`trackme-sdk/`, `TrackMeLiveActivity/`).
- The sample apps are demos. They log in with an API secret from git-ignored files on the developer's machine;
  a build of a sample app must never be distributed.

## Handling secrets

No secrets belong in this repository. API secrets, map keys and signing credentials live only in git-ignored
files (`local.properties`, `sample-ios/Secrets.xcconfig`, `~/.gradle/gradle.properties`) or in CI secrets.

## Tester builds

The samples log in by themselves with the TrackMe API secret, which is a demo shortcut. A real app's backend logs
in and hands the app a token, so a real app never contains the secret. Anything compiled into an APK or IPA can be
recovered by someone determined, so treat every sample build as containing the secret.

What the Android samples do to keep it from being *read* out of an APK:
- The build stores the secret **XOR-ed with a random pad made for that build** (`TRACKME_SECRET_A` / `_B` in
  `BuildConfig`, generated in `build.gradle.kts`); `DemoAuth` rebuilds it only at login. Searching the APK, or
  opening it in a decompiler such as jadx, shows two arrays of numbers, and no text that matches the secret.
- **What it doesn't stop:** someone who reads `DemoAuth`, hooks the running app (e.g. Frida), or intercepts the login
  request on their own phone can still get the secret. It's obfuscation, not encryption with a key the app doesn't
  have. Only give sample builds to people you'd trust with the secret, and never publish them.
- **Still readable inside the APK:** the Baato map key. Restrict it to the app's package name in the Baato dashboard
  if possible.
- **Check a build:** `unzip -p app-release.apk 'classes*.dex' | grep -c -a -F "$SECRET"` must print `0`.
- **The real fix,** when available: the backend binds logins to a registered app (package name / signing
  certificate) or your own backend issues short-lived tokens, so no app ever holds the business secret.
- Not done yet: the iOS samples and the Flutter / React Native examples still compile the secret in as plain text.
