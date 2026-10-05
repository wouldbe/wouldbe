package com.example.youtubeapp.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Local watch history: seeds for YouTube recommendation feed.
 *
 * YouTube recommends "related" videos through the innertube /next endpoint;
 * chaining them over the videos the user actually watched gives a feed that
 * follows the user's interests instead of a generic query rotation.
 *
 * Besides the order, the *watch time* per video is stored: the article on
 * tuning recommendations points out that watch time is one of the strongest
 * signals, so seeds are picked by accumulated milliseconds first.
 *
 * Watch time is also aggregated per channel ([watchedChannels]) - the feed
 * uses it to rank videos of the channels the user actually watches.
 *
 * In incognito mode nothing is recorded (see [Recommendations]).
 */
object WatchHistory {

    private const val TAG = "WatchHistory"
    private const val PREFS_NAME = "watch_history"
    private const val KEY_IDS = "video_ids"
    private const val KEY_TIMES = "watch_times" // JSON: {"id": ms}
    private const val KEY_CHANNELS = "watch_channels" // JSON: {"UC...": ms}
    private const val MAX_IDS = 30
    private const val MAX_CHANNELS = 60

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Most recent first. */
    fun recentIds(context: Context, limit: Int = 5): List<String> =
        prefs(context).getString(KEY_IDS, "")
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.take(limit)
            ?: emptyList()

    /**
     * Seeds ordered by accumulated watch time (fallback: recency) - the
     * strongest interest signals first.
     */
    fun topIds(context: Context, limit: Int = 5): List<String> {
        val times = times(context)
        val recent = recentIds(context, MAX_IDS)
        val byTime = recent
            .sortedByDescending { times[it] ?: 0L }
            .filter { (times[it] ?: 0L) > 0L }
        val rest = recent.filter { it !in byTime }
        return (byTime + rest).distinct().take(limit)
            .also { Log.i(TAG, "topIds: $it (times=$times)") }
    }

    /** Accumulated watch time per video id, milliseconds. */
    fun times(context: Context): Map<String, Long> {
        val raw = prefs(context).getString(KEY_TIMES, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optLong(it, 0L) }
        } catch (e: Exception) {
            Log.w(TAG, "times parse failed: $e")
            emptyMap()
        }
    }

    fun count(context: Context): Int =
        prefs(context).getString(KEY_IDS, "")?.split(',')?.count { it.isNotBlank() } ?: 0

    /**
     * Accumulated watch time per channel, milliseconds - the ranking signal
     * for "channels you watch" in the home feed.
     */
    fun watchedChannels(context: Context): Map<String, Long> {
        val raw = prefs(context).getString(KEY_CHANNELS, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optLong(it, 0L) }
        } catch (e: Exception) {
            Log.w(TAG, "watchedChannels parse failed: $e")
            emptyMap()
        }
    }

    /**
     * Records a view. [watchedMs] is accumulated per video - pass the real
     * watch time when leaving the player to strengthen the signal. When
     * [channelId] is given, the time is also aggregated per channel.
     */
    fun add(context: Context, videoId: String, watchedMs: Long = 0L, channelId: String = "") {
        if (videoId.isBlank()) return
        if (Recommendations.isIncognito(context)) {
            Log.i(TAG, "incognito: skip history for $videoId")
            return
        }
        val p = prefs(context)
        val current = p.getString(KEY_IDS, "")!!
            .split(',')
            .filter { it.isNotBlank() && it != videoId }
        val updated = (listOf(videoId) + current).take(MAX_IDS)

        val times = times(context).toMutableMap()
        times[videoId] = (times[videoId] ?: 0L) + watchedMs.coerceAtLeast(0L)

        // times are kept for the ids still inside the history window
        val timesJson = JSONObject()
        updated.forEach { id -> timesJson.put(id, times[id] ?: 0L) }

        val editor = p.edit()
            .putString(KEY_IDS, updated.joinToString(","))
            .putString(KEY_TIMES, timesJson.toString())

        if (channelId.isNotBlank()) {
            val channels = watchedChannels(context).toMutableMap()
            channels[channelId] = (channels[channelId] ?: 0L) + watchedMs.coerceAtLeast(0L)
            val channelsJson = JSONObject()
            channels.entries
                .sortedByDescending { it.value }
                .take(MAX_CHANNELS)
                .forEach { channelsJson.put(it.key, it.value) }
            editor.putString(KEY_CHANNELS, channelsJson.toString())
        }

        editor.apply()
        if (watchedMs > 0) {
            Log.i(TAG, "recorded $videoId += ${watchedMs}ms (total=${times[videoId]}ms, channel=$channelId)")
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_IDS).remove(KEY_TIMES).remove(KEY_CHANNELS).apply()
        Log.i(TAG, "history cleared")
    }
}
