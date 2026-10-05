package com.example.youtubeapp.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Adapter that collects free proxy lists from open sources, measures the ping
 * of every address and returns the [topN] fastest ones for the proxy settings
 * screen (the first entry is applied to the host/port fields).
 *
 * Sources are plain text lists ("ip:port" per line) or JSON (geonode).
 * The list requests reuse [ProxyRepository.buildOkHttpClient], so they work
 * through the currently configured proxy; the ping itself is a direct TCP
 * connect measured in milliseconds.
 */
class ProxyAdapter(context: Context) {

    data class Candidate(val host: String, val port: Int, val pingMs: Int)

    private val appContext: Context = context.applicationContext
    private val proxyRepository = ProxyRepository(appContext)

    /**
     * Fetches open proxy lists, pings the addresses and returns the
     * [topN] candidates sorted by ping ascending. Empty list = nothing
     * reachable. Throws if no source could be downloaded at all.
     */
    suspend fun findBest(topN: Int = DEFAULT_TOP_N): List<Candidate> =
        withContext(Dispatchers.IO) {
            val addresses = fetchAddresses()
            Log.i(TAG, "collected ${addresses.size} proxy addresses from open sources")
            if (addresses.isEmpty()) return@withContext emptyList()

            pingAll(addresses)
                .sortedBy { it.pingMs }
                .also { Log.i(TAG, "reachable ${it.size}, best=${it.firstOrNull()?.let { c -> "${c.host}:${c.port} ${c.pingMs}ms" }}") }
                .take(topN)
        }

    // ---------------------------------------------------------------- sources

    private val sources = listOf(
        Source("https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=3000&country=all&ssl=all&anonymity=all", SourceFormat.TEXT),
        Source("https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt", SourceFormat.TEXT),
        Source("https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt", SourceFormat.TEXT),
        Source("https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&protocol=http&timeout=3000&country=all", SourceFormat.TEXT),
        Source("https://proxylist.geonode.com/api/proxy-list?limit=300&page=1&sort_by=lastChecked&sort_type=desc&protocols=http", SourceFormat.GEONODE_JSON)
    )

    private data class Source(val url: String, val format: SourceFormat)
    private enum class SourceFormat { TEXT, GEONODE_JSON }

    /** IPv4:port - hostname entries are skipped (no DNS dependency). */
    private val addressPattern = Regex("^((?:\\d{1,3}\\.){3}\\d{1,3}):(\\d{2,5})$")

    private suspend fun fetchAddresses(): List<String> = coroutineScope {
        val client = buildClient()
        val results = sources.map { source ->
            async {
                try {
                    val request = Request.Builder()
                        .url(source.url)
                        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                        .header("Accept", "*/*")
                        .build()
                    val body = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                        response.body?.string() ?: ""
                    }
                    val parsed = parse(body, source.format)
                    Log.i(TAG, "source ok: ${source.url.take(60)}... -> ${parsed.size}")
                    parsed
                } catch (e: Exception) {
                    Log.w(TAG, "source failed: ${source.url.take(60)}...: $e")
                    emptyList()
                }
            }
        }.awaitAll()

        results.flatten()
            .distinct()
            .shuffled()
            .take(MAX_ADDRESSES)
    }

    private fun parse(body: String, format: SourceFormat): List<String> {
        val out = mutableListOf<String>()
        when (format) {
            SourceFormat.TEXT -> body.lineSequence().forEach { line ->
                val m = addressPattern.find(line.trim()) ?: return@forEach
                val host = m.groupValues[1]
                val port = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (valid(host, port)) out.add("$host:$port")
            }
            SourceFormat.GEONODE_JSON -> try {
                val data = JSONObject(body).optJSONArray("data") ?: return out
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val host = item.optString("ip")
                    val port = item.optInt("port", 0)
                    if (valid(host, port)) out.add("$host:$port")
                }
            } catch (e: Exception) {
                Log.w(TAG, "json parse failed: $e")
            }
        }
        return out
    }

    private fun valid(host: String, port: Int): Boolean =
        host.isNotEmpty() && port in 1..65535 &&
            host.split(".").size == 4 &&
            host.split(".").all { it.length <= 3 && it.toIntOrNull() in 0..255 }

    // ------------------------------------------------------------------- ping

    private suspend fun pingAll(addresses: List<String>): List<Candidate> = coroutineScope {
        val semaphore = Semaphore(PING_CONCURRENCY)
        addresses.map { address ->
            async {
                semaphore.withPermit {
                    val sep = address.lastIndexOf(':')
                    val host = address.substring(0, sep)
                    val port = address.substring(sep + 1).toIntOrNull() ?: return@withPermit null
                    val ping = pingTcp(host, port) ?: return@withPermit null
                    Candidate(host, port, ping)
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** TCP connect time in ms, or null when unreachable. */
    private fun pingTcp(host: String, port: Int): Int? = try {
        Socket().use { socket ->
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), PING_TIMEOUT_MS)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            elapsed.toInt().coerceAtLeast(1)
        }
    } catch (e: Exception) {
        null
    }

    private fun buildClient(): OkHttpClient = proxyRepository.buildOkHttpClient(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    )

    companion object {
        private const val TAG = "ProxyAdapter"

        /** Result size requested by the settings screen. */
        const val DEFAULT_TOP_N = 5

        /** Upper bound of addresses to ping (keeps the scan quick). */
        private const val MAX_ADDRESSES = 120

        /** Parallel TCP connects while pinging. */
        private const val PING_CONCURRENCY = 16

        private const val PING_TIMEOUT_MS = 2_500
    }
}
