package com.example.youtubeapp.data.model

data class DownloadTask(
    val id: String,
    val videoId: String,
    val videoTitle: String,
    val thumbnailUrl: String,
    val quality: VideoQuality = VideoQuality.HD,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Int = 0,
    val filePath: String = "",
    val fileSize: Long = 0,
    val downloadedSize: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Quality preset. Selection of a rendition is bitrate-driven: [targetBitrate]
 * is the bitrate (bits per second) the picker aims at, based on the bitrate
 * ladder of the concrete video rather than on its resolution alone.
 */
enum class VideoQuality(val targetBitrate: Int) {
    LOW(600_000),        // ~144p-360p
    MEDIUM(1_500_000),   // ~480p
    HD(4_000_000),       // ~720p
    FULL_HD(8_000_000),  // ~1080p
    FOUR_K(24_000_000);  // ~2160p

    /** Label with the target bitrate, e.g. "HD (4 Мбит/с)". */
    val label: String
        get() {
            val mbps = targetBitrate / 1_000_000.0
            val text = if (mbps >= 10.0) {
                mbps.toLong().toString()
            } else {
                String.format(java.util.Locale.US, "%.1f", mbps).removeSuffix(".0")
            }
            return "$name ($text Мбит/с)"
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
