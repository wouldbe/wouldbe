package com.example.youtubeapp.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.youtubeapp.data.model.VideoQuality

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var videoQuality: VideoQuality
        get() = VideoQuality.fromStored(prefs.getString(KEY_VIDEO_QUALITY, null))
        set(value) = prefs.edit().putString(KEY_VIDEO_QUALITY, value.name).apply()

    var autoPlay: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PLAY, value).apply()

    var subtitlesEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLES_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SUBTITLES_ENABLED, value).apply()

    var subtitleLanguage: String
        get() = prefs.getString(KEY_SUBTITLE_LANGUAGE, "ru") ?: "ru"
        set(value) = prefs.edit().putString(KEY_SUBTITLE_LANGUAGE, value).apply()

    var downloadWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_DOWNLOAD_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_DOWNLOAD_WIFI_ONLY, value).apply()

    var darkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK_THEME, false)
        set(value) = prefs.edit().putBoolean(KEY_DARK_THEME, value).apply()

    var maxDownloadQuality: VideoQuality
        get() = VideoQuality.fromStored(prefs.getString(KEY_MAX_DOWNLOAD_QUALITY, null))
        set(value) = prefs.edit().putString(KEY_MAX_DOWNLOAD_QUALITY, value.name).apply()

    var bufferSize: Int
        get() = prefs.getInt(KEY_BUFFER_SIZE, 50)
        set(value) = prefs.edit().putInt(KEY_BUFFER_SIZE, value).apply()

    companion object {
        private const val PREFS_NAME = "youtube_app_settings"
        private const val KEY_VIDEO_QUALITY = "video_quality"
        private const val KEY_AUTO_PLAY = "auto_play"
        private const val KEY_SUBTITLES_ENABLED = "subtitles_enabled"
        private const val KEY_SUBTITLE_LANGUAGE = "subtitle_language"
        private const val KEY_DOWNLOAD_WIFI_ONLY = "download_wifi_only"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_MAX_DOWNLOAD_QUALITY = "max_download_quality"
        private const val KEY_BUFFER_SIZE = "buffer_size"
    }
}
