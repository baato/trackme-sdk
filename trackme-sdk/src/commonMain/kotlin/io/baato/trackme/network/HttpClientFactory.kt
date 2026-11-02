package io.baato.trackme.network

import io.baato.trackme.TrackMeConfig
import io.baato.trackme.platformName
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal val TrackMeJson = Json {
    ignoreUnknownKeys = true   // server can add fields without breaking SDK versions already shipped
    explicitNulls = false      // don't send `"name": null` for optional request fields
    encodeDefaults = true
}

/**
 * Builds the Ktor client used by one SDK session.
 *
 * Auth: Ktor's Bearer plugin sends `Authorization: Bearer <token>` on every request.
 * On a 401 it calls [refreshToken] once, with concurrent requests sharing that single refresh,
 * and retries the request with the new token.
 *
 * `engine == null` → Ktor picks the platform engine (OkHttp on Android, Darwin on iOS). Tests pass a MockEngine.
 */
internal fun createHttpClient(
    config: TrackMeConfig,
    tokens: TokenStore,
    refreshToken: suspend () -> String?,
    engine: HttpClientEngine? = null,
): HttpClient {
    val setup: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = true   // non-2xx → exception, which the SDK maps to a TrackMeError
        install(ContentNegotiation) { json(TrackMeJson) }
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            connectTimeoutMillis = 10_000
        }
        install(Auth) {
            bearer {
                loadTokens { tokens.current()?.let { BearerTokens(it, null) } }
                refreshTokens {
                    refreshToken()?.let { BearerTokens(it, null) }   // null → original 401 is returned
                }
                sendWithoutRequest { true }   // attach the token up front, don't wait for a 401 challenge
            }
        }
        defaultRequest {
            url(config.apiUrl.trimEnd('/') + "/")   // so get("v1/trips") → …/api/v1/trips
            headers.append(HttpHeaders.UserAgent, "TrackMeSDK (${platformName()})")
        }
    }
    return if (engine != null) HttpClient(engine, setup) else HttpClient(setup)
}
