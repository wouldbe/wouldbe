package com.example.youtubeapp.data.repository

import android.content.Context
import android.util.Log
import com.example.youtubeapp.data.model.SearchResult
import com.example.youtubeapp.data.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Feed and search over the public innertube API (no API key required).
 *
 * Why this exists:
 *  - The YouTube Data API key is restricted server side (API_KEY_SERVICE_BLOCKED),
 *    so [com.example.youtubeapp.data.repository.YouTubeRepository] returns nothing.
 *  - The guest home feed (browse FEwhat_to_watch) is empty: YouTube answers with
 *    a feedNudgeRenderer ("Try searching to get started") and FEtrending/FEexplore
 *    are rejected with HTTP 400 (trending was removed).
 *
 * What works without a key (validated against the working proxy):
 *  - POST /youtubei/v1/search with {query} -> videoRenderer list;
 *  - pagination: POST /youtubei/v1/search with {continuation} (the token of
 *    continuationItemRenderer.continuationCommand, request type SEARCH) - the
 *    /next endpoint does NOT work for search continuations (HTTP 400).
 *
 * The home feed is emulated by a search feed over a small rotating set of broad
 * queries, which gives a stable list with working pagination.
 */
class FeedRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val proxyRepository = ProxyRepository(appContext)

    data class Session(val cookies: String, val visitorData: String?)

    @Volatile
    private var cachedSession: Session? = null

    private fun buildClient(): OkHttpClient = proxyRepository.buildOkHttpClient(
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    /** Consented homepage visit -> visitorData + session cookies (cached). */
    private suspend fun session(client: OkHttpClient): Session {
        cachedSession?.let { return it }

        val request = Request.Builder()
            .url("https://www.youtube.com/")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "SOCS=CAI; PREF=hl=en&tz=UTC")
            .build()

        val cookies = linkedSetOf("SOCS=CAI", "PREF=hl=en&tz=UTC")
        val homepage = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw FeedException("homepage HTTP ${response.code}")
            for (header in response.headers("Set-Cookie")) {
                val pair = header.substringBefore(';')
                if (pair.contains('=')) cookies.add(pair)
            }
            response.body?.string() ?: ""
        }

        val visitorData = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").find(homepage)?.groupValues?.get(1)
            ?: Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"").find(homepage)?.groupValues?.get(1)

        return Session(cookies.joinToString("; "), visitorData).also { cachedSession = it }
    }

    /** First page of search results. */
    suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        post(JSONObject().put("query", query))
    }

    /** Next page of a previous search. */
    suspend fun continueSearch(token: String): SearchResult = withContext(Dispatchers.IO) {
        post(JSONObject().put("continuation", token))
    }

    /**
     * YouTube recommendations for one video: the watch-next list of
     * POST /youtubei/v1/next (lockupViewModel entries).
     */
    suspend fun related(videoId: String): List<Video> = withContext(Dispatchers.IO) {
        parse(postBody(JSONObject().put("videoId", videoId), "next")).videos
    }

    /**
     * Recommendations seeded by the user's watch history: related lists of the
     * most recent videos, deduplicated and with the seeds themselves removed.
     */
    suspend fun recommendations(seeds: List<String>): List<Video> = withContext(Dispatchers.IO) {
        val seedSet = seeds.toHashSet()
        val result = LinkedHashMap<String, Video>()
        for (seed in seeds.take(MAX_SEEDS)) {
            try {
                val items = related(seed)
                Log.i(TAG, "recommendations: seed=$seed -> ${items.size} items")
                for (v in items) {
                    if (v.id !in seedSet) result.putIfAbsent(v.id, v)
                }
            } catch (e: Exception) {
                Log.w(TAG, "recommendations: seed=$seed failed: ${e.message}")
            }
        }
        result.values.toList()
    }

    /**
     * Home feed substitute: search feed over a broad query that changes by day,
     * so the tab shows varied, paginated content instead of an empty nudge.
     */
    suspend fun home(): SearchResult = withContext(Dispatchers.IO) {
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        post(JSONObject().put("query", HOME_QUERIES[day % HOME_QUERIES.size]))
    }

    private suspend fun post(body: JSONObject, endpoint: String = "search"): SearchResult =
        parse(postBody(body, endpoint))

    private suspend fun postBody(body: JSONObject, endpoint: String): String {
        val client = buildClient()
        val sess = runCatching { session(client) }.getOrElse { e ->
            Log.e(TAG, "session failed: ${e.message}", e)
            Session("SOCS=CAI", null)
        }

        body.put(
            "context",
            JSONObject().apply {
                put(
                    "client",
                    JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", WEB_CLIENT_VERSION)
                        put("hl", "en")
                        put("gl", "US")
                        put("timeZone", "UTC")
                        put("utcOffsetMinutes", 0)
                    }
                )
            }
        )

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", WEB_CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", sess.cookies)
            .apply { if (sess.visitorData != null) header("X-Goog-Visitor-Id", sess.visitorData) }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val raw = client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                cachedSession = null // stale visitor data - drop it for the next call
            }
            if (!response.isSuccessful) throw FeedException("$endpoint HTTP ${response.code}")
            response.body?.string() ?: ""
        }

        return raw
    }

    private fun parse(raw: String): SearchResult {
        val root = JSONObject(raw)
        val videos = mutableListOf<Video>()
        collectVideos(root, videos)

        val seen = HashSet<String>()
        val distinct = videos.filter { seen.add(it.id) }

        val token = findContinuation(root)
        if (distinct.isEmpty() && token == null) {
            Log.w(TAG, "parse: no videos and no continuation in ${raw.length} bytes")
        }
        return SearchResult(distinct, token)
    }

    /** Recursive walk: every videoRenderer-like object in the response. */
    private fun collectVideos(node: Any?, out: MutableList<Video>) {
        when (node) {
            is JSONObject -> {
                for (key in VIDEO_RENDERERS) {
                    node.optJSONObject(key)?.let { obj -> parseVideo(obj)?.let { out.add(it) } }
                }
                // watch-next recommendations are rendered as lockupViewModel
                node.optJSONObject("lockupViewModel")?.let { obj -> parseLockup(obj)?.let { out.add(it) } }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectVideos(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectVideos(node.opt(i), out)
            }
        }
    }

    /**
     * lockupViewModel (used by /next): nested view models instead of
     * videoRenderer fields - title/content/channel/counts live under
     * metadata.lockupMetadataViewModel, the id is inside the onTap commands.
     */
    private fun parseLockup(obj: JSONObject): Video? {
        val id = findVideoId(obj) ?: return null

        val meta = obj.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = meta.optJSONObject("title")?.optString("content") ?: ""

        val rows = meta.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")
        val firstPart = rows?.optJSONObject(0)?.optJSONArray("metadataParts")?.optJSONObject(0)
        val secondRow = rows?.optJSONObject(1)?.optJSONArray("metadataParts")

        val channelTitle = firstPart?.optJSONObject("text")?.optString("content") ?: ""
        // channel id lives in the avatar command of the title image
        val channelId = meta.optJSONObject("image")
            ?.optJSONObject("decoratedAvatarViewModel")
            ?.optJSONObject("avatar")
            ?.optJSONObject("avatarViewModel")
            ?.optJSONObject("commandContext")?.optJSONObject("onTap")
            ?.optJSONObject("innertubeCommand")
            ?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""

        val viewsText = secondRow?.optJSONObject(0)?.optJSONArray("metadataParts")
            ?.optJSONObject(0)
            ?.let { p -> p.optJSONObject("text")?.optString("content") ?: "" } ?: ""
        val publishedText = secondRow?.optJSONObject(0)?.optJSONArray("metadataParts")
            ?.optJSONObject(1)
            ?.let { p -> p.optJSONObject("text")?.optString("content") ?: "" } ?: ""

        val thumbs = obj.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")?.optJSONArray("sources")
        val thumbnailUrl = (0 until (thumbs?.length() ?: 0))
            .mapNotNull { thumbs?.optJSONObject(it) }
            .maxByOrNull { it.optInt("width", 0) }
            ?.optString("url") ?: ""

        val duration = obj.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONArray("overlays")
            ?.optJSONObject(0)
            ?.optJSONObject("thumbnailBottomOverlayViewModel")
            ?.optJSONArray("badges")
            ?.optJSONObject(0)
            ?.optJSONObject("thumbnailBadgeViewModel")
            ?.optString("text") ?: ""

        if (title.isBlank()) return null
        return Video(
            id = id,
            title = title,
            description = "",
            thumbnailUrl = thumbnailUrl,
            channelTitle = channelTitle,
            channelId = channelId,
            publishedAt = publishedText,
            duration = duration,
            viewCount = parseCompactCount(viewsText)
        )
    }

    /** First 11-char videoId anywhere inside an object (commands, endpoints...). */
    private fun findVideoId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val direct = node.optString("videoId")
                if (direct.length == 11) return direct
                val keys = node.keys()
                while (keys.hasNext()) {
                    findVideoId(node.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) findVideoId(node.opt(i))?.let { return it }
            }
        }
        return null
    }

    /** "13M" / "1,2K" / "27,033,780 views" -> number. */
    private fun parseCompactCount(text: String): Long {
        if (text.isBlank()) return 0
        val cleaned = text.filter { it.isDigit() || it == '.' || it == ',' }
            .replace(",", "")
        val suffix = text.lastOrNull { it.isLetter() }?.uppercaseChar()
        val base = cleaned.substringBefore('.').ifBlank { return 0 }.toLongOrNull() ?: return 0
        val mantissa = cleaned.substringAfter('.', "").take(1).toIntOrNull() ?: 0
        val value = base * 10 + mantissa
        return when (suffix) {
            'K' -> value * 1_000L
            'M' -> value * 1_000_000L
            'B' -> value * 1_000_000_000L
            else -> {
                // plain number, e.g. "27033780 views"
                text.filter { it.isDigit() }.toLongOrNull() ?: 0L
            }
        }
    }

    private fun parseVideo(obj: JSONObject): Video? {
        val id = obj.optString("videoId")
        if (id.isBlank()) return null

        val title = textOf(obj.optJSONObject("title"))
        val byline = obj.optJSONObject("ownerText") ?: obj.optJSONObject("longBylineText")
        val channelTitle = textOf(byline)
        val channelId = byline?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId") ?: ""

        val thumbs = obj.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbnailUrl = (0 until (thumbs?.length() ?: 0))
            .mapNotNull { thumbs?.optJSONObject(it) }
            .maxByOrNull { it.optInt("width", 0) }
            ?.optString("url") ?: ""

        val views = textOf(obj.optJSONObject("viewCountText"))
        val viewCount = views.filter { it.isDigit() }.toLongOrNull() ?: 0L

        return Video(
            id = id,
            title = title,
            description = "",
            thumbnailUrl = thumbnailUrl,
            channelTitle = channelTitle,
            channelId = channelId,
            publishedAt = textOf(obj.optJSONObject("publishedTimeText")),
            duration = textOf(obj.optJSONObject("lengthText")),
            viewCount = viewCount
        )
    }

    /** "runs"/"simpleText" text container -> plain string. */
    private fun textOf(obj: JSONObject?): String {
        if (obj == null) return ""
        obj.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj.optJSONArray("runs") ?: return ""
        return (0 until runs.length())
            .mapNotNull { runs.optJSONObject(it)?.optString("text") }
            .joinToString("")
    }

    /** Next search continuation token (prefers the SEARCH request type). */
    private fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val cmd = node.optJSONObject("continuationCommand")
                if (cmd != null) {
                    val token = cmd.optString("token")
                    if (token.isNotBlank()) {
                        val request = cmd.optString("request")
                        if (request.isBlank() || request == "CONTINUATION_REQUEST_TYPE_SEARCH") {
                            return token
                        }
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findContinuation(node.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findContinuation(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    class FeedException(message: String) : Exception(message)

    companion object {
        private const val TAG = "FeedRepository"
        private const val WEB_CLIENT_VERSION = "2.20260708.00.00"

        /** How many watched videos seed the recommendation chain. */
        private const val MAX_SEEDS = 3

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val VIDEO_RENDERERS = listOf(
            "videoRenderer",
            "compactVideoRenderer",
            "gridVideoRenderer",
            "playlistVideoRenderer"
        )

        private val HOME_QUERIES = arrayOf(
            "new music videos",
            "funny videos",
            "gaming highlights",
            "sports highlights",
            "movie trailers",
            "technology news",
            "cooking recipes"
        )
    }
}
