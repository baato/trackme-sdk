package io.baato.trackme

/**
 * What each platform must hand the SDK at [TrackMe.initialize].
 * Android: wraps a `Context` (needed for location, notifications and the foreground service).
 * iOS: empty, `PlatformContext()`.
 *
 * Another expect/actual example: here the *class* differs per platform, not just a function.
 */
expect class PlatformContext
