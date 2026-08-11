package com.tdee.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
private const val NEEDS_RECONNECT_KEY = "drive_needs_reconnect"

/** Thrown by [DriveAuth.token] when Drive access needs user consent (no silent token available). */
class NeedsAuthorizationException(val intentSender: IntentSender) :
    Exception("Google Drive authorization required")

/**
 * Supplies OAuth access tokens for the Drive `drive.file` scope via [AuthorizationClient]
 * (authorization only — no sign-in identity, so this deliberately avoids the deprecated
 * `GoogleSignIn` API).
 *
 * Uses the app's existing settings prefs file (same one [com.tdee.app.ui.theme.ThemeStore] uses)
 * only to persist [needsReconnect]; the token itself is cached in memory only (see [token]).
 */
class DriveAuth(private val context: Context, private val prefs: SharedPreferences) {

    // In-memory only, never persisted: GMS tokens are short-lived and GMS is itself the durable
    // cache, so writing one to disk would store a needless secret at rest.
    @Volatile private var cachedToken: String? = null

    var needsReconnect: Boolean
        get() = prefs.getBoolean(NEEDS_RECONNECT_KEY, false)
        set(value) = prefs.edit().putBoolean(NEEDS_RECONNECT_KEY, value).apply()

    /**
     * Returns a cached token, else silently re-authorizes. Throws [NeedsAuthorizationException]
     * if user consent is required (caller launches the returned [IntentSender] and passes the
     * result to [onAuthorizationResult]).
     *
     * ponytail: 401-as-expiry instead of tracking expiresIn; add expiry tracking if the extra
     * round-trip shows up in logs.
     */
    suspend fun token(): String {
        cachedToken?.let { return it }

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        if (result.hasResolution()) {
            throw NeedsAuthorizationException(result.pendingIntent!!.intentSender)
        }
        val token = result.accessToken!!
        cachedToken = token
        return token
    }

    /** Clears the cached token; called by [DriveClient]'s `onUnauthorized` on a 401. */
    fun invalidate() {
        cachedToken = null
    }

    /**
     * Called by the UI after the consent flow (from [NeedsAuthorizationException.intentSender])
     * returns. Extracts and caches the resulting token. Returns false on failure.
     */
    fun onAuthorizationResult(intent: Intent?): Boolean {
        val result = runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
        }.getOrNull() ?: return false
        val token = result.accessToken ?: return false
        cachedToken = token
        return true
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
