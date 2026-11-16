package ke.co.kopilot.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "kopilot_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "kp-${UUID.randomUUID()}"
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun saveAccessToken(token: String?) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun saveBackendBaseUrl(url: String) {
        val trimmed = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        prefs.edit().putString(KEY_BACKEND_URL, trimmed).apply()
    }

    fun getBackendBaseUrl(defaultUrl: String = "https://kp.kkdes.co.ke/"): String =
        prefs.getString(KEY_BACKEND_URL, null)?.takeIf { it.isNotBlank() } ?: defaultUrl

    fun saveOpenAiApiKey(key: String?) {
        prefs.edit().putString(KEY_OPENAI_API_KEY, key?.trim()?.ifBlank { null }).apply()
    }

    fun getOpenAiApiKey(): String? = prefs.getString(KEY_OPENAI_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveGoogleMapsApiKey(key: String?) {
        prefs.edit().putString(KEY_GOOGLE_MAPS_API_KEY, key?.trim()?.ifBlank { null }).apply()
    }

    fun getGoogleMapsApiKey(): String? = prefs.getString(KEY_GOOGLE_MAPS_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_OPENAI_API_KEY)
            .remove(KEY_GOOGLE_MAPS_API_KEY)
            .remove(KEY_BACKEND_URL)
            .apply()
    }

    fun clearAll() {
        val deviceId = getOrCreateDeviceId()
        prefs.edit().clear().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_BACKEND_URL = "backend_base_url"
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_GOOGLE_MAPS_API_KEY = "google_maps_api_key"
    }
}
