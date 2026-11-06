package io.baato.trackme.sample

import android.content.Context
import org.json.JSONObject

/**
 * Remembers who is logged in (the login form, not the token), so reopening the app goes straight back in.
 *
 * The token isn't stored: on a fresh start the sample asks for a new one (DemoAuth, standing in for your backend).
 * A real app keeps its own user session the same way and gets a fresh TrackMe token from its backend.
 */
object SavedLogin {
    private const val PREFS = "trackme_session"
    private const val KEY = "login"

    fun save(context: Context, form: LoginForm) {
        val json = JSONObject()
            .put("email", form.email)
            .put("fullName", form.fullName)
            .put("title", form.notificationTitle)
            .put("text", form.notificationText)
            .put("accent", form.accentColor)
            .put("pause", form.showPause)
            .put("stop", form.showStop)
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }

    fun load(context: Context): LoginForm? = runCatching {
        val json = JSONObject(prefs(context).getString(KEY, null) ?: return null)
        LoginForm(
            email = json.getString("email"),
            fullName = json.optString("fullName"),
            notificationTitle = json.optString("title", "Sharing your location"),
            notificationText = json.optString("text", "Trip members can see where you are."),
            accentColor = json.optLong("accent", 0xFF1565C0),
            showPause = json.optBoolean("pause", true),
            showStop = json.optBoolean("stop", true),
        )
    }.getOrNull()

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
