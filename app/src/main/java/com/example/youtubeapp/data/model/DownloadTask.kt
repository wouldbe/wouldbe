package com.example.youtubeapp.data.model

data class DownloadTask(
    val id: String,
    val videoId: String,
    val videoTitle: String,
    val thumbnailUrl: String,
    val quality: VideoQuality = VideoQuality.DEFAULT,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Int = 0,
    val filePath: String = "",
    val fileSize: Long = 0,
    val downloadedSize: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Quality preset selected by resolution like in the YouTube quality menu
 * (240p, 360p, 480p, ...).
 *
 * [height] is the target rendition height: the picker takes the tallest
 * format/variant not exceeding it. [targetBitrate] is the fallback bandwidth
 * goal for sources that expose no RESOLUTION data (HLS with BANDWIDTH only).
 */
enum class VideoQuality(val height: Int, val targetBitrate: Int) {
    P240(240, 400_000),
    P360(360, 800_000),
    P480(480, 1_400_000),
    P720(720, 3_000_000),
    P1080(1080, 6_000_000),
    P1440(1440, 12_000_000),
    P2160(2160, 24_000_000);

    /** Menu label in YouTube style, e.g. "360p". */
    val label: String
        get() = "${height}p"

    companion object {
        /** Playback/download default when nothing is stored yet. */
        val DEFAULT: VideoQuality = P1080

        /**
         * Reads a stored preset, migrating the legacy bitrate-based names
         * (LOW/MEDIUM/HD/FULL_HD/FOUR_K) saved by earlier app versions.
         */
        fun fromStored(name: String?): VideoQuality = when (name) {
            "LOW" -> P360
            "MEDIUM" -> P480
            "HD" -> P720
            "FULL_HD" -> P1080
            "FOUR_K" -> P2160
            else -> values().firstOrNull { it.name == name } ?: DEFAULT
        }
    }
}

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
