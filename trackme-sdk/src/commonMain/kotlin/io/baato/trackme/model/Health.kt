package io.baato.trackme.model

import kotlinx.serialization.Serializable

/** Response of `GET {API_URL}/health` → `{"status":"ok"}` */
@Serializable
data class HealthResponse(val status: String) {
    val isOk: Boolean get() = status.equals("ok", ignoreCase = true)
}
