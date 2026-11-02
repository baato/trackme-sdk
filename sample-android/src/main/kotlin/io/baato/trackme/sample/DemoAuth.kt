package io.baato.trackme.sample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DEMO ONLY: gets an access token by calling `POST /api/v1/auth/login` with the API secret.
 *
 * In a real app this call lives on YOUR backend (the secret must never ship inside an app). The app then
 * receives the token from its backend and passes it to `TrackMe.initialize(...)`. The SDK itself never logs in.
 */
object DemoAuth {
    suspend fun login(email: String, fullName: String?): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("${BuildConfig.TRACKME_API_URL}/v1/auth/login").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject()
                .put("email", email)
                .put("api_secret", BuildConfig.TRACKME_API_SECRET)
                .apply { if (!fullName.isNullOrBlank()) put("full_name", fullName) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) return@runCatching null
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(json).getString("access_token")
        }.getOrNull()
    }

    val isConfigured: Boolean get() = BuildConfig.TRACKME_API_SECRET.isNotBlank()
}
