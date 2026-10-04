package com.example.youtubeapp.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.youtubeapp.R
import com.example.youtubeapp.YouTubeApp
import com.example.youtubeapp.data.model.CaptionTrack
import com.example.youtubeapp.data.model.VideoQuality
import com.example.youtubeapp.data.repository.AuthRepository
import com.example.youtubeapp.data.repository.StreamRepository
import com.example.youtubeapp.data.repository.YouTubeRepository
import com.example.youtubeapp.databinding.ActivityPlayerBinding
import com.example.youtubeapp.service.DownloadService
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var videoId: String = ""
    private var videoTitle: String = ""
    private var channelTitle: String = ""
    private var accessToken: String? = null
    private var captionTracks: List<CaptionTrack> = emptyList()
    private var currentQuality: VideoQuality = VideoQuality.HD
    private var streamResult: StreamRepository.StreamResult? = null

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private val youTubeRepository by lazy {
        YouTubeRepository(this)
    }

    private val streamRepository by lazy {
        StreamRepository(this)
    }

    private val proxyRepository by lazy {
        (application as YouTubeApp).proxyRepository
    }

    private val settingsRepository by lazy {
        (application as YouTubeApp).settingsRepository
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
        channelTitle = intent.getStringExtra(EXTRA_CHANNEL_TITLE) ?: ""

        if (videoId.isBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            accessToken = AuthRepository.getAccessToken(this@PlayerActivity)
            currentQuality = settingsRepository.videoQuality

            setupUI()
            startPlayback()
            loadVideoDetails()
        }
    }

    /**
     * Primary path: extract stream URLs via the VISIONOS innertube client (works
     * through the web proxy) and play with ExoPlayer.
     * Fallback: legacy YouTube IFrame player in WebView.
     */
    private fun startPlayback() {
        binding.playerStatus.visibility = View.VISIBLE
        binding.playerStatus.text = getString(R.string.player_loading_stream)

        lifecycleScope.launch {
            val data = runCatching { streamRepository.extract(videoId) }.getOrNull()
            var started = false

            if (data != null) {
                started = runCatching {
                    streamResult = data
                    initExoPlayer(data)
                    populateCaptionsFromStream(data)
                    applyStreamDetails(data)
                }.onFailure { e ->
                    Log.e(TAG, "ExoPlayer init failed: $e")
                    releasePlayer()
                }.isSuccess
            } else {
                Log.w(TAG, "stream extraction failed, falling back to IFrame player")
            }

            binding.playerStatus.visibility = View.GONE
            if (!started) {
                binding.playerWebView.visibility = View.VISIBLE
                loadPlayer()
            }
        }
    }

    private fun initExoPlayer(data: StreamRepository.StreamResult) {
        releasePlayer()

        val httpDataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(StreamRepository.VISIONOS_USER_AGENT)
            .setConnectTimeoutMs(25_000)
            .setReadTimeoutMs(30_000)

        val mediaSourceFactory = HlsMediaSource.Factory(httpDataSource)
            .setAllowChunklessPreparation(true)

        val qualityHeight = when (currentQuality) {
            VideoQuality.LOW -> 360
            VideoQuality.MEDIUM -> 480
            VideoQuality.HD -> 720
            VideoQuality.FULL_HD -> 1080
            VideoQuality.FOUR_K -> 2160
        }

        val selector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setMaxVideoSize(3840, 2160))
        }
        trackSelector = selector

        val player = ExoPlayer.Builder(this)
            .setTrackSelector(selector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} ${error.message}")
                Toast.makeText(
                    this@PlayerActivity,
                    "Ошибка воспроизведения: ${error.errorCodeName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        binding.playerView.player = player
        binding.playerView.visibility = View.VISIBLE
        binding.playerWebView.visibility = View.GONE

        applyQualityConstraint(qualityHeight, selector)
        player.setMediaItem(buildMediaItem(data))
        player.prepare()
        player.playWhenReady = settingsRepository.autoPlay

        exoPlayer = player
    }

    private fun buildMediaItem(data: StreamRepository.StreamResult): MediaItem {
        val hls = data.hlsUrl
            ?: data.progressive?.url
            ?: throw IllegalStateException("no playable stream")

        val builder = MediaItem.Builder().setUri(hls)

        val activeCaptions = if (captionTracks.isNotEmpty()) captionTracks else data.captionTracks
        if (settingsRepository.subtitlesEnabled) {
            val selected = activeCaptions.firstOrNull {
                it.languageCode == settingsRepository.subtitleLanguage
            } ?: activeCaptions.firstOrNull()
            if (selected != null) {
                val subtitleUri = android.net.Uri.parse(selected.url)
                    .buildUpon()
                    .appendQueryParameter("fmt", "vtt")
                    .build()
                builder.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                            .setMimeType(MimeTypes.TEXT_VTT)
                            .setLanguage(selected.languageCode)
                            .setLabel(selected.languageName)
                            .build()
                    )
                )
            }
        }
        return builder.build()
    }

    private fun applyQualityConstraint(maxHeight: Int, selector: DefaultTrackSelector) {
        selector.setParameters(
            selector.buildUponParameters().setMaxVideoSize(3840, maxHeight)
        )
    }

    private fun reloadStream() {
        val data = streamResult ?: return
        val player = exoPlayer ?: return
        val position = player.currentPosition
        player.setMediaItem(buildMediaItem(data))
        player.prepare()
        player.seekTo(position)
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        trackSelector = null
    }

    private fun loadPlayer() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { margin: 0; background: #000; }
                    #player { width: 100%; height: 100%; }
                </style>
            </head>
            <body>
                <div id="player"></div>
                <script>
                    var player;
                    var videoId = "$videoId";
                    function onYouTubeIframeAPIReady() {
                        player = new YT.Player('player', {
                            videoId: videoId,
                            playerVars: {
                                autoplay: ${if (settingsRepository.autoPlay) 1 else 0},
                                cc_load_policy: ${if (settingsRepository.subtitlesEnabled) 1 else 0},
                                cc_lang_pref: "${settingsRepository.subtitleLanguage}",
                                rel: 0,
                                modestbranding: 1
                            },
                            events: {
                                onReady: onPlayerReady,
                                onStateChange: onPlayerStateChange
                            }
                        });
                    }
                    function onPlayerReady(event) {
                        setQuality("${currentQuality}");
                    }
                    function onPlayerStateChange(event) {}
                    function setQuality(quality) {
                        var q = {"LOW":"small","MEDIUM":"medium","HD":"hd720","FULL_HD":"hd1080","FOUR_K":"highres"}[quality] || "hd720";
                        if (player && player.setPlaybackQuality) { player.setPlaybackQuality(q); }
                    }
                    function enableSubtitles(enable) {
                        if (!player) return;
                        if (enable) { player.loadModule("captions"); player.setOption("captions", "reload", true); }
                        else { player.unloadModule("captions"); }
                    }
                    function setSubtitleLanguage(lang) {
                        if (player && player.setOption) { player.setOption("captions", "track", {languageCode: lang}); }
                    }
                    function play() { if (player) player.playVideo(); }
                    function pause() { if (player) player.pauseVideo(); }
                </script>
                <script src="https://www.youtube.com/iframe_api"></script>
            </body>
            </html>
        """.trimIndent()

        binding.playerWebView.settings.javaScriptEnabled = true
        binding.playerWebView.settings.domStorageEnabled = true
        binding.playerWebView.addJavascriptInterface(PlayerBridge(), "AndroidBridge")
        binding.playerWebView.loadDataWithBaseURL(
            "https://www.youtube.com",
            html,
            "text/html",
            "utf-8",
            null
        )
    }

    private fun evaluateJs(script: String) {
        if (exoPlayer != null) return
        binding.playerWebView.post {
            binding.playerWebView.evaluateJavascript(script, null)
        }
    }

    private inner class PlayerBridge {
        @android.webkit.JavascriptInterface
        fun onPlayerReady() {}

        @android.webkit.JavascriptInterface
        fun onStateChange(state: Int) {}
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = videoTitle

        binding.videoTitle.text = videoTitle
        binding.channelTitle.text = channelTitle

        setupQualitySelector()
        setupSubtitleSelector()
        setupDownloadButton()
        setupTabs()
    }

    private fun setupQualitySelector() {
        val qualities = VideoQuality.values().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.qualitySpinner.adapter = adapter
        binding.qualitySpinner.setSelection(qualities.indexOf(currentQuality.name))

        binding.qualitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentQuality = VideoQuality.valueOf(qualities[position])
                settingsRepository.videoQuality = currentQuality

                val height = when (currentQuality) {
                    VideoQuality.LOW -> 360
                    VideoQuality.MEDIUM -> 480
                    VideoQuality.HD -> 720
                    VideoQuality.FULL_HD -> 1080
                    VideoQuality.FOUR_K -> 2160
                }
                trackSelector?.let { applyQualityConstraint(height, it) }
                evaluateJs("setQuality('${currentQuality.name}')")
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupSubtitleSelector() {
        binding.subtitleSwitch.isChecked = settingsRepository.subtitlesEnabled
        binding.subtitleSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.subtitlesEnabled = isChecked
            if (exoPlayer != null) reloadStream() else evaluateJs("enableSubtitles($isChecked)")
        }
    }

    private fun setupDownloadButton() {
        binding.downloadButton.setOnClickListener {
            startDownload()
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_info))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_comments))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_related))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showInfoTab()
                    1 -> showCommentsTab()
                    2 -> showRelatedTab()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showInfoTab() {
        binding.infoContainer.visibility = View.VISIBLE
        binding.commentsContainer.visibility = View.GONE
        binding.relatedContainer.visibility = View.GONE
    }

    private fun showCommentsTab() {
        binding.infoContainer.visibility = View.GONE
        binding.commentsContainer.visibility = View.VISIBLE
        binding.relatedContainer.visibility = View.GONE
        // TODO: Load comments
    }

    private fun showRelatedTab() {
        binding.infoContainer.visibility = View.GONE
        binding.commentsContainer.visibility = View.GONE
        binding.relatedContainer.visibility = View.VISIBLE
        // TODO: Load related videos
    }

    /** Fills UI from innertube details when the Data API is unavailable. */
    private fun applyStreamDetails(data: StreamRepository.StreamResult) {
        if (data.description.isNotBlank() && binding.videoDescription.text.isNullOrBlank()) {
            binding.videoDescription.text = data.description
        }
        if (data.viewCount > 0 && binding.viewCount.text.isNullOrBlank()) {
            binding.viewCount.text = formatViewCount(data.viewCount)
        }
        if (data.publishDate.isNotBlank() && binding.publishDate.text.isNullOrBlank()) {
            binding.publishDate.text = data.publishDate.substringBefore('T')
        }
        if (channelTitle.isBlank() && data.author.isNotBlank()) {
            binding.channelTitle.text = data.author
        }
    }

    private fun loadVideoDetails() {
        lifecycleScope.launch {
            try {
                val details = youTubeRepository.getVideoDetails(videoId, accessToken)
                details?.let {
                    binding.videoDescription.text = it.video.description
                    binding.viewCount.text = formatViewCount(it.video.viewCount)
                    binding.likeCount.text = formatLikeCount(it.video.likeCount)
                    binding.publishDate.text = it.video.publishedAt
                } ?: applyStreamDetails(streamResult ?: return@launch)
            } catch (e: Exception) {
                Log.w(TAG, "video details via Data API failed: ${e.message}")
                applyStreamDetails(streamResult ?: return@launch)
            }
        }
    }

    private fun populateCaptionsFromStream(data: StreamRepository.StreamResult) {
        if (data.captionTracks.isEmpty()) return
        captionTracks = data.captionTracks

        val languages = data.captionTracks.map {
            "${it.languageName}${if (it.isAutoGenerated) " (авто)" else ""}"
        }
        val adapter = ArrayAdapter(this@PlayerActivity, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.subtitleSpinner.adapter = adapter

        val selectedIndex = data.captionTracks.indexOfFirst {
            it.languageCode == settingsRepository.subtitleLanguage
        }
        if (selectedIndex >= 0) binding.subtitleSpinner.setSelection(selectedIndex)

        binding.subtitleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settingsRepository.subtitleLanguage = captionTracks[position].languageCode
                if (exoPlayer != null && settingsRepository.subtitlesEnabled) reloadStream()
                else evaluateJs("setSubtitleLanguage('${captionTracks[position].languageCode}')")
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun startDownload() {
        val intent = Intent(this, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_VIDEO_ID, videoId)
            putExtra(DownloadService.EXTRA_VIDEO_TITLE, videoTitle)
            putExtra(DownloadService.EXTRA_THUMBNAIL_URL, streamResult?.thumbnailUrl ?: "")
            putExtra(DownloadService.EXTRA_QUALITY, currentQuality.name)
        }
        startService(intent)
        Toast.makeText(this, "Загрузка началась", Toast.LENGTH_SHORT).show()
    }

    private fun formatViewCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM просмотров", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK просмотров", count / 1_000.0)
            else -> "$count просмотров"
        }
    }

    private fun formatLikeCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> "$count"
        }
    }

    private var resumeAfterResumePending = false

    override fun onResume() {
        super.onResume()
        if (resumeAfterResumePending) {
            resumeAfterResumePending = false
            exoPlayer?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        val playing = exoPlayer?.isPlaying == true
        if (playing) {
            resumeAfterResumePending = true
            exoPlayer?.pause()
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val TAG = "PlayerActivity"

        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_CHANNEL_TITLE = "extra_channel_title"
    }
}
