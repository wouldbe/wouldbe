package com.example.youtubeapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.youtubeapp.R
import com.example.youtubeapp.data.model.DownloadStatus
import com.example.youtubeapp.data.model.DownloadTask
import com.example.youtubeapp.data.model.VideoQuality
import com.example.youtubeapp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress

    private val _downloadStatus = MutableStateFlow(DownloadStatus.PENDING)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoId = intent?.getStringExtra(EXTRA_VIDEO_ID) ?: return START_NOT_STICKY
        val videoTitle = intent?.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video"
        val thumbnailUrl = intent?.getStringExtra(EXTRA_THUMBNAIL_URL) ?: ""
        val qualityStr = intent?.getStringExtra(EXTRA_QUALITY) ?: VideoQuality.HD.name
        val quality = VideoQuality.valueOf(qualityStr)

        val downloadTask = DownloadTask(
            id = UUID.randomUUID().toString(),
            videoId = videoId,
            videoTitle = videoTitle,
            thumbnailUrl = thumbnailUrl,
            quality = quality
        )

        startForeground(NOTIFICATION_ID, createNotification(downloadTask, 0))
        startDownload(downloadTask)

        return START_NOT_STICKY
    }

    private fun startDownload(task: DownloadTask) {
        downloadJob = serviceScope.launch {
            try {
                _downloadStatus.value = DownloadStatus.DOWNLOADING

                // Simulate download process
                // In real implementation, you would use YouTube download library
                // like NewPipe or yt-dlp to get the actual video URL
                for (i in 0..100 step 5) {
                    if (!isActive) break
                    _downloadProgress.value = i
                    updateNotification(task, i)
                    delay(500)
                }

                if (isActive) {
                    _downloadStatus.value = DownloadStatus.COMPLETED
                    _downloadProgress.value = 100
                    updateNotification(task, 100)
                }
            } catch (e: Exception) {
                _downloadStatus.value = DownloadStatus.FAILED
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Загрузки",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления о загрузке видео"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(task: DownloadTask, progress: Int): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Загрузка: ${task.videoTitle}")
            .setContentText("Прогресс: $progress%")
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(task: DownloadTask, progress: Int) {
        val notification = createNotification(task, progress)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_THUMBNAIL_URL = "extra_thumbnail_url"
        const val EXTRA_QUALITY = "extra_quality"

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1
    }
}
