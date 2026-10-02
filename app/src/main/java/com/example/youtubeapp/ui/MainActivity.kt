package com.example.youtubeapp.ui

import android.content.Intent
import android.os.Bundle
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
import com.example.youtubeapp.data.model.Video
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var googleSignInClient: GoogleSignInClient
    private var accessToken: String? = null
    private var currentQuery: String = ""
    private var nextPageToken: String? = null
    private var isLoading = false

    private val youTubeRepository by lazy {
        YouTubeRepository(this)
    }

    private val settingsRepository by lazy {
        (application as YouTubeApp).settingsRepository
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(Exception::class.java)
            accessToken = account.idToken
            loadUserData()
        } catch (e: Exception) {
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
            accessToken = account.idToken
            loadUserData()
        }
        loadTrendingVideos()
    }

    private fun loadUserData() {
        updateUIForSignedInUser()
    }

    private fun updateUIForSignedInUser() {
        binding.signInContainer.visibility = View.GONE
        val account = GoogleSignIn.getLastSignedInAccount(this)
        supportActionBar?.subtitle = account?.displayName
    }

    private fun loadTrendingVideos() {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val result = youTubeRepository.getTrendingVideos(accessToken)
                videoAdapter.submitList(result.videos)
                nextPageToken = result.nextPageToken
            } catch (e: Exception) {
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
                val result = youTubeRepository.searchVideos(query, accessToken)
                videoAdapter.submitList(result.videos)
                nextPageToken = result.nextPageToken
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
                val result = if (currentQuery.isNotBlank()) {
                    youTubeRepository.searchVideos(currentQuery, accessToken, nextPageToken)
                } else {
                    youTubeRepository.getTrendingVideos(accessToken, pageToken = nextPageToken)
                }
                videoAdapter.submitList(videoAdapter.currentList + result.videos)
                nextPageToken = result.nextPageToken
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
