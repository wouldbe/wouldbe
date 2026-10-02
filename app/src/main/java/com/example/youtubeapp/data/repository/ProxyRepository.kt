package com.example.youtubeapp.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.youtubeapp.data.model.ProxyConfig
import com.example.youtubeapp.data.model.ProxyType
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class ProxyRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var currentConfig: ProxyConfig
        get() {
            val type = ProxyType.valueOf(prefs.getString(KEY_PROXY_TYPE, ProxyType.NONE.name) ?: ProxyType.NONE.name)
            val host = prefs.getString(KEY_PROXY_HOST, "") ?: ""
            val port = prefs.getInt(KEY_PROXY_PORT, 0)
            val username = prefs.getString(KEY_PROXY_USERNAME, "") ?: ""
            val password = prefs.getString(KEY_PROXY_PASSWORD, "") ?: ""
            val enabled = prefs.getBoolean(KEY_PROXY_ENABLED, false)
            return ProxyConfig(type, host, port, username, password, enabled)
        }
        set(value) {
            prefs.edit()
                .putString(KEY_PROXY_TYPE, value.type.name)
                .putString(KEY_PROXY_HOST, value.host)
                .putInt(KEY_PROXY_PORT, value.port)
                .putString(KEY_PROXY_USERNAME, value.username)
                .putString(KEY_PROXY_PASSWORD, value.password)
                .putBoolean(KEY_PROXY_ENABLED, value.isEnabled)
                .apply()
        }

    fun buildOkHttpClient(baseClient: OkHttpClient = OkHttpClient.Builder().build()): OkHttpClient {
        val config = currentConfig
        if (!config.isValid() || !config.isEnabled) {
            return baseClient
        }

        val builder = baseClient.newBuilder()

        val proxyType = when (config.type) {
            ProxyType.HTTP, ProxyType.HTTPS -> Proxy.Type.HTTP
            ProxyType.SOCKS5 -> Proxy.Type.SOCKS
            ProxyType.NONE -> return baseClient
        }

        val proxy = Proxy(proxyType, InetSocketAddress(config.host, config.port))
        builder.proxy(proxy)

        if (config.username.isNotBlank()) {
            builder.proxyAuthenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    val credential = Credentials.basic(config.username, config.password)
                    return response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            })
        }

        return builder
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun testConnection(callback: (Boolean, String?) -> Unit) {
        val config = currentConfig
        if (!config.isValid() || !config.isEnabled) {
            callback(true, null)
            return
        }

        Thread {
            try {
                val client = buildOkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url("https://www.youtube.com")
                    .head()
                    .build()
                val response = client.newCall(request).execute()
                callback(response.isSuccessful, null)
            } catch (e: Exception) {
                callback(false, e.message)
            }
        }.start()
    }

    companion object {
        private const val PREFS_NAME = "proxy_settings"
        private const val KEY_PROXY_TYPE = "proxy_type"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_PROXY_USERNAME = "proxy_username"
        private const val KEY_PROXY_PASSWORD = "proxy_password"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
    }
}
