package imayx.opendots.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Per-install secrets, held in Keystore-backed EncryptedSharedPreferences.
 *
 * - [ownerToken]: 256-bit random Bearer token sent as OWNER_TOKEN to the
 *   embedded backend. The backend requires it on every `/api/` call
 *   (timingSafeEqual). Never leaves the device, never logged.
 * - Provider keys (Intelligence, OpenAI, …) are stored here too and passed
 *   to Node as env vars; the UI never receives them back.
 */
class TokenStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "opendots_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Returns the existing token or generates + persists a new 256-bit one. */
    fun getOrCreateOwnerToken(): String {
        prefs.getString(KEY_OWNER_TOKEN, null)?.let { return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        // 43 chars, URL-safe, comfortably above the 24-char minimum.
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(KEY_OWNER_TOKEN, token).apply()
        return token
    }

    fun getSecret(key: String): String? = prefs.getString(key, null)

    fun setSecret(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun clearSecret(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val KEY_OWNER_TOKEN = "owner_token"
        // Provider / service credentials (backend-only; never sent to the UI).
        const val KEY_INTELLIGENCE_API_KEY = "intelligence_api_key"
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_OPENAI_MODEL = "openai_model"
        const val KEY_OPENAI_BASE_URL = "openai_base_url"
        const val KEY_PARALLEL_API_KEY = "parallel_api_key"
        const val KEY_VOICE_API_KEY = "voice_api_key"
        const val KEY_VOICE_MODEL = "voice_model"
        const val KEY_BROWSER_SECRET = "browser_secret"
        const val KEY_COMPUTER_SUPERVISOR_TOKEN = "computer_supervisor_token"
        const val KEY_COMPUTER_TOKEN = "computer_token"
    }
}
