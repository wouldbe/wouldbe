package com.example.youtubeapp.data.repository

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.services.youtube.YouTubeScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides a real OAuth 2.0 access token for YouTube Data API.
 * [com.google.android.gms.auth.api.signin.GoogleSignInAccount.idToken] must NOT be
 * used as an API access token - it is an ID token and causes 401 UNAUTHENTICATED.
 */
object AuthRepository {

    private const val TAG = "AuthRepository"

    @Volatile
    private var cachedToken: String? = null

    suspend fun getAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        cachedToken?.let { return@withContext it }
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: return@withContext null
        val acct = account.account ?: return@withContext null
        val appContext = context.applicationContext

        // Some Play services builds reject the bare scope URL with InvalidRequest
        // and require the oauth2: prefix (and vice versa) - try both.
        val scopes = listOf(
            YouTubeScopes.YOUTUBE_READONLY,
            "oauth2:${YouTubeScopes.YOUTUBE_READONLY}"
        )

        for (scope in scopes) {
            try {
                val token = GoogleAuthUtil.getToken(appContext, acct, scope)
                cachedToken = token
                Log.i(TAG, "access token acquired (${scope.take(12)}...): ${token.take(12)}...")
                return@withContext token
            } catch (e: UserRecoverableAuthException) {
                // Consent screen / re-auth required - cannot be resolved silently
                Log.w(TAG, "getToken needs user recovery: ${e.message}")
                return@withContext null
            } catch (e: GoogleAuthException) {
                Log.w(TAG, "getToken failed for scope=$scope: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "getToken error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        null
    }

    fun clear() {
        cachedToken = null
    }
}
