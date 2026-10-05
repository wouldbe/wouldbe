package com.example.youtubeapp.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.youtubeapp.R
import com.example.youtubeapp.YouTubeApp
import com.example.youtubeapp.data.model.SearchResult
import com.example.youtubeapp.data.model.Video
import com.example.youtubeapp.data.repository.AuthRepository
import com.example.youtubeapp.data.repository.FeedRepository
import com.example.youtubeapp.data.repository.Recommendations
import com.example.youtubeapp.data.repository.WatchHistory
import com.example.youtubeapp.data.repository.YouTubeRepository
import com.example.youtubeapp.databinding.ActivityMainBinding
import com.example.youtubeapp.ui.adapter.VideoAdapter
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.youtube.YouTubeScopes
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"
        const val FEED_MAX_ITEMS = 40
        const val SEEDS_PER_SOURCE = 2   // subscription uploads used as seeds
        const val SEED_LIMIT = 5         // /next requests per feed load
        const val MAX_PER_CHANNEL = 5
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var googleSignInClient: GoogleSignInClient
    private var accessToken: String? = null
    private var currentQuery: String = ""
    private var nextPageToken: String? = null
    private var isLoading = false

    /** True when the current list came from [FeedRepository] (innertube pagination). */
    private var usingFeedSource = false

    /** True when the last home load had a personalized (subs + watch) part. */
    private var personalizedFeed = false

    /**
     * Unfiltered copy of the current feed (search results or home feed).
     * `videoAdapter` always shows [feedSnapshot] with hidden videos/channels
     * removed, so the snapshot is needed to bring an undone video back.
     */
    private var feedSnapshot: List<Video> = emptyList()

    private val youTubeRepository by lazy {
        YouTubeRepository(this)
    }

    /**
     * Innertube fallback: the Data API key is blocked (API_KEY_SERVICE_BLOCKED)
     * and the guest home feed is empty, so search/trending fall back to
     * [FeedRepository] when the official API returns nothing.
     */
    private val feedRepository by lazy {
        FeedRepository(this)
    }

    private val settingsRepository by lazy {
        (application as YouTubeApp).settingsRepository
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(Exception::class.java)
            Log.i(TAG, "SIGN_IN_OK account=${account.email}")
            lifecycleScope.launch {
                accessToken = AuthRepository.getAccessToken(this@MainActivity)
                Log.i(TAG, "SIGN_IN token acquired=${accessToken != null}")
                loadUserData()
                loadTrendingVideos()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SIGN_IN_FAILED code=${(e as? com.google.android.gms.common.api.ApiException)?.statusCode} msg=${e.message}", e)
            Toast.makeText(this, "Ошибка авторизации: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupSearchView()
        setupSwipeRefresh()
        setupGoogleSignIn()
        checkExistingSignIn()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        updateSubtitle()

        binding.signInButton.setOnClickListener {
            signIn()
        }
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onVideoClick = { video -> openPlayer(video) },
            onMenuClick = { video, anchor ->
                VideoMenu.show(this, anchor, binding.recyclerView, video) {
                    refreshFeed()
                }
            }
        )

        val spanCount = if (resources.configuration.screenWidthDp > 600) 3 else 2
        binding.recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        binding.recyclerView.adapter = videoAdapter

        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as GridLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                if (!isLoading && nextPageToken != null) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                        && firstVisibleItemPosition >= 0
                    ) {
                        loadMoreVideos()
                    }
                }
            }
        })
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    currentQuery = it
                    searchVideos(it)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean = false
        })
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            if (currentQuery.isNotBlank()) {
                searchVideos(currentQuery)
            } else {
                loadTrendingVideos()
            }
        }
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .requestScopes(Scope(YouTubeScopes.YOUTUBE_READONLY))
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun checkExistingSignIn() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            lifecycleScope.launch {
                accessToken = AuthRepository.getAccessToken(this@MainActivity)
                loadUserData()
                loadTrendingVideos()
            }
        } else {
            loadTrendingVideos()
        }
    }

    private fun loadUserData() {
        updateUIForSignedInUser()
    }

    private fun updateUIForSignedInUser() {
        binding.signInContainer.visibility = View.GONE
        updateSubtitle()
    }

    /**
     * Personalized part of the home feed - selection like YouTube does it:
     *  1. uploads of subscribed channels (OAuth only, no API key);
     *  2. YouTube watch-next recommendations seeded by BOTH signals at once:
     *     the strongest local watch-history videos (what the user watched)
     *     and the newest uploads of the subscriptions (what YouTube would
     *     recommend around the subscriptions);
     *  3. ranking: channels the user actually watches first (by accumulated
     *     watch time), then subscription uploads interleaved with pure
     *     recommendations so both stay visible, with a per-channel cap.
     * Empty when signed out, in incognito and with nothing watched.
     */
    private suspend fun personalizedParts(): List<Video> {
        if (Recommendations.isIncognito(this)) {
            Log.i(TAG, "personalized: skipped (incognito)")
            personalizedFeed = false
            return emptyList()
        }
        Log.i(TAG, "personalized: start (token=${accessToken != null})")

        val subscriptions = accessToken?.let { token ->
            runCatching { youTubeRepository.getSubscriptionUploads(token) }
                .onFailure { Log.w(TAG, "subscription feed failed: ${it.message}") }
                .getOrDefault(emptyList())
        } ?: emptyList()

        // history seeds: picked by accumulated watch time (strongest interest
        // signal); subscription seeds: recommendations "around" the channels
        // the user follows. Hidden videos never seed the recommendations.
        val historySeeds = Recommendations.filterSeeds(this, WatchHistory.topIds(this, 3))
        val subscriptionSeeds = subscriptions.take(SEEDS_PER_SOURCE).map { it.id }
        val seeds = (historySeeds + subscriptionSeeds).distinct().take(SEED_LIMIT)
        val recommendations = if (seeds.isNotEmpty()) {
            feedRepository.recommendations(seeds)
        } else emptyList()

        val subscriptionIds = subscriptions.mapTo(HashSet()) { it.id }
        val parts = Recommendations.filter(
            this,
            (subscriptions + recommendations).distinctBy { it.id }
        )
        val watchedChannels = WatchHistory.watchedChannels(this)
        val ranked = rankFeed(parts, subscriptionIds, watchedChannels)
        personalizedFeed = ranked.isNotEmpty()
        // composition signature of the top of the feed: W=watched channel,
        // S=subscription upload, R=pure recommendation
        val signature = ranked.take(12).joinToString("") { v ->
            when {
                (watchedChannels[v.channelId] ?: 0L) > 0L -> "W"
                v.id in subscriptionIds -> "S"
                else -> "R"
            }
        }
        Log.i(
            TAG,
            "personalized: subs=${subscriptions.size} recs=${recommendations.size} " +
                "seeds=${seeds.size} ranked=${ranked.size} top=$signature"
        )
        return ranked
    }

    /**
     * Feed ranking in the YouTube spirit:
     *  - videos of channels the user watches go first (by watch time);
     *  - the rest - subscription uploads interleaved round-robin with
     *    watch-based recommendations, so neither signal crowds the other out;
     *  - at most [MAX_PER_CHANNEL] videos of one channel (diversity).
     */
    private fun rankFeed(
        videos: List<Video>,
        subscriptionIds: Set<String>,
        watchedChannels: Map<String, Long>
    ): List<Video> {
        val watchedGroup = ArrayList<Video>()
        val subscriptionsGroup = ArrayList<Video>()
        val recommendationsGroup = ArrayList<Video>()
        for (video in videos) {
            when {
                (watchedChannels[video.channelId] ?: 0L) > 0L -> watchedGroup.add(video)
                video.id in subscriptionIds -> subscriptionsGroup.add(video)
                else -> recommendationsGroup.add(video)
            }
        }
        watchedGroup.sortByDescending { watchedChannels[it.channelId] ?: 0L }
        val mixed = interleave(subscriptionsGroup, recommendationsGroup)
        Log.i(
            TAG, "rankFeed: watchedChannels=${watchedGroup.size} " +
                "subscriptions=${subscriptionsGroup.size} " +
                "recommendations=${recommendationsGroup.size} -> ${mixed.size} interleaved"
        )
        return capPerChannel(watchedGroup + mixed)
    }

    /** Round-robin merge: a[0], b[0], a[1], b[1]... */
    private fun interleave(a: List<Video>, b: List<Video>): List<Video> {
        val out = ArrayList<Video>(a.size + b.size)
        var i = 0
        while (i < a.size || i < b.size) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
            i++
        }
        return out
    }

    /** Keeps order, dropping videos beyond [max] from the same channel. */
    private fun capPerChannel(videos: List<Video>, max: Int = MAX_PER_CHANNEL): List<Video> {
        val counts = HashMap<String, Int>()
        return videos.filter { video ->
            val n = counts[video.channelId] ?: 0
            if (n >= max) return@filter false
            counts[video.channelId] = n + 1
            true
        }
    }

    /** Applies hidden videos/channels to the feed and repopulates the list. */
    private fun refreshFeed() {
        val filtered = Recommendations.filter(this, feedSnapshot)
        Log.i(TAG, "refreshFeed: ${feedSnapshot.size} -> ${filtered.size} videos")
        videoAdapter.submitList(filtered)
    }

    /**
     * Personalized home feed when the Data API is unavailable:
     * personalized parts plus a rotating search feed as filler so the list
     * always has content.
     */
    private suspend fun loadPersonalizedFeed(): SearchResult {
        val parts = personalizedParts()
        val filler = feedRepository.home()
        val merged = (parts + filler.videos)
            .distinctBy { it.id }
            .take(FEED_MAX_ITEMS)
        return SearchResult(merged, filler.nextPageToken)
    }

    private fun loadTrendingVideos() {
        Log.i(TAG, "loadTrendingVideos: begin")
        showLoading(true)
        lifecycleScope.launch {
            try {
                var result = youTubeRepository.getTrendingVideos(accessToken)
                var personalCount = 0
                Log.i(TAG, "loadTrendingVideos: got ${result.videos.size} videos (Data API)")
                if (result.videos.isEmpty()) {
                    result = loadPersonalizedFeed()
                    usingFeedSource = true
                } else {
                    usingFeedSource = false
                    // personalization on top of the official (non-personal) feed
                    val personal = personalizedParts()
                    personalCount = personal.size
                    if (personal.isNotEmpty()) {
                        result = SearchResult(
                            (personal + result.videos).distinctBy { it.id }.take(FEED_MAX_ITEMS),
                            result.nextPageToken
                        )
                    }
                }
                // hidden videos/channels never reach the home feed
                feedSnapshot = result.videos
                val visible = Recommendations.filter(this@MainActivity, result.videos)
                videoAdapter.submitList(visible)
                nextPageToken = result.nextPageToken
                Log.i(
                    TAG, "loadTrendingVideos: displayed ${visible.size} videos " +
                        "(personal=$personalCount), pageToken=${nextPageToken != null}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadTrendingVideos failed: ${e.javaClass.simpleName}: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Ошибка загрузки: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun searchVideos(query: String) {
        showLoading(true)
        nextPageToken = null
        lifecycleScope.launch {
            try {
                var result = youTubeRepository.searchVideos(query, accessToken)
                if (result.videos.isEmpty()) {
                    Log.i(TAG, "search: Data API empty/blocked, using innertube search")
                    result = feedRepository.search(query)
                    usingFeedSource = true
                } else {
                    usingFeedSource = false
                }
                feedSnapshot = result.videos
                // «Не интересует» applies to search results as well
                val visible = Recommendations.filter(this@MainActivity, result.videos)
                videoAdapter.submitList(visible)
                nextPageToken = result.nextPageToken
                Log.i(TAG, "searchVideos: displayed ${visible.size} videos for '$query', pageToken=${nextPageToken != null}")
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Ошибка поиска: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun loadMoreVideos() {
        if (isLoading || nextPageToken == null) return
        isLoading = true
        showLoading(true)

        lifecycleScope.launch {
            try {
                val prevToken = nextPageToken
                val result = when {
                    usingFeedSource ->
                        feedRepository.continueSearch(prevToken!!)
                    currentQuery.isNotBlank() ->
                        youTubeRepository.searchVideos(currentQuery, accessToken, prevToken)
                    else ->
                        youTubeRepository.getTrendingVideos(accessToken, pageToken = prevToken)
                }
                // a personalized home feed keeps growing from watch-based
                // recommendations seeded by the tail of the current list
                val expanded =
                    if (personalizedFeed && currentQuery.isBlank() && !usingFeedSource) {
                        val tail = Recommendations.filterSeeds(
                            this@MainActivity, feedSnapshot.takeLast(2).map { it.id }
                        )
                        if (tail.isNotEmpty()) {
                            runCatching { feedRepository.recommendations(tail) }
                                .onFailure { Log.w(TAG, "feed expansion failed: ${it.message}") }
                                .getOrDefault(emptyList())
                        } else emptyList()
                    } else emptyList()
                val merged = (feedSnapshot + expanded + result.videos).distinctBy { it.id }
                feedSnapshot = merged
                val visible = Recommendations.filter(this@MainActivity, merged)
                videoAdapter.submitList(visible)
                // stop paging when YouTube keeps returning the same continuation
                nextPageToken = result.nextPageToken?.takeIf { it != prevToken }
                Log.i(
                    TAG,
                    "loadMore: +${result.videos.size} videos (+${expanded.size} related), " +
                        "total=${videoAdapter.itemCount}, pageToken=${nextPageToken != null}"
                )
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Ошибка загрузки: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
                isLoading = false
            }
        }
    }

    private fun openPlayer(video: Video) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_ID, video.id)
            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, video.title)
            putExtra(PlayerActivity.EXTRA_CHANNEL_TITLE, video.channelTitle)
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, video.channelId)
        }
        startActivity(intent)
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_incognito)?.isChecked = Recommendations.isIncognito(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sign_in -> {
                signIn()
                true
            }
            R.id.action_sign_out -> {
                signOut()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_subscriptions -> {
                loadSubscriptions()
                true
            }
            R.id.action_playlists -> {
                loadPlaylists()
                true
            }
            R.id.action_incognito -> {
                toggleIncognito()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Инкогнито (шаг 4 статьи Т—Ж): активность не сохраняется, поэтому
     * история просмотра не пишется, а лента не персонализируется.
     */
    private fun toggleIncognito() {
        val enabled = !Recommendations.isIncognito(this)
        Recommendations.setIncognito(this, enabled)
        invalidateOptionsMenu()
        updateSubtitle()
        Toast.makeText(
            this,
            getString(if (enabled) R.string.incognito_on else R.string.incognito_off),
            Toast.LENGTH_LONG
        ).show()
        loadTrendingVideos()
    }

    /** Account name, prefixed with "Инкогнито" in incognito mode. */
    private fun updateSubtitle() {
        val name = GoogleSignIn.getLastSignedInAccount(this)?.displayName
        supportActionBar?.subtitle = when {
            Recommendations.isIncognito(this) ->
                listOf(getString(R.string.incognito_title), name)
                    .filterNotNull()
                    .joinToString(" · ")
            else -> name
        }
    }

    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    private fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            accessToken = null
            AuthRepository.clear()
            binding.signInContainer.visibility = View.VISIBLE
            updateSubtitle()
            loadTrendingVideos()
        }
    }

    private fun loadSubscriptions() {
        if (accessToken == null) {
            Toast.makeText(this, "Войдите в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }
        // TODO: Implement subscriptions screen
    }

    private fun loadPlaylists() {
        if (accessToken == null) {
            Toast.makeText(this, "Войдите в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }
        // TODO: Implement playlists screen
    }
}
