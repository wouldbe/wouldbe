package com.example.youtubeapp.ui

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.youtubeapp.R
import com.example.youtubeapp.YouTubeApp
import com.example.youtubeapp.data.model.ProxyConfig
import com.example.youtubeapp.data.model.ProxyType
import com.example.youtubeapp.data.model.VideoQuality
import com.example.youtubeapp.data.repository.ProxyAdapter
import com.example.youtubeapp.data.repository.Recommendations
import com.example.youtubeapp.data.repository.WatchHistory
import com.example.youtubeapp.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private val TAG = "SettingsActivity"

    private lateinit var binding: ActivitySettingsBinding

    private val settingsRepository by lazy {
        (application as YouTubeApp).settingsRepository
    }

    private val proxyRepository by lazy {
        (application as YouTubeApp).proxyRepository
    }

    private val proxyAdapter by lazy {
        ProxyAdapter(application)
    }

    /** Candidates returned by the last "Получить прокси" run. */
    private var proxyCandidates: List<ProxyAdapter.Candidate> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        setupVideoQualitySettings()
        setupPlaybackSettings()
        setupSubtitleSettings()
        setupDownloadSettings()
        setupProxySettings()
        setupRecommendations()
        setupThemeSettings()
    }

    private fun setupVideoQualitySettings() {
        val qualities = VideoQuality.values()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.defaultQualitySpinner.adapter = adapter
        binding.defaultQualitySpinner.setSelection(settingsRepository.videoQuality.ordinal)

        binding.defaultQualitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settingsRepository.videoQuality = qualities[position]
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupPlaybackSettings() {
        binding.autoPlaySwitch.isChecked = settingsRepository.autoPlay
        binding.autoPlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.autoPlay = isChecked
        }

        binding.bufferSizeSlider.value = settingsRepository.bufferSize.toFloat()
        binding.bufferSizeSlider.addOnChangeListener { _, value, _ ->
            settingsRepository.bufferSize = value.toInt()
        }
    }

    private fun setupSubtitleSettings() {
        binding.subtitlesEnabledSwitch.isChecked = settingsRepository.subtitlesEnabled
        binding.subtitlesEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.subtitlesEnabled = isChecked
        }

        val languages = listOf("ru", "en", "es", "fr", "de", "ja", "ko", "zh")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.subtitleLanguageSpinner.adapter = adapter
        binding.subtitleLanguageSpinner.setSelection(languages.indexOf(settingsRepository.subtitleLanguage))
    }

    private fun setupDownloadSettings() {
        binding.downloadWifiOnlySwitch.isChecked = settingsRepository.downloadWifiOnly
        binding.downloadWifiOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.downloadWifiOnly = isChecked
        }

        val qualities = VideoQuality.values()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.maxDownloadQualitySpinner.adapter = adapter
        binding.maxDownloadQualitySpinner.setSelection(settingsRepository.maxDownloadQuality.ordinal)
        binding.maxDownloadQualitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settingsRepository.maxDownloadQuality = qualities[position]
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupProxySettings() {
        val proxyTypes = ProxyType.values().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, proxyTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.proxyTypeSpinner.adapter = adapter

        val currentConfig = proxyRepository.currentConfig
        binding.proxyTypeSpinner.setSelection(proxyTypes.indexOf(currentConfig.type.name))
        binding.proxyHostInput.setText(currentConfig.host)
        binding.proxyPortInput.setText(currentConfig.port.toString())
        binding.proxyUsernameInput.setText(currentConfig.username)
        binding.proxyPasswordInput.setText(currentConfig.password)
        binding.proxyEnabledSwitch.isChecked = currentConfig.isEnabled

        binding.proxyTypeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val type = ProxyType.valueOf(proxyTypes[position])
                binding.proxyUsernameInput.isEnabled = type != ProxyType.NONE
                binding.proxyPasswordInput.isEnabled = type != ProxyType.NONE
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.saveProxyButton.setOnClickListener {
            saveProxySettings()
        }

        binding.testProxyButton.setOnClickListener {
            testProxyConnection()
        }

        binding.fetchProxyButton.setOnClickListener {
            fetchProxyCandidates()
        }
    }

    /**
     * Runs the proxy adapter: downloads open proxy lists, pings them and
     * shows the 5 fastest addresses as a list; the first one is applied to
     * the host/port fields.
     */
    private fun fetchProxyCandidates() {
        binding.fetchProxyButton.isEnabled = false
        binding.fetchProxyButton.text = getString(R.string.proxy_fetching)

        lifecycleScope.launch {
            val result = runCatching { proxyAdapter.findBest() }
            binding.fetchProxyButton.isEnabled = true
            binding.fetchProxyButton.text = getString(R.string.proxy_fetch)

            result.fold(
                onSuccess = { candidates -> showProxyCandidates(candidates) },
                onFailure = { e ->
                    Log.e(TAG, "proxy fetch failed: $e")
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.proxy_fetch_error, e.message ?: e.toString()),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun showProxyCandidates(candidates: List<ProxyAdapter.Candidate>) {
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.proxy_fetch_empty, Toast.LENGTH_LONG).show()
            return
        }
        proxyCandidates = candidates

        val labels = candidates.map { "${it.host}:${it.port} — ${it.pingMs} мс" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.proxyCandidatesRow.visibility = View.VISIBLE
        binding.proxyCandidatesSpinner.adapter = adapter
        binding.proxyCandidatesSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                applyProxyCandidate(candidates[position])
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        binding.proxyCandidatesSpinner.setSelection(0)

        Toast.makeText(
            this,
            getString(R.string.proxy_fetch_done, candidates.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Fills the proxy host/port fields with the selected candidate. */
    private fun applyProxyCandidate(candidate: ProxyAdapter.Candidate) {
        binding.proxyHostInput.setText(candidate.host)
        binding.proxyPortInput.setText(candidate.port.toString())

        // a found proxy is unusable with type NONE - default to HTTP
        if (binding.proxyTypeSpinner.selectedItem?.toString() == ProxyType.NONE.name) {
            val types = ProxyType.values().map { it.name }
            binding.proxyTypeSpinner.setSelection(types.indexOf(ProxyType.HTTP.name))
        }
        Log.i(TAG, "applyProxyCandidate: ${candidate.host}:${candidate.port} ping=${candidate.pingMs}ms")
    }

    /**
     * History + hidden videos/channels (Т—Ж, шаги 3, 6, 7: «не интересует»,
     * чистка истории и «начать с чистого листа»).
     */
    private fun setupRecommendations() {
        refreshRecommendationStats()

        binding.clearHistoryButton.setOnClickListener {
            WatchHistory.clear(this)
            refreshRecommendationStats()
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
        }

        binding.resetHiddenButton.setOnClickListener {
            Recommendations.resetHidden(this)
            refreshRecommendationStats()
            Toast.makeText(this, R.string.hidden_reset, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshRecommendationStats() {
        val (videos, channels) = Recommendations.hiddenCounts(this)
        binding.recStatsText.text = getString(
            R.string.rec_stats,
            WatchHistory.count(this),
            videos,
            channels
        )
    }

    private fun setupThemeSettings() {
        binding.darkThemeSwitch.isChecked = settingsRepository.darkTheme
        binding.darkThemeSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.darkTheme = isChecked
            // TODO: Apply theme
        }
    }

    private fun saveProxySettings() {
        val typeText = binding.proxyTypeSpinner.selectedItem?.toString() ?: "NONE"
        val host = binding.proxyHostInput.text.toString()
        val port = binding.proxyPortInput.text.toString().toIntOrNull() ?: 0
        val username = binding.proxyUsernameInput.text.toString()
        val password = binding.proxyPasswordInput.text.toString()
        val enabled = binding.proxyEnabledSwitch.isChecked
        Log.i(
            TAG,
            "saveProxy: typeText='$typeText' host='$host' port=$port enabled=$enabled"
        )

        val type = try {
            ProxyType.valueOf(typeText)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "saveProxy: unknown type '$typeText', falling back to HTTP")
            ProxyType.HTTP
        }

        val config = ProxyConfig(type, host, port, username, password, enabled)
        proxyRepository.currentConfig = config
        proxyRepository.applyToSystem()

        Toast.makeText(this, "Настройки прокси сохранены", Toast.LENGTH_SHORT).show()
    }

    private fun testProxyConnection() {
        saveProxySettings()
        proxyRepository.testConnection { success, error ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Прокси работает", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Ошибка прокси: $error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
