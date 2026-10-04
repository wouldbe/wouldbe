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
import java.io.IOException

class YouTubeRepository(private val context: Context) {

    private companion object {
        const val TAG = "YouTubeRepository"

        // SHA-1 of the debug signing certificate (Google Cloud API key
        // "Android applications" restriction). Release builds must switch
        // this to the release keystore fingerprint.
        const val ANDROID_CERT_SHA1 = "321D3091496B51B77BBD6A14ECB068C4EAAC03AA"
    }

    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport = GoogleNetHttpTransport.newTrustedTransport()

    private fun getYouTubeService(accessToken: String?): YouTube {
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
            if (apiKey.isNotBlank()) {
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
