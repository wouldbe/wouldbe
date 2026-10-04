package com.example.youtubeapp.data.repository

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
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
        try {
            val token = GoogleAuthUtil.getToken(
                context.applicationContext,
                acct,
                YouTubeScopes.YOUTUBE_READONLY
            )
            cachedToken = token
            Log.i(TAG, "access token acquired: ${token.take(12)}...")
            token
        } catch (e: Exception) {
            // UserRecoverableAuthException / no network / cancelled consent
            Log.w(TAG, "getToken failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun clear() {
        cachedToken = null
    }
}
