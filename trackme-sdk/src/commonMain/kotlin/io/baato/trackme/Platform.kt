package io.baato.trackme

/**
 * expect/actual in its smallest form: common code *declares* what it needs,
 * each platform source set (androidMain, iosMain) *provides* it.
 * Used here for the User-Agent header; later the same pattern gives us GPS and battery info.
 */
internal expect fun platformName(): String
