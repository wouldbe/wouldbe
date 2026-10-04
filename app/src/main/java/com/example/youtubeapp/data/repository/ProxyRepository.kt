package com.example.youtubeapp.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class ProxyRepository(context: Context) {

    private val context: Context = context.applicationContext

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

    /**
     * Applies proxy to java.net-level clients (HttpURLConnection,
     * used by google-api-client / GoogleNetHttpTransport).
     */
    fun applyToSystem() {
        val config = currentConfig
        val active = config.isEnabled && config.isValid() && config.type != ProxyType.NONE
        Log.i(
            TAG,
            "applyToSystem: active=$active type=${config.type} " +
                "host=${config.host}:${config.port} enabled=${config.isEnabled}"
        )
        if (active) {
            System.setProperty("http.proxyHost", config.host)
            System.setProperty("http.proxyPort", config.port.toString())
            System.setProperty("https.proxyHost", config.host)
            System.setProperty("https.proxyPort", config.port.toString())
            if (config.type == ProxyType.SOCKS5) {
                System.setProperty("socksProxyHost", config.host)
                System.setProperty("socksProxyPort", config.port.toString())
            }
        } else {
            listOf(
                "http.proxyHost", "http.proxyPort",
                "https.proxyHost", "https.proxyPort",
                "socksProxyHost", "socksProxyPort"
            ).forEach { System.clearProperty(it) }
        }
        applyGlobalProxy(config, active)
        applyToWebView(config, active)
    }

    /**
     * Applies proxy globally via Settings.Global.HTTP_PROXY so that WebView
     * (Chromium listens for proxy changes) routes the video stream through it.
     * Requires WRITE_SECURE_SETTINGS (grantable via adb pm grant).
     */
    private fun applyGlobalProxy(config: ProxyConfig, active: Boolean) {
        try {
            val value = if (active) "${config.host}:${config.port}" else ":0"
            val changed = android.provider.Settings.Global.putString(
                context.contentResolver,
                android.provider.Settings.Global.HTTP_PROXY,
                value
            )
            Log.i(TAG, "global http_proxy set to '$value': $changed")
        } catch (se: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted, global proxy skipped. " +
                "Run: adb shell pm grant com.example.youtubeapp android.permission.WRITE_SECURE_SETTINGS")
        }
    }

    /**
     * Applies proxy to WebView (video stream via IFrame player).
     * android.webkit.ProxyConfig/ProxyController are hidden APIs,
     * so they are accessed via reflection with graceful fallback.
     */
    private fun applyToWebView(config: ProxyConfig, active: Boolean) {
        try {
            val controllerClass = Class.forName("android.webkit.ProxyController")
            val executor = Executor { it.run() }
            val callback = Runnable { }
            if (active) {
                val rule = when (config.type) {
                    ProxyType.SOCKS5 -> "socks5://${config.host}:${config.port}"
                    else -> "http://${config.host}:${config.port}"
                }
                val configClass = Class.forName("android.webkit.ProxyConfig")
                val builderClass = Class.forName("android.webkit.ProxyConfig\$Builder")
                val ctor = builderClass.getDeclaredConstructor()
                ctor.isAccessible = true
                val builder = ctor.newInstance()
                val addRule = builderClass.getMethod("addProxyRule", String::class.java)
                addRule.invoke(builder, rule)
                val proxyConfig = builderClass.getMethod("build").invoke(builder)

                val instance = controllerClass.getMethod("getInstance").invoke(null)
                controllerClass
                    .getMethod("setProxyOverride", configClass, Executor::class.java, Runnable::class.java)
                    .invoke(instance, proxyConfig, executor, callback)
                Log.i(TAG, "WebView proxy override set: $rule")
            } else {
                val instance = controllerClass.getMethod("getInstance").invoke(null)
                controllerClass
                    .getMethod("clearProxyOverride", Executor::class.java, Runnable::class.java)
                    .invoke(instance, executor, callback)
                Log.i(TAG, "WebView proxy override cleared")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WebView proxy override failed: $t")
        }
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
        private const val TAG = "ProxyRepository"
        private const val PREFS_NAME = "proxy_settings"
        private const val KEY_PROXY_TYPE = "proxy_type"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_PROXY_USERNAME = "proxy_username"
        private const val KEY_PROXY_PASSWORD = "proxy_password"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
    }
}
