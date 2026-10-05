package com.example.youtubeapp.data.repository

import android.content.Context
import android.util.Log
import com.example.youtubeapp.R
import com.example.youtubeapp.data.model.*
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class YouTubeRepository(private val context: Context) {

    private companion object {
        const val TAG = "YouTubeRepository"

        // SHA-1 of the debug signing certificate (Google Cloud API key
        // "Android applications" restriction). Release builds must switch
        // this to the release keystore fingerprint.
        const val ANDROID_CERT_SHA1 = "321D3091496B51B77BBD6A14ECB068C4EAAC03AA"

        // search.list costs 100 quota units per call - hard cap for the
        // history-based related search (default quota: 10 000 units/day).
        const val API_RELATED_SEED_LIMIT = 2
    }

    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport = GoogleNetHttpTransport.newTrustedTransport()

    /**
     * Raw HTTP client for the Data API calls whose parameters were dropped
     * from the generated Google client (`relatedToVideoId`, `mySubscriptions`).
     */
    private val rawHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun getYouTubeService(accessToken: String?, withKey: Boolean = true): YouTube {
        val credential = com.google.api.client.googleapis.auth.oauth2.GoogleCredential()
        if (accessToken != null) {
            credential.accessToken = accessToken
        }
        val apiKey = context.getString(R.string.youtube_api_key)
        // Required by "Android application" API key restriction:
        // without these headers Google returns API_KEY_ANDROID_APP_BLOCKED.
        val packageName = context.packageName
        val certSha1 = ANDROID_CERT_SHA1
        val initializer = HttpRequestInitializer { request ->
            credential.initialize(request)
            if (withKey && apiKey.isNotBlank()) {
                request.url.put("key", apiKey)
            }
            request.headers["X-Android-Package"] = packageName
            request.headers["X-Android-Cert"] = certSha1
            request.connectTimeout = 15_000
            request.readTimeout = 20_000
            request.numberOfRetries = 1
        }

        return YouTube.Builder(httpTransport, jsonFactory, initializer)
            .setApplicationName("YouTubeApp")
            .build()
    }

    suspend fun searchVideos(
        query: String,
        accessToken: String?,
        pageToken: String? = null,
        maxResults: Long = 20
    ): SearchResult = withContext(Dispatchers.IO) {
        try {
            val youtube = getYouTubeService(accessToken)
            val searchList = youtube.search().list(listOf("snippet"))
            searchList.q = query
            searchList.maxResults = maxResults
            searchList.type = listOf("video")
            searchList.pageToken = pageToken

            val response = searchList.execute()
            val videos = response.items.map { item ->
                Video(
                    id = item.id.videoId,
                    title = item.snippet.title,
                    description = item.snippet.description,
                    thumbnailUrl = item.snippet.thumbnails?.medium?.url
                        ?: item.snippet.thumbnails?.default?.url ?: "",
                    channelTitle = item.snippet.channelTitle,
                    channelId = item.snippet.channelId,
                    publishedAt = item.snippet.publishedAt.toString()
                )
            }
            SearchResult(videos, response.nextPageToken)
        } catch (e: IOException) {
            Log.e(TAG, "YouTube API error: ${e.message}", e)
            SearchResult(emptyList())
        }
    }

    suspend fun getVideoDetails(
        videoId: String,
        accessToken: String?
    ): VideoDetails? = withContext(Dispatchers.IO) {
        try {
            val youtube = getYouTubeService(accessToken)
            val videoList = youtube.videos().list(listOf("snippet,contentDetails,statistics"))
            videoList.id = listOf(videoId)

            val response = videoList.execute()
            val item = response.items.firstOrNull() ?: return@withContext null

            val video = Video(
                id = item.id,
                title = item.snippet.title,
                description = item.snippet.description,
                thumbnailUrl = item.snippet.thumbnails?.medium?.url ?: "",
                channelTitle = item.snippet.channelTitle,
                channelId = item.snippet.channelId,
                publishedAt = item.snippet.publishedAt.toString(),
                duration = item.contentDetails?.duration ?: "",
                viewCount = item.statistics?.viewCount?.toString()?.toLongOrNull() ?: 0,
                likeCount = item.statistics?.likeCount?.toString()?.toLongOrNull() ?: 0
            )

            VideoDetails(
                video = video,
                tags = item.snippet.tags ?: emptyList(),
                categoryId = item.snippet.categoryId ?: "",
                defaultLanguage = item.snippet.defaultLanguage ?: ""
            )
        } catch (e: IOException) {
            Log.e(TAG, "YouTube API error: ${e.message}", e)
            null
        }
    }

    suspend fun getTrendingVideos(
        accessToken: String?,
        regionCode: String = "RU",
        pageToken: String? = null
    ): SearchResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "trending: start (token=${accessToken != null})")
            val youtube = getYouTubeService(accessToken)
            Log.i(TAG, "trending: service built")
            val videoList = youtube.videos().list(listOf("snippet,contentDetails,statistics"))
            videoList.chart = "mostPopular"
            videoList.regionCode = regionCode
            videoList.maxResults = 20
            videoList.pageToken = pageToken

            val response = videoList.execute()
            Log.i(TAG, "trending: response items=${response.items?.size}")
            val videos = response.items.map { item ->
                Video(
                    id = item.id,
                    title = item.snippet.title,
                    description = item.snippet.description,
                    thumbnailUrl = item.snippet.thumbnails?.medium?.url ?: "",
                    channelTitle = item.snippet.channelTitle,
                    channelId = item.snippet.channelId,
                    publishedAt = item.snippet.publishedAt.toString(),
                    duration = item.contentDetails?.duration ?: "",
                    viewCount = item.statistics?.viewCount?.toString()?.toLongOrNull() ?: 0,
                    likeCount = item.statistics?.likeCount?.toString()?.toLongOrNull() ?: 0
                )
            }
            SearchResult(videos, response.nextPageToken)
        } catch (e: IOException) {
            Log.e(TAG, "YouTube API error: ${e.message}", e)
            SearchResult(emptyList())
        }
    }

    suspend fun getChannelVideos(
        channelId: String,
        accessToken: String?,
        pageToken: String? = null
    ): SearchResult = withContext(Dispatchers.IO) {
        try {
            val youtube = getYouTubeService(accessToken)
            val searchList = youtube.search().list(listOf("snippet"))
            searchList.channelId = channelId
            searchList.maxResults = 20
            searchList.type = listOf("video")
            searchList.pageToken = pageToken

            val response = searchList.execute()
            val videos = response.items.map { item ->
                Video(
                    id = item.id.videoId,
                    title = item.snippet.title,
                    description = item.snippet.description,
                    thumbnailUrl = item.snippet.thumbnails?.medium?.url ?: "",
                    channelTitle = item.snippet.channelTitle,
                    channelId = item.snippet.channelId,
                    publishedAt = item.snippet.publishedAt.toString()
                )
            }
            SearchResult(videos, response.nextPageToken)
        } catch (e: IOException) {
            Log.e(TAG, "YouTube API error: ${e.message}", e)
            SearchResult(emptyList())
        }
    }

    suspend fun getSubscriptions(
        accessToken: String?,
        pageToken: String? = null
    ): List<Channel> = withContext(Dispatchers.IO) {
        try {
            val youtube = getYouTubeService(accessToken)
            val subsList = youtube.subscriptions().list(listOf("snippet,contentDetails"))
            subsList.mine = true
            subsList.maxResults = 50
            subsList.pageToken = pageToken

            val response = subsList.execute()
            response.items.map { item ->
                Channel(
                    id = item.snippet.resourceId.channelId,
                    title = item.snippet.title,
                    description = item.snippet.description,
                    thumbnailUrl = item.snippet.thumbnails?.medium?.url ?: ""
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "YouTube API error: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getPlaylists(
        accessToken: String?,
        pageToken: String? = null
    ): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            val youtube = getYouTubeService(accessToken)
            val playlistsList = youtube.playlists().list(listOf("snippet,contentDetails"))
            playlistsList.mine = true
            playlistsList.maxResults = 50
            playlistsList.pageToken = pageToken

            val response = playlistsList.execute()
            response.items.map { item ->
                Playlist(
                    id = item.id,
                    title = item.snippet.title,
                    description = item.snippet.description,
                    thumbnailUrl = item.snippet.thumbnails?.medium?.url ?: "",
                    itemCount = item.contentDetails?.itemCount?.toInt() ?: 0
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "YouTube API error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Latest uploads of the channels the user is subscribed to.
     *
     * Uses OAuth access token ONLY - no API key parameter. The key itself is
     * currently blocked (API_KEY_SERVICE_BLOCKED), while OAuth requests are
     * authenticated separately and may still pass; on failure returns empty.
     */
    suspend fun getSubscriptionUploads(
        accessToken: String?,
        maxChannels: Int = 8,
        perChannel: Int = 5
    ): List<Video> = withContext(Dispatchers.IO) {
        if (accessToken.isNullOrBlank()) return@withContext emptyList()
        try {
            val youtube = getYouTubeService(accessToken, withKey = false)

            val subs = youtube.subscriptions().list(listOf("snippet"))
                .setMine(true)
                .setMaxResults(50)
                .execute()
            val channelIds = subs.items.mapNotNull { it.snippet.resourceId?.channelId }
            Log.i(TAG, "oauth: subscriptions=${channelIds.size}")
            if (channelIds.isEmpty()) return@withContext emptyList()

            val channels = youtube.channels().list(listOf("contentDetails"))
                .setId(channelIds.take(maxChannels))
                .execute()
            val uploadPlaylists = channels.items.mapNotNull {
                it.contentDetails?.relatedPlaylists?.uploads
            }

            val videos = mutableListOf<Video>()
            for (playlistId in uploadPlaylists) {
                try {
                    val items = youtube.playlistItems().list(listOf("snippet"))
                        .setPlaylistId(playlistId)
                        .setMaxResults(perChannel.toLong())
                        .execute()
                    for (item in items.items) {
                        val sn = item.snippet ?: continue
                        val videoId = sn.resourceId?.videoId ?: continue
                        videos.add(
                            Video(
                                id = videoId,
                                title = sn.title ?: "",
                                description = sn.description ?: "",
                                thumbnailUrl = sn.thumbnails?.medium?.url
                                    ?: sn.thumbnails?.default?.url ?: "",
                                channelTitle = sn.channelTitle ?: "",
                                channelId = sn.channelId ?: "",
                                publishedAt = sn.publishedAt?.toString() ?: ""
                            )
                        )
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "oauth: playlist $playlistId failed: ${e.message}")
                }
            }
            videos
                .sortedByDescending { it.publishedAt }
                .also { Log.i(TAG, "oauth: subscription uploads=${it.size}") }
        } catch (e: IOException) {
            val reason = (e as? com.google.api.client.googleapis.json.GoogleJsonResponseException)
                ?.details?.errors?.firstOrNull()?.reason
            Log.e(TAG, "oauth subscriptions feed failed: ${reason ?: e.message}")
            emptyList()
        }
    }

    /**
     * Recommendations for the watch history through the OFFICIAL Data API.
     *
     * The documented `relatedToVideoId` parameter was removed server-side
     * (verified at runtime: HTTP 400 `badRequest Request contains an invalid
     * argument`, and it is gone from the discovery document as well), so the
     * official fallback described in the docs is used: take the strongest
     * history seeds, read their titles via `videos.list` (1 quota unit) and
     * run `search.list` over those keywords (100 units per seed) — the same
     * "related" effect built from what the user watched.
     *
     * On any error returns what was collected so far (empty on failure).
     */
    suspend fun getRelatedVideos(
        accessToken: String?,
        seedIds: List<String>,
        maxPerSeed: Int = 12
    ): List<Video> = withContext(Dispatchers.IO) {
        if (seedIds.isEmpty()) return@withContext emptyList()
        val seeds = seedIds.take(API_RELATED_SEED_LIMIT)
        val titles = try {
            videoMetadata(seeds, accessToken)
        } catch (e: IOException) {
            Log.e(TAG, "related: seed metadata failed: ${e.message}")
            emptyMap()
        }

        val result = LinkedHashMap<String, Video>()
        for (seed in seeds) {
            val query = searchQuery(titles[seed]?.title ?: continue)
            if (query.isBlank()) continue
            var items = searchOnce(query, seed, maxPerSeed, accessToken)
            if (items.isEmpty()) {
                // very specific titles sometimes return nothing: retry with
                // the leading words only (the topic, not the whole sentence)
                val shorter = query.split(Regex("\\s+")).take(4).joinToString(" ")
                if (shorter != query) items = searchOnce(shorter, seed, maxPerSeed, accessToken)
            }
            for (video in items) result.putIfAbsent(video.id, video)
        }
        result.values.toList()
    }

    /** One search.list call (100 quota units); failures are logged and give an empty list. */
    private fun searchOnce(
        query: String,
        excludeId: String,
        maxPerSeed: Int,
        accessToken: String?
    ): List<Video> = try {
        val json = apiGet(
            "youtube/v3/search",
            listOf(
                "part" to "snippet",
                "type" to "video",
                "q" to query,
                "maxResults" to maxPerSeed.toString()
            ),
            accessToken
        )
        val items = parseVideos(json, excludeId = excludeId, plainId = false)
        Log.i(TAG, "related search q='$query' -> ${items.size} items")
        items
    } catch (e: IOException) {
        Log.e(TAG, "related search q='$query' failed: ${e.message}")
        emptyList()
    }

    /**
     * Subscription/channel activity through `activities.list`. The once
     * documented `mySubscriptions=true` filter was removed server-side too
     * (verified: HTTP 400 `missingRequiredParameter No filter selected.
     * Expected one of: channelId, mine, home`), so only the accepted filters
     * are used, in the order of usefulness and quota:
     *  1. `channelId` — activity of the subscribed channels (uploads, likes,
     *     live streams): the direct replacement of `mySubscriptions`;
     *  2. `mine` — likes/uploads of the user's own channel;
     *  3. `home=true` — the classic home-feed filter (kept as the last
     *     fallback: it answers 200 OK but is empty for a live account).
     * Subscription *uploads* are covered separately by
     * [getSubscriptionUploads]; video cards are re-read through
     * `videos.list` (1 quota unit) to get real titles. Each filter costs
     * 100 quota units, the first non-empty one wins.
     */
    suspend fun getSubscriptionActivity(
        accessToken: String?,
        maxResults: Int = 25,
        channelIds: List<String> = emptyList()
    ): List<Video> = withContext(Dispatchers.IO) {
        if (accessToken.isNullOrBlank()) return@withContext emptyList()
        val filters: List<Pair<String, String>> =
            channelIds.distinct().take(2).map { "channelId" to it } +
                listOf("mine" to "true", "home" to "true")

        val activities = filters.asSequence().mapNotNull { (filter, value) ->
            val label = if (filter == "channelId") "channelId=${value.take(8)}" else filter
            try {
                val json = apiGet(
                    "youtube/v3/activities",
                    listOf(
                        "part" to "snippet,contentDetails",
                        filter to value,
                        "maxResults" to maxResults.toString()
                    ),
                    accessToken
                )
                val parsed = parseVideos(json, excludeId = null, plainId = false)
                    .distinctBy { it.id }
                Log.i(TAG, "oauth: activities[$label]=${parsed.size}")
                parsed.takeIf { it.isNotEmpty() }
            } catch (e: IOException) {
                Log.e(TAG, "oauth activities[$label] failed: ${e.message}")
                null
            }
        }.firstOrNull() ?: return@withContext emptyList()

        val metadata = try {
            videoMetadata(activities.map { it.id }, accessToken)
        } catch (e: IOException) {
            Log.w(TAG, "activity metadata failed: ${e.message}")
            emptyMap()
        }
        activities.map { metadata[it.id] ?: it }
    }

    /**
     * Raw GET of the Data API with the API key (+ OAuth token when given).
     * Non-2xx becomes an IOException carrying the API `reason`.
     */
    private fun apiGet(
        path: String,
        params: List<Pair<String, String>>,
        accessToken: String?
    ): JSONObject {
        val builder = HttpUrl.Builder()
            .scheme("https")
            .host("www.googleapis.com")
            .addPathSegments(path)
        for ((name, value) in params) builder.addQueryParameter(name, value)
        builder.addQueryParameter("key", context.getString(R.string.youtube_api_key))
        if (!accessToken.isNullOrBlank()) builder.addQueryParameter("access_token", accessToken)

        val request = Request.Builder()
            .url(builder.build())
            // Required by the "Android application" API key restriction:
            // without these headers Google answers 403 forbidden.
            .header("X-Android-Package", context.packageName)
            .header("X-Android-Cert", ANDROID_CERT_SHA1)
            .build()
        rawHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = try {
                    JSONObject(body).optJSONObject("error")
                } catch (e: Exception) {
                    null
                }
                val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
                val message = error?.optString("message")
                throw IOException(
                    "HTTP ${response.code} ${reason ?: "?"} ${(message ?: body).take(160)}"
                )
            }
            return JSONObject(body)
        }
    }

    /** videos.list metadata for ids (titles, channels, thumbnails), 1 quota unit per call. */
    private fun videoMetadata(ids: List<String>, accessToken: String?): Map<String, Video> {
        val chunk = ids.take(50)
        if (chunk.isEmpty()) return emptyMap()
        val json = apiGet(
            "youtube/v3/videos",
            listOf("part" to "snippet", "id" to chunk.joinToString(",")),
            accessToken
        )
        return parseVideos(json, excludeId = null, plainId = true).associateBy { it.id }
    }

    /** Keyword query for a video title: the main segment, max 8 words / 80 chars for search.list. */
    private fun searchQuery(title: String): String = title
        .substringBefore(" / ")
        .substringBefore(" | ")
        .substringBefore(" - ")
        .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .take(8)
        .joinToString(" ")
        .take(80)

    /** Parses `items[]` of search/activities/videos responses into [Video]s. */
    private fun parseVideos(json: JSONObject, excludeId: String?, plainId: Boolean = false): List<Video> {
        val items = json.optJSONArray("items") ?: return emptyList()
        val videos = ArrayList<Video>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val snippet = item.optJSONObject("snippet") ?: continue
            val videoId = if (plainId) {
                item.optString("id").takeIf { it.isNotBlank() }
            } else {
                item.optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() }
                    ?: item.optJSONObject("contentDetails")?.optJSONObject("upload")
                        ?.optString("videoId")?.takeIf { it.isNotBlank() }
                    ?: snippet.optJSONObject("resourceId")?.optString("videoId")
                        ?.takeIf { it.isNotBlank() }
            } ?: continue
            if (videoId == excludeId) continue
            videos.add(
                Video(
                    id = videoId,
                    title = snippet.optString("title"),
                    description = snippet.optString("description"),
                    thumbnailUrl = thumbnailUrl(snippet),
                    channelTitle = snippet.optString("channelTitle"),
                    channelId = snippet.optString("channelId"),
                    publishedAt = snippet.optString("publishedAt")
                )
            )
        }
        return videos
    }

    private fun thumbnailUrl(snippet: JSONObject): String {
        val thumbnails = snippet.optJSONObject("thumbnails") ?: return ""
        return thumbnails.optJSONObject("medium")?.optString("url")?.takeIf { it.isNotBlank() }
            ?: thumbnails.optJSONObject("default")?.optString("url").orEmpty()
    }

    suspend fun getCaptionTracks(
        videoId: String,
        accessToken: String?
    ): List<CaptionTrack> = withContext(Dispatchers.IO) {
        try {
            val youtube = getYouTubeService(accessToken)
            val captionsList = youtube.captions().list(listOf("snippet"), videoId)

            val response = captionsList.execute()
            response.items.map { item ->
                CaptionTrack(
                    languageCode = item.snippet.language,
                    languageName = item.snippet.name ?: item.snippet.language,
                    url = "https://www.youtube.com/api/timedtext?lang=${item.snippet.language}&v=$videoId",
                    isAutoGenerated = item.snippet.trackKind == "ASR"
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "YouTube API error: ${e.message}", e)
            emptyList()
        }
    }
}
