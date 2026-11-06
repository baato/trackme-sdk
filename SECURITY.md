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
