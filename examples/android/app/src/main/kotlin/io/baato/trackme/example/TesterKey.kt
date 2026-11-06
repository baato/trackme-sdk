package io.baato.trackme.example

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DEMO / TESTER BUILDS ONLY: the TrackMe API secret, typed in once by a tester instead of compiled into the APK.
 *
 * Release builds of the samples never contain the secret (see build.gradle.kts), so decompiling the APK finds
 * nothing. The tester enters it on the login screen; it's stored encrypted with an AES key that lives in the
 * Android Keystore and never leaves it, in this app's private storage. It still travels in the login request,
 * so a network interceptor on the tester's own phone can see it: a real app has its backend log in instead.
 */
object TesterKey {
    private const val KEY_ALIAS = "trackme_tester_key"
    private const val PREFS = "trackme_tester"
    private const val VALUE = "secret"

    fun save(context: Context, secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(secret.toByteArray())
        prefs(context).edit().putString(VALUE, "${b64(cipher.iv)}:${b64(sealed)}").apply()
    }

    fun load(context: Context): String? = runCatching {
        val (iv, sealed) = prefs(context).getString(VALUE, null)?.split(":")?.takeIf { it.size == 2 } ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv)))
        String(cipher.doFinal(unb64(sealed)))
    }.getOrNull()

    fun clear(context: Context) = prefs(context).edit().remove(VALUE).apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)
}
