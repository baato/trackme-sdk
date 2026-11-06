package io.baato.trackme.example

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
 *
 * Where the secret comes from: debug builds compile it in from local.properties (your own machine only). Release
 * builds don't contain it: a tester types it in once and it's kept encrypted on the phone ([TesterKey]).
 */
object DemoAuth {
    private lateinit var appContext: android.content.Context

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /** True when this build has the secret compiled in (debug builds with local.properties). */
    val isEmbedded: Boolean get() = BuildConfig.TRACKME_API_SECRET.isNotBlank()

    private fun secret(): String = BuildConfig.TRACKME_API_SECRET.ifBlank { TesterKey.load(appContext).orEmpty() }

    fun saveTesterKey(secret: String) = TesterKey.save(appContext, secret.trim())
    fun forgetTesterKey() = TesterKey.clear(appContext)
    val hasTesterKey: Boolean get() = TesterKey.load(appContext) != null

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
                .put("api_secret", secret())
                .apply { if (!fullName.isNullOrBlank()) put("full_name", fullName) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) return@runCatching null
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(json).getString("access_token")
        }.getOrNull()
    }

    val isConfigured: Boolean get() = secret().isNotBlank()
}
