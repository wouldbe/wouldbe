package com.example.youtubeapp.data.repository

import android.content.Context
import android.util.Log
import com.example.youtubeapp.data.model.CaptionTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Extracts playable stream URLs from YouTube (innertube player API).
 *
 * Strategy (validated against the working proxy):
 *  1. GET https://www.youtube.com/ with the SOCS=CAI consent cookie - this
 *     yields the visitorData token and session cookies.
 *  2. POST /youtubei/v1/player using the VISIONOS client (clientName 101).
 *
 * Regular WEB/WEB_EMBEDDED clients get bot-detected from datacenter proxy IPs
 * (UNPLAYABLE / "Sign in to confirm you're not a bot"). The VISIONOS client is
 * not subject to that check and returns playabilityStatus=OK with signed
 * googlevideo URLs, an HLS variant manifest, full format list and caption tracks.
 *
 * Signed URLs are IP-bound (ip=<proxy ip>), so playback/download must use the
 * same proxy - both are routed through [ProxyRepository.buildOkHttpClient].
 */
class StreamRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val proxyRepository = ProxyRepository(appContext)

    data class StreamFormat(
        val itag: Int,
        val url: String,
        val mimeType: String,
        val quality: String?,
        val width: Int?,
        val height: Int?,
        val bitrate: Int?,
        val hasVideo: Boolean,
        val hasAudio: Boolean
    )

    data class StreamResult(
        val videoId: String,
        val hlsUrl: String?,
        val formats: List<StreamFormat>,
        val captionTracks: List<CaptionTrack>,
        val title: String,
        val author: String,
        val description: String,
        val viewCount: Long,
        val lengthSeconds: Long,
        val publishDate: String,
        val thumbnailUrl: String
    ) {
        /** Best progressive (video+audio) format if present. */
        val progressive: StreamFormat?
            get() = formats.filter { it.hasVideo && it.hasAudio }.maxByOrNull { it.bitrate ?: 0 }

        fun bestVideoFormat(maxHeight: Int): StreamFormat? =
            formats.filter { it.hasVideo && !it.hasAudio && (it.height ?: 0) <= maxHeight }
                .maxByOrNull { it.height ?: 0 }

        /**
         * Format of this video whose bitrate is closest to [targetBitrate] -
         * quality is picked from the video's own bitrate ladder.
         */
        fun formatByBitrate(targetBitrate: Int): StreamFormat? =
            formats.filter { it.hasVideo && (it.bitrate ?: 0) > 0 }
                .minByOrNull { abs((it.bitrate ?: 0) - targetBitrate) }

        val bestAudioFormat: StreamFormat?
            get() = formats.filter { it.hasAudio && !it.hasVideo }.maxByOrNull { it.bitrate ?: 0 }
    }

    private fun buildClient(): OkHttpClient = proxyRepository.buildOkHttpClient(
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    /**
     * Extracts stream URLs with retries: the homepage/session step can fail
     * transiently through a proxy (bot-check, timeout), and each attempt uses
     * a fresh session.
     */
    suspend fun extract(videoId: String): StreamResult = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (attempt in 1..EXTRACT_ATTEMPTS) {
            try {
                return@withContext extractOnce(videoId)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "extract attempt $attempt/$EXTRACT_ATTEMPTS failed: $e")
                if (attempt < EXTRACT_ATTEMPTS) delay(RETRY_DELAY_MS * attempt)
            }
        }
        throw lastError ?: StreamExtractionException("unknown extraction failure")
    }

    private fun extractOnce(videoId: String): StreamResult {
        val client = buildClient()

        // Step 1: consented homepage visit -> visitorData + session cookies
        val homeRequest = Request.Builder()
            .url("https://www.youtube.com/")
            .header("User-Agent", VISIONOS_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "SOCS=CAI; PREF=hl=en&tz=UTC")
            .build()

        val cookies = LinkedHashSet<String>()
        cookies.add("SOCS=CAI")
        cookies.add("PREF=hl=en&tz=UTC")

        val homepage: String = client.newCall(homeRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw StreamExtractionException("homepage HTTP ${response.code}")
            }
            for (header in response.headers("Set-Cookie")) {
                val pair = header.substringBefore(';')
                if (pair.contains('=')) cookies.add(pair)
            }
            response.body?.string() ?: ""
        }

        val visitorData = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").find(homepage)?.groupValues?.get(1)
            ?: Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"").find(homepage)?.groupValues?.get(1)

        Log.i(TAG, "extract: homepage=${homepage.length} chars, cookies=${cookies.size}, visitor=${visitorData != null}")

        // Step 2: player API with the VISIONOS client
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "VISIONOS")
                    put("clientVersion", "1.02")
                    put("deviceMake", "Apple")
                    put("deviceModel", "RealityDevice17,1")
                    put("osName", "visionOS")
                    put("osVersion", "26.5.23O471")
                    put("hl", "en")
                    put("gl", "US")
                    put("timeZone", "UTC")
                    put("utcOffsetMinutes", 0)
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val playerRequest = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", VISIONOS_USER_AGENT)
            .header("X-Youtube-Client-Name", "101")
            .header("X-Youtube-Client-Version", "1.02")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", cookies.joinToString("; "))
            .apply { if (visitorData != null) header("X-Goog-Visitor-Id", visitorData) }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val raw = client.newCall(playerRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw StreamExtractionException("player API HTTP ${response.code}")
            }
            response.body?.string() ?: ""
        }

        return parse(videoId, raw)
    }

    private fun parse(videoId: String, raw: String): StreamResult {
        val root = JSONObject(raw)
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status") ?: "UNKNOWN"
        Log.i(TAG, "parse: videoId=$videoId playability=$status")
        if (status != "OK") {
            val reason = playability?.optString("reason")
                ?: root.optJSONObject("playabilityStatus")?.optString("messages")
                ?: status
            throw StreamExtractionException("playability=$status reason=$reason")
        }

        val streaming = root.optJSONObject("streamingData")
        val hlsUrl = streaming?.optString("hlsManifestUrl")?.takeIf { it.isNotBlank() }

        val formats = mutableListOf<StreamFormat>()
        listOf("formats", "adaptiveFormats").forEach { key ->
            val array = streaming?.optJSONArray(key) ?: return@forEach
            for (i in 0 until array.length()) {
                val f = array.optJSONObject(i) ?: continue
                val url = f.optString("url").takeIf { it.isNotBlank() } ?: continue
                val mime = f.optString("mimeType")
                formats.add(
                    StreamFormat(
                        itag = f.optInt("itag", 0),
                        url = url,
                        mimeType = mime,
                        quality = f.optString("quality").takeIf { it.isNotBlank() },
                        width = if (f.has("width")) f.optInt("width") else null,
                        height = if (f.has("height")) f.optInt("height") else null,
                        bitrate = if (f.has("bitrate")) f.optInt("bitrate") else null,
                        hasVideo = mime.contains("video") || mime.startsWith("video"),
                        hasAudio = mime.contains("audio") || mime.startsWith("audio")
                    )
                )
            }
        }

        if (hlsUrl == null && formats.isEmpty()) {
            throw StreamExtractionException("no streams in response")
        }

        val captions = mutableListOf<CaptionTrack>()
        root.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?.let { array ->
                for (i in 0 until array.length()) {
                    val t = array.optJSONObject(i) ?: continue
                    val baseUrl = t.optString("baseUrl").takeIf { it.isNotBlank() } ?: continue
                    val langCode = t.optString("languageCode")
                    val name = t.optJSONObject("name")?.let { nameObj ->
                        nameObj.optString("simpleText").takeIf { it.isNotBlank() }
                            ?: nameObj.optJSONArray("runs")?.let { runs ->
                                (0 until runs.length())
                                    .mapNotNull { runs.optJSONObject(it)?.optString("text") }
                                    .joinToString("")
                                    .takeIf { it.isNotBlank() }
                            }
                    } ?: langCode
                    captions.add(
                        CaptionTrack(
                            languageCode = langCode,
                            languageName = name,
                            url = "$baseUrl&fmt=vtt",
                            isAutoGenerated = t.optString("kind") == "asr"
                        )
                    )
                }
            }

        val details = root.optJSONObject("videoDetails")
        val micro = root.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        val thumbnail = micro?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            ?.maxByOrNull { it.optInt("width", 0) }?.optString("url") ?: ""

        return StreamResult(
            videoId = videoId,
            hlsUrl = hlsUrl,
            formats = formats,
            captionTracks = captions,
            title = details?.optString("title") ?: "",
            author = details?.optString("author") ?: "",
            description = details?.optString("shortDescription") ?: "",
            viewCount = details?.optString("viewCount")?.toLongOrNull() ?: 0,
            lengthSeconds = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0,
            publishDate = micro?.optString("publishDate")?.takeIf { it.isNotBlank() }
                ?: micro?.optString("uploadDate") ?: "",
            thumbnailUrl = thumbnail
        )
    }

    class StreamExtractionException(message: String) : Exception(message)

    companion object {
        private const val TAG = "StreamRepository"

        /** Retries for the whole extraction (fresh session each time). */
        private const val EXTRACT_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_200L

        const val VISIONOS_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"

        init {
            Log.d(TAG, "VISIONOS innertube stream extraction initialized")
        }
    }
}
