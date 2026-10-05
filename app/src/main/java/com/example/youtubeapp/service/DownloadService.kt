package com.example.youtubeapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.youtubeapp.R
import com.example.youtubeapp.YouTubeApp
import com.example.youtubeapp.data.model.DownloadStatus
import com.example.youtubeapp.data.model.DownloadTask
import com.example.youtubeapp.data.model.VideoQuality
import com.example.youtubeapp.data.repository.StreamRepository
import com.example.youtubeapp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Real video download: extracts stream URLs via [StreamRepository] (VISIONOS
 * innertube client) and downloads through the configured web proxy. When no
 * progressive (video+audio) format exists, the HLS master manifest is used:
 * the variant matching the selected quality is picked and all segments are
 * concatenated (fMP4: init + m4s) into a single .mp4 file.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _downloadStatus = MutableStateFlow(DownloadStatus.PENDING)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus.asStateFlow()

    private val streamRepository by lazy { StreamRepository(this) }

    private val client: OkHttpClient by lazy {
        (application as YouTubeApp).proxyRepository.buildOkHttpClient(
            OkHttpClient.Builder().build()
        )
    }

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
                _downloadProgress.value = 0
                updateNotification(task, 0)

                val streams = streamRepository.extract(task.videoId)
                val targetDir = getExternalFilesDir(null) ?: filesDir
                val downloadsDir = File(targetDir, "Downloads").apply { mkdirs() }
                val safeTitle = task.videoTitle.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_")
                    .take(60).ifBlank { task.videoId }

                val progressive = streams.progressive
                val outFile: File

                if (progressive != null) {
                    outFile = File(downloadsDir, "$safeTitle.mp4")
                    downloadFile(progressive.url, outFile, task)
                } else {
                    val hls = streams.hlsUrl
                        ?: throw IOException("no downloadable stream")
                    outFile = File(downloadsDir, "$safeTitle.mp4")
                    downloadHls(hls, outFile, task)
                }

                if (isActive) {
                    _downloadProgress.value = 100
                    _downloadStatus.value = DownloadStatus.COMPLETED
                    updateNotification(task, 100)
                    Log.i(TAG, "download completed: ${outFile.absolutePath} (${outFile.length()} bytes)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "download failed: ${e.message}", e)
                _downloadStatus.value = DownloadStatus.FAILED
                updateNotification(task, -1, "Ошибка: ${e.message}")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun downloadFile(url: String, outFile: File, task: DownloadTask) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body?.contentLength() ?: -1L
            val body = response.body?.byteStream() ?: throw IOException("empty body")
            var written = 0L
            val buffer = ByteArray(64 * 1024)
            FileOutputStream(outFile).use { out ->
                while (serviceScope.isActive) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    if (total > 0) {
                        val progress = (written * 100 / total).toInt().coerceIn(0, 99)
                        if (progress != _downloadProgress.value) {
                            _downloadProgress.value = progress
                            updateNotification(task, progress)
                        }
                    }
                }
                out.flush()
            }
        }
    }

    /**
     * Downloads an HLS variant playlist: picks the rendition closest to the
     * selected quality, then concatenates the init segment and all media
     * segments into one fMP4 file.
     */
    private fun downloadHls(masterUrl: String, outFile: File, task: DownloadTask) {
        val targetBitrate = task.quality.targetBitrate

        val master = fetchText(masterUrl)
        val variantUrl = pickVariant(master, masterUrl, targetBitrate)
            ?: throw IOException("no HLS variant")

        val playlist = fetchText(variantUrl)

        // Resolve segment URIs against the playlist URL
        fun resolve(uri: String): String =
            if (uri.startsWith("http")) uri
            else java.net.URI(variantUrl).resolve(uri).toString()

        val segments = mutableListOf<String>()
        var initUri: String? = null
        playlist.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-MAP:") -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(trimmed)?.groupValues?.get(1)
                    if (uri != null) initUri = resolve(uri)
                }
                trimmed.isEmpty() || trimmed.startsWith("#") -> Unit
                else -> segments.add(resolve(trimmed))
            }
        }
        if (segments.isEmpty()) throw IOException("empty HLS playlist")

        Log.i(TAG, "HLS: ${segments.size} segments, init=${initUri != null}")

        FileOutputStream(outFile).use { out ->
            initUri?.let { uri -> appendSegment(uri, out) }
            segments.forEachIndexed { index, uri ->
                if (!serviceScope.isActive) throw IOException("cancelled")
                appendSegment(uri, out)
                val progress = ((index + 1) * 100 / segments.size).coerceIn(0, 99)
                if (progress != _downloadProgress.value) {
                    _downloadProgress.value = progress
                    updateNotification(task, progress)
                }
            }
            out.flush()
        }
    }

    private fun appendSegment(url: String, out: FileOutputStream) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("segment HTTP ${response.code}")
            val body = response.body?.byteStream() ?: throw IOException("empty segment")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = body.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        }
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", StreamRepository.VISIONOS_USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("empty body")
        }
    }

    /**
     * Picks the HLS rendition whose BANDWIDTH is closest to [targetBitrate]
     * without exceeding it (falls back to the smallest rendition otherwise).
     */
    private fun pickVariant(master: String, masterUrl: String, targetBitrate: Int): String? {
        val lines = master.lines()
        // bandwidth (bps), height, uri
        val candidates = mutableListOf<Triple<Int, Int, String>>()
        var pendingBandwidth: Int? = null
        var pendingHeight = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                pendingBandwidth = Regex("BANDWIDTH=(\\d+)")
                    .find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                pendingHeight = Regex("RESOLUTION=\\d+x(\\d+)")
                    .find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingBandwidth != null) {
                candidates.add(Triple(pendingBandwidth, pendingHeight, trimmed))
                pendingBandwidth = null
                pendingHeight = 0
            }
        }
        if (candidates.isEmpty()) return null

        val chosen = candidates.filter { it.first in 1..targetBitrate }.maxByOrNull { it.first }
            ?: candidates.minByOrNull { it.first }!!
        Log.i(TAG, "HLS variant: ${chosen.first} bps (target $targetBitrate bps, ${chosen.second}p)")
        val uri = chosen.third
        return if (uri.startsWith("http")) uri else java.net.URI(masterUrl).resolve(uri).toString()
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

    private fun createNotification(task: DownloadTask, progress: Int, text: String? = null): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val content = text ?: "Прогресс: $progress%"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Загрузка: ${task.videoTitle}")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (progress in 0..100 && text == null) {
            builder.setProgress(100, progress, false)
        } else if (progress < 0) {
            builder.setProgress(0, 0, false)
            builder.setOngoing(false)
        }
        return builder.build()
    }

    private fun updateNotification(task: DownloadTask, progress: Int, text: String? = null) {
        val notification = createNotification(task, progress, text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "DownloadService"

        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_THUMBNAIL_URL = "extra_thumbnail_url"
        const val EXTRA_QUALITY = "extra_quality"

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1
    }
}
