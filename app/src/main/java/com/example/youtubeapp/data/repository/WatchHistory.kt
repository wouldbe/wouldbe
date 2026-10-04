package com.example.youtubeapp.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * Local watch history: seeds for YouTube recommendation feed.
 *
 * YouTube recommends "related" videos through the innertube /next endpoint;
 * chaining them over the videos the user actually watched gives a feed that
 * follows the user's interests instead of a generic query rotation.
 */
object WatchHistory {

    private const val PREFS_NAME = "watch_history"
    private const val KEY_IDS = "video_ids"
    private const val MAX_IDS = 30

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Most recent first. */
    fun recentIds(context: Context, limit: Int = 5): List<String> =
        prefs(context).getString(KEY_IDS, "")
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.take(limit)
            ?: emptyList()

    fun add(context: Context, videoId: String) {
        if (videoId.isBlank()) return
        val p = prefs(context)
        val current = p.getString(KEY_IDS, "")!!
            .split(',')
            .filter { it.isNotBlank() && it != videoId }
        val updated = (listOf(videoId) + current).take(MAX_IDS)
        p.edit().putString(KEY_IDS, updated.joinToString(",")).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_IDS).apply()
    }
}
