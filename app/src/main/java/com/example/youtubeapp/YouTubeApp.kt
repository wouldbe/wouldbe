package com.example.youtubeapp

import android.app.Application
import com.example.youtubeapp.data.repository.ProxyRepository
import com.example.youtubeapp.data.repository.SettingsRepository

class YouTubeApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var proxyRepository: ProxyRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        proxyRepository = ProxyRepository(this)
        proxyRepository.applyToSystem()
    }

    companion object {
        lateinit var instance: YouTubeApp
            private set
    }
}
