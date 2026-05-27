package com.example.mindwave.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val PREFS_FILE = "mindwave_auth"
private const val KEY_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_EMAIL = "account_email"

class AuthRepository(context: Context) {

    companion object {
        /**
         * Base URL of the MindWave FastAPI server.
         * 10.0.2.2 is the emulator loopback to dev machine localhost.
         */
        const val BASE_URL = "http://10.0.2.2:8000" // todo: make configurable / change for prod
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun isLoggedIn(): Boolean = prefs.getString(KEY_TOKEN, null) != null

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun logout() = prefs.edit()
        .remove(KEY_TOKEN)
        .remove(KEY_REFRESH_TOKEN)
        .remove(KEY_EMAIL)
        .apply()

    /**
     * Decode JWT to check expiry.
     * Returns payload as JSONObject or null if invalid
     */
    private fun decodeJwtPayload(token: String): JSONObject? {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val decoded = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
            JSONObject(decoded)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if the current access token is expired or will expire within bufferMs.
     * Returns true if token should be refreshed.
     */
    private fun isTokenExpired(bufferMs: Long = 180_000L): Boolean {
        val token = getToken() ?: return true
        val payload = decodeJwtPayload(token) ?: return true
        val expiryTime = payload.optLong("exp", 0) * 1000  // JWT exp is in seconds
        return System.currentTimeMillis() > expiryTime - bufferMs
    }

    /**
     * Refresh the access token using the stored refresh token.
     * On success, update both tokens, otherwise clear them.
     */
    suspend fun refreshToken(): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
                    ?: throw Exception("No refresh token available")

                val conn = (URL("$BASE_URL/auth/refresh").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                val body = JSONObject().apply {
                    put("refresh_token", refreshToken)
                }.toString()
                conn.outputStream.bufferedWriter().use { it.write(body) }

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    val errBody = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    logout()
                    throw Exception(parseDetail(errBody, conn.responseCode))
                }

                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                prefs.edit()
                    .putString(KEY_TOKEN, json.getString("access_token"))
                    .putString(KEY_REFRESH_TOKEN, json.getString("refresh_token"))
                    .apply()
            }
        }

    /**
     * Ensure the access token is valid. If expired or expiring soon, refresh it.
     * Call this before any API call that requires authentication.
     */
    suspend fun ensureValidToken() {
        if (isTokenExpired()) {
            refreshToken().getOrNull()
        }
    }

    /**
     * POST /auth/register/user, used for public self-registration
     * TODO: add validation and err handling (for ex. email already exists)
     */
    suspend fun register(email: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL("$BASE_URL/auth/register/user").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val body = JSONObject().apply {
                    put("email", email.trim())
                    put("password", password)
                }.toString()
                conn.outputStream.bufferedWriter().use { it.write(body) }

                if (conn.responseCode != HttpURLConnection.HTTP_CREATED) {
                    val errBody = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    throw Exception(parseDetail(errBody, conn.responseCode))
                }
            }
        }

     /**
      * POST /auth/login with OAuth2 password-grant form data
      * On success, stores the JWT access token and refresh token in EncryptedSharedPreferences
      */
     suspend fun login(email: String, password: String): Result<Unit> =
         withContext(Dispatchers.IO) {
             runCatching {
                 val conn = (URL("$BASE_URL/auth/login").openConnection() as HttpURLConnection).apply {
                     requestMethod = "POST"
                     setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                     doOutput = true
                     connectTimeout = 10_000
                     readTimeout = 10_000
                 }

                 val body = "username=${URLEncoder.encode(email, "UTF-8")}" +
                         "&password=${URLEncoder.encode(password, "UTF-8")}" +
                         "&grant_type=password"
                 conn.outputStream.bufferedWriter().use { it.write(body) }

                 if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                     val errBody = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                     throw Exception(parseDetail(errBody, conn.responseCode))
                 }

                 val json = JSONObject(conn.inputStream.bufferedReader().readText())
                 prefs.edit()
                     .putString(KEY_TOKEN, json.getString("access_token"))
                     .putString(KEY_REFRESH_TOKEN, json.optString("refresh_token", ""))
                     .putString(KEY_EMAIL, email.trim())
                     .apply()
             }
         }

    private fun parseDetail(body: String, code: Int): String = runCatching {
        JSONObject(body).getString("detail")
    }.getOrDefault("Login failed ($code)")
}
