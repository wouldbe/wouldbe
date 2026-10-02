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

enum class VideoQuality {
    LOW,      // 144p - 240p
    MEDIUM,   // 360p - 480p
    HD,       // 720p
    FULL_HD,  // 1080p
    FOUR_K    // 2160p
}

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
