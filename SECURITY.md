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
in and hands the app a token. Anything compiled into an APK or IPA can be read back out with a decompiler, so:

- **Debug builds** (your own machine, `local.properties` / `Secrets.xcconfig`) may contain the secret. Never share them.
- **Release builds of the Android samples never contain it.** `build.gradle.kts` sets `TRACKME_API_SECRET` to an
  empty string for `release`. A tester types the secret once on the login screen ("Tester key"). It's stored
  encrypted with an AES key that is generated in, and never leaves, the Android Keystore (`TesterKey.kt`).
  Decompiling the APK finds no secret.
- **What it doesn't protect:** the secret still travels in the login request, so a network interceptor on the
  tester's own phone, or a rooted phone, can see it. Treat testers as people who know the secret, and send it to
  them separately from the APK. The only full fix is the real setup: your backend holds the secret and gives the
  app short-lived tokens.
- **Still inside the APK:** the Baato map key (a map style key). Restrict it to the app's package name in the Baato
  dashboard if possible.
- **When the secret can be rotated:** rotate it after a testing round, or if a debug build or a tester key leaked.
- Not done yet: the iOS samples and the Flutter / React Native examples still compile the secret in. Don't hand out
  release builds of those until they get the same tester-key login.
