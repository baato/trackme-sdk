package io.baato.trackme

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The token must never go over cleartext, except to a local development server. */
class SecureUrlTest {
    @AfterTest fun tearDown() = TrackMe.dispose()

    @Test fun onlyHttpsOrLocalDevHosts() {
        listOf(
            "https://tracker.baato.io/trackme/api", "wss://tracker.baato.io/trackme", "HTTPS://Example.com",
            "http://localhost:8000/api", "http://127.0.0.1/api", "http://[::1]:8000/api", "http://10.0.2.2:8000/api", "ws://localhost:8000",
        ).forEach { assertTrue(TrackMe.isSecureOrLocal(it), it) }
        listOf(
            "http://tracker.baato.io/trackme/api", "ws://tracker.baato.io/trackme", "http://192.168.1.5/api",
            "http://localhost.evil.com/api", "ftp://example.com", "tracker.baato.io/api", "",
        ).forEach { assertFalse(TrackMe.isSecureOrLocal(it), it) }
    }

    @Test fun initializeRefusesACleartextApiUrl() {
        val r = TrackMe.start(context = null, config = TrackMeConfig(token = "t", apiUrl = "http://tracker.baato.io/trackme/api"), engine = null)
        assertIs<TrackMeError.Validation>(r.errorOrNull())
        assertFalse(TrackMe.isInitialized)
    }
}
