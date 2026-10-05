package com.example.youtubeapp.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.youtubeapp.data.model.Video

/**
 * Local recommendation controls (see "Как настроить для себя рекомендации
 * Ютуба", Т—Ж):
 *
 *  - "Не интересует" - a video is hidden from every recommendation feed
 *    (undoable);
 *  - "Не рекомендовать видео с этого канала" - the whole channel is excluded;
 *  - incognito mode - watch history is not recorded and the feed is not
 *    personalized (subscriptions and history seeds are skipped).
 *
 * All state is device-local: it filters the assembled home feed, the
 * personalized parts and the "related" list in the player.
 */
object Recommendations {

    private const val TAG = "Recommendations"
    private const val PREFS_NAME = "recommendations"
    private const val KEY_HIDDEN_VIDEOS = "hidden_videos"
    private const val KEY_HIDDEN_CHANNELS = "hidden_channels"
    private const val KEY_INCOGNITO = "incognito"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readSet(context: Context, key: String): Set<String> =
        prefs(context).getString(key, "")
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    private fun writeSet(context: Context, key: String, values: Set<String>) {
        prefs(context).edit().putString(key, values.joinToString(",")).apply()
    }

    // --------------------------------------------------------------- incognito

    fun isIncognito(context: Context): Boolean = prefs(context).getBoolean(KEY_INCOGNITO, false)

    fun setIncognito(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCOGNITO, enabled).apply()
        Log.i(TAG, "incognito=$enabled")
    }

    // ------------------------------------------------------------------ hidden

    fun hiddenVideoIds(context: Context): Set<String> = readSet(context, KEY_HIDDEN_VIDEOS)

    fun hiddenChannelIds(context: Context): Set<String> = readSet(context, KEY_HIDDEN_CHANNELS)

    fun hideVideo(context: Context, videoId: String) {
        if (videoId.isBlank()) return
        writeSet(context, KEY_HIDDEN_VIDEOS, hiddenVideoIds(context) + videoId)
        Log.i(TAG, "hideVideo: $videoId (total=${hiddenVideoIds(context).size})")
    }

    fun unhideVideo(context: Context, videoId: String) {
        writeSet(context, KEY_HIDDEN_VIDEOS, hiddenVideoIds(context) - videoId)
        Log.i(TAG, "unhideVideo: $videoId")
    }

    fun hideChannel(context: Context, channelId: String) {
        if (channelId.isBlank()) return
        writeSet(context, KEY_HIDDEN_CHANNELS, hiddenChannelIds(context) + channelId)
        Log.i(TAG, "hideChannel: $channelId (total=${hiddenChannelIds(context).size})")
    }

    /** "Начать с чистого листа": returns hidden videos/channels to the feed. */
    fun resetHidden(context: Context) {
        prefs(context).edit()
            .remove(KEY_HIDDEN_VIDEOS)
            .remove(KEY_HIDDEN_CHANNELS)
            .apply()
        Log.i(TAG, "resetHidden")
    }

    /** Counts for the settings screen. */
    fun hiddenCounts(context: Context): Pair<Int, Int> =
        hiddenVideoIds(context).size to hiddenChannelIds(context).size

    // ------------------------------------------------------------------ filter

    /** Applies hidden videos/channels to a feed of recommendations. */
    fun filter(context: Context, videos: List<Video>): List<Video> {
        if (videos.isEmpty()) return videos
        val hiddenVideos = hiddenVideoIds(context)
        val hiddenChannels = hiddenChannelIds(context)
        if (hiddenVideos.isEmpty() && hiddenChannels.isEmpty()) return videos
        return videos.filter { it.id !in hiddenVideos && it.channelId !in hiddenChannels }
    }

    /** Filters seed ids (metadata for channels is unknown at seed stage). */
    fun filterSeeds(context: Context, ids: List<String>): List<String> {
        val hidden = hiddenVideoIds(context)
        return if (hidden.isEmpty()) ids else ids.filter { it !in hidden }
    }
}
