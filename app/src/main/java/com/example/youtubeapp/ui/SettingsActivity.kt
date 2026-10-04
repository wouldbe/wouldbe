package com.example.youtubeapp.ui

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.youtubeapp.R
import com.example.youtubeapp.YouTubeApp
import com.example.youtubeapp.data.model.ProxyConfig
import com.example.youtubeapp.data.model.ProxyType
import com.example.youtubeapp.data.model.VideoQuality
import com.example.youtubeapp.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private val TAG = "SettingsActivity"

    private lateinit var binding: ActivitySettingsBinding

    private val settingsRepository by lazy {
        (application as YouTubeApp).settingsRepository
    }

    private val proxyRepository by lazy {
        (application as YouTubeApp).proxyRepository
    }

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
        setupThemeSettings()
    }

    private fun setupVideoQualitySettings() {
        val qualities = VideoQuality.values().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.defaultQualitySpinner.adapter = adapter
        binding.defaultQualitySpinner.setSelection(qualities.indexOf(settingsRepository.videoQuality.name))

        binding.defaultQualitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settingsRepository.videoQuality = VideoQuality.valueOf(qualities[position])
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

        val qualities = VideoQuality.values().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.maxDownloadQualitySpinner.adapter = adapter
        binding.maxDownloadQualitySpinner.setSelection(qualities.indexOf(settingsRepository.maxDownloadQuality.name))
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
