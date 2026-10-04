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

        binding.signInButton.setOnClickListener {
            signIn()
        }
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter { video ->
            openPlayer(video)
        }

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
        val account = GoogleSignIn.getLastSignedInAccount(this)
        supportActionBar?.subtitle = account?.displayName
    }

    /**
     * Personalized part of the home feed:
     *  1. uploads of subscribed channels (OAuth only, no API key);
     *  2. YouTube watch-next recommendations seeded by local watch history.
     * Merged and deduplicated; empty when signed out and nothing watched.
     */
    private suspend fun personalizedParts(): List<Video> {
        Log.i(TAG, "personalized: start (token=${accessToken != null})")

        val subscriptions = accessToken?.let { token ->
            runCatching { youTubeRepository.getSubscriptionUploads(token) }
                .onFailure { Log.w(TAG, "subscription feed failed: ${it.message}") }
                .getOrDefault(emptyList())
        } ?: emptyList()

        val seeds = WatchHistory.recentIds(this, 5)
        val recommendations = if (seeds.isNotEmpty()) {
            feedRepository.recommendations(seeds)
        } else emptyList()

        val parts = (subscriptions + recommendations).distinctBy { it.id }
        Log.i(
            TAG,
            "personalized: subs=${subscriptions.size} recs=${recommendations.size} " +
                "seeds=${seeds.size} total=${parts.size}"
        )
        return parts
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
                Log.i(TAG, "loadTrendingVideos: got ${result.videos.size} videos (Data API)")
                if (result.videos.isEmpty()) {
                    result = loadPersonalizedFeed()
                    usingFeedSource = true
                } else {
                    usingFeedSource = false
                    // personalization on top of the official (non-personal) feed
                    val personal = personalizedParts()
                    if (personal.isNotEmpty()) {
                        result = SearchResult(
                            (personal + result.videos).distinctBy { it.id }.take(FEED_MAX_ITEMS),
                            result.nextPageToken
                        )
                    }
                }
                videoAdapter.submitList(result.videos)
                nextPageToken = result.nextPageToken
                Log.i(TAG, "loadTrendingVideos: displayed ${result.videos.size} videos, pageToken=${nextPageToken != null}")
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
                videoAdapter.submitList(result.videos)
                nextPageToken = result.nextPageToken
                Log.i(TAG, "searchVideos: displayed ${result.videos.size} videos for '$query', pageToken=${nextPageToken != null}")
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
                videoAdapter.submitList(videoAdapter.currentList + result.videos)
                // stop paging when YouTube keeps returning the same continuation
                nextPageToken = result.nextPageToken?.takeIf { it != prevToken }
                Log.i(
                    TAG,
                    "loadMore: +${result.videos.size} videos, total=${videoAdapter.itemCount}, pageToken=${nextPageToken != null}"
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
            else -> super.onOptionsItemSelected(item)
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
            supportActionBar?.subtitle = null
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
