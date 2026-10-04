package com.example.youtubeapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.youtubeapp.R
import com.example.youtubeapp.YouTubeApp
import com.example.youtubeapp.data.model.CaptionTrack
import com.example.youtubeapp.data.model.VideoQuality
import com.example.youtubeapp.data.repository.AuthRepository
import com.example.youtubeapp.data.repository.YouTubeRepository
import com.example.youtubeapp.databinding.ActivityPlayerBinding
import com.example.youtubeapp.service.DownloadService
import com.google.android.gms.auth.api.signin.GoogleSignIn
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

    private val youTubeRepository by lazy {
        YouTubeRepository(this)
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
            loadPlayer()
            loadVideoDetails()
            loadCaptionTracks()
        }
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
                evaluateJs("setQuality('${currentQuality.name}')")
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupSubtitleSelector() {
        binding.subtitleSwitch.isChecked = settingsRepository.subtitlesEnabled
        binding.subtitleSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.subtitlesEnabled = isChecked
            evaluateJs("enableSubtitles($isChecked)")
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

    private fun loadVideoDetails() {
        lifecycleScope.launch {
            try {
                val details = youTubeRepository.getVideoDetails(videoId, accessToken)
                details?.let {
                    binding.videoDescription.text = it.video.description
                    binding.viewCount.text = formatViewCount(it.video.viewCount)
                    binding.likeCount.text = formatLikeCount(it.video.likeCount)
                    binding.publishDate.text = it.video.publishedAt
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "Ошибка загрузки деталей: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadCaptionTracks() {
        lifecycleScope.launch {
            try {
                captionTracks = youTubeRepository.getCaptionTracks(videoId, accessToken)
                val languages = captionTracks.map { "${it.languageName}${if (it.isAutoGenerated) " (авто)" else ""}" }
                val adapter = ArrayAdapter(this@PlayerActivity, android.R.layout.simple_spinner_item, languages)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.subtitleSpinner.adapter = adapter

                if (captionTracks.isNotEmpty()) {
                    binding.subtitleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                            val lang = captionTracks[position].languageCode
                            settingsRepository.subtitleLanguage = lang
                            evaluateJs("setSubtitleLanguage('$lang')")
                        }

                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }
                }
            } catch (e: Exception) {
                // Captions not available
            }
        }
    }

    private fun startDownload() {
        val intent = Intent(this, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_VIDEO_ID, videoId)
            putExtra(DownloadService.EXTRA_VIDEO_TITLE, videoTitle)
            putExtra(DownloadService.EXTRA_THUMBNAIL_URL, "")
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_CHANNEL_TITLE = "extra_channel_title"
    }
}
