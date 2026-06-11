package com.davoyans.doinplace.data.remote

import android.content.Context
import android.util.Log
import com.davoyans.doinplace.BuildConfig
import com.davoyans.doinplace.util.DiagLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SupabaseAuthClient(private val context: Context) {

    data class Session(
        val userId: String,
        val email: String,
        val displayName: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long = 0L   // Unix ms; 0 = unknown
    )

    class EmailConfirmationPendingException(email: String) :
        Exception("Check your inbox at $email and click the confirmation link, then sign in.")

    class SessionExpiredException :
        Exception("Session expired. Please log in again.")

    private val prefs by lazy {
        context.getSharedPreferences("supabase_session", Context.MODE_PRIVATE)
    }

    private val baseUrl get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey get() = BuildConfig.SUPABASE_ANON_KEY

    // ── Email / password ───────────────────────────────────────────────────

    fun signUp(email: String, password: String, displayName: String): Result<Session> =
        runCatching {
            val body = JSONObject().apply {
                put("email", email.trim())
                put("password", password)
                put("data", JSONObject().put("display_name", displayName.trim()))
            }
            val json = post("$baseUrl/auth/v1/signup", body)
            if (!json.has("access_token") || json.isNull("access_token")) {
                throw EmailConfirmationPendingException(email.trim())
            }
            parseSession(json, displayName)
        }

    fun signIn(email: String, password: String): Result<Session> =
        runCatching {
            val body = JSONObject().apply {
                put("email", email.trim())
                put("password", password)
            }
            val json = post("$baseUrl/auth/v1/token?grant_type=password", body)
            parseSession(json)
        }

    fun signInWithGoogle(idToken: String): Result<Session> =
        runCatching {
            val body = JSONObject().apply {
                put("provider", "google")
                put("id_token", idToken)
            }
            val json = post("$baseUrl/auth/v1/token?grant_type=id_token", body)
            parseSession(json)
        }

    fun signOut() {
        runCatching {
            val token = prefs.getString(KEY_ACCESS_TOKEN, null)
            if (!token.isNullOrBlank())
                post("$baseUrl/auth/v1/logout", JSONObject(), accessToken = token)
        }
        prefs.edit().clear().apply()
    }

    // ── Session ────────────────────────────────────────────────────────────

    fun getSession(): Session? {
        val userId       = prefs.getString(KEY_USER_ID, null) ?: return null
        val email        = prefs.getString(KEY_EMAIL, "") ?: ""
        val displayName  = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        val accessToken  = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
        val expiresAt    = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return Session(userId, email, displayName, accessToken, refreshToken, expiresAt)
    }

    fun isLoggedIn(): Boolean = getSession() != null
    fun getCurrentUserId(): String? = prefs.getString(KEY_USER_ID, null)
    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun isExpiringSoon(): Boolean {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt == 0L) return false   // expiry unknown — assume ok
        return System.currentTimeMillis() > expiresAt - 60_000L
    }

    /**
     * Returns a valid access token, refreshing first if the stored token
     * is expiring soon. Protected by a lock so concurrent callers don't
     * trigger multiple refresh calls.
     *
     * Throws [SessionExpiredException] if no session exists or refresh fails.
     */
    fun getValidAccessToken(): String {
        if (!isExpiringSoon()) {
            return getAccessToken() ?: throw SessionExpiredException()
        }
        DiagLog.d("AUTH", "token expiring soon — proactive refresh")
        return refreshAndGetToken()
    }

    /**
     * Force-refresh the session (e.g. after receiving a 401). Protected by
     * the same lock as [getValidAccessToken] so only one refresh runs at a time.
     * Other threads wait and then re-read the newly saved token.
     *
     * Pass [force]=true when called after a confirmed 401 so the re-check is
     * skipped — otherwise a token with unknown expiry (expiresAt==0) would fool
     * the re-check into returning the same expired token again.
     *
     * Throws [SessionExpiredException] if the refresh token is missing or invalid.
     */
    fun refreshAndGetToken(force: Boolean = false, failedToken: String? = null): String {
        synchronized(refreshLock) {
            // If another thread already refreshed while we were waiting, its new token will
            // differ from the token that triggered the 401. Skip a redundant refresh.
            if (failedToken != null) {
                val current = getAccessToken()
                if (current != null && current != failedToken) {
                    DiagLog.d("AUTH", "refresh skipped; token already fresh")
                    return current
                }
            } else if (!force && !isExpiringSoon()) {
                return getAccessToken() ?: throw SessionExpiredException()
            }

            val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
            if (refreshToken.isNullOrBlank()) {
                DiagLog.e("AUTH", "refresh failed — no refresh token stored")
                Log.d(TAG, "Refresh failed: no refresh token stored")
                clearSession()
                throw SessionExpiredException()
            }

            DiagLog.d("AUTH", "refresh start")
            Log.d(TAG, "Refreshing Supabase session")
            val body = JSONObject().put("refresh_token", refreshToken)
            return try {
                val json = post("$baseUrl/auth/v1/token?grant_type=refresh_token", body)
                val session = parseSession(json)
                DiagLog.d("AUTH", "refresh success userId=${session.userId.take(8)}")
                Log.d(TAG, "Session refreshed successfully for user=${session.userId}")
                session.accessToken
            } catch (e: Exception) {
                DiagLog.e("AUTH", "refresh failed", e)
                Log.d(TAG, "Session refresh failed: ${e.message}")
                clearSession()
                throw SessionExpiredException()
            }
        }
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun parseSession(json: JSONObject, fallbackName: String = ""): Session {
        val access    = json.getString("access_token")
        val refresh   = json.optString("refresh_token", "")
        val expiresIn = json.optLong("expires_in", 0L)    // seconds
        val expiresAt = if (expiresIn > 0)
            System.currentTimeMillis() + expiresIn * 1000L
        else
            json.optLong("expires_at", 0L).let { if (it > 1_000_000_000_000L) it else it * 1000L }

        val user       = json.getJSONObject("user")
        val userId     = user.getString("id")
        val email      = user.optString("email", "")
        val meta       = user.optJSONObject("user_metadata")
        val name       = meta?.optString("display_name", "").orEmpty().ifBlank { fallbackName }
        val session    = Session(userId, email, name, access, refresh, expiresAt)
        saveSession(session)
        return session
    }

    private fun saveSession(s: Session) {
        prefs.edit()
            .putString(KEY_USER_ID, s.userId)
            .putString(KEY_EMAIL, s.email)
            .putString(KEY_DISPLAY_NAME, s.displayName)
            .putString(KEY_ACCESS_TOKEN, s.accessToken)
            .putString(KEY_REFRESH_TOKEN, s.refreshToken)
            .putLong(KEY_EXPIRES_AT, s.expiresAt)
            .apply()
    }

    private fun post(url: String, body: JSONObject, accessToken: String? = null): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("apikey", anonKey)
        val bearer = accessToken ?: anonKey
        conn.setRequestProperty("Authorization", "Bearer $bearer")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (code !in 200..299) throw IllegalStateException("Auth $code: ${text.take(300)}")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    companion object {
        private const val TAG = "SupabaseAuth"
        private const val KEY_USER_ID       = "user_id"
        private const val KEY_EMAIL         = "email"
        private const val KEY_DISPLAY_NAME  = "display_name"
        private const val KEY_ACCESS_TOKEN  = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT    = "expires_at"

        // Shared across all instances so parallel workers don't each refresh independently
        private val refreshLock = Any()
    }
}
