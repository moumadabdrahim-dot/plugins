package com.dramaslayer

import android.util.Log
import com.lagradost.cloudstream3.app
import java.net.URLDecoder
import java.net.URLEncoder

internal object DramaSlayerConfig {
    const val apiBase = "https://drslayer.com/drama/public"
    const val fwEndpoint = "https://drslayer.com/servers/public/api/fw"
    const val requestTimeoutSeconds = 25L
    const val pageSize = 21
}

private const val DRAMA_SLAYER_LOG_TAG = "DramaSlayer"

internal fun logDramaSlayer(message: String) {
    Log.d(DRAMA_SLAYER_LOG_TAG, message)
}

internal fun dramaSlayerOffset(page: Int): Int {
    return (page.coerceAtLeast(1) - 1) * DramaSlayerConfig.pageSize
}

internal class DramaSlayerApi(
    private val baseUrl: () -> String = { DramaSlayerConfig.apiBase },
    private val infProvider: DramaSlayerInfProvider = EmptyDramaSlayerInfProvider,
) {
    private fun endpoint(path: String): String = baseUrl().trimEnd('/') + "/" + path.trimStart('/')

    private fun withQuery(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$url?$query"
    }

    private val headers = mapOf(
        "Accept" to "application/vnd.drama-app-api.v1+json, application/json, text/plain, */*",
        "Accept-Language" to "ar,ar-MA;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "okhttp/4.12.0",
    )

    private fun decodeApiPayload(text: String): Any? {
        val root = parseJsonValue(text)
        val envelope = root as? org.json.JSONObject ?: return root
        val raw = RawResponse.fromJson(envelope)
        return if (raw != null) {
            parseJsonValue(DramaSlayerCrypto.decrypt(raw))
        } else {
            envelope
        }
    }

    private suspend fun getPayload(url: String, cacheTime: Int): Any? {
        return try {
            val response = app.get(
                url,
                headers = headers,
                timeout = DramaSlayerConfig.requestTimeoutSeconds,
                cacheTime = cacheTime,
            )
            if (response.code !in 200..299) {
                logDramaSlayer("HTTP_FAILED method=GET endpoint=${url.substringBefore('?')} code=${response.code}")
                null
            } else {
                decodeApiPayload(response.text)
            }
        } catch (error: Exception) {
            logDramaSlayer(
                "REQUEST_FAILED method=GET endpoint=${url.substringBefore('?')} " +
                    "exception=${error::class.java.simpleName}",
            )
            null
        }
    }

    private suspend fun postPayload(url: String, data: Map<String, String>, cacheTime: Int): Any? {
        return try {
            val response = app.post(
                url,
                data = data,
                headers = headers,
                timeout = DramaSlayerConfig.requestTimeoutSeconds,
                cacheTime = cacheTime,
            )
            if (response.code !in 200..299) {
                logDramaSlayer("HTTP_FAILED method=POST endpoint=$url code=${response.code}")
                null
            } else {
                decodeApiPayload(response.text)
            }
        } catch (error: Exception) {
            logDramaSlayer(
                "REQUEST_FAILED method=POST endpoint=$url " +
                    "exception=${error::class.java.simpleName}",
            )
            null
        }
    }

    private suspend fun publishedDrama(page: Int, keyword: String? = null): List<DramaItem> {
        val offset = dramaSlayerOffset(page)
        val requestJson = org.json.JSONObject()
            .put("list_type", "all")
            .put("_offset", offset)
            .put("_limit", DramaSlayerConfig.pageSize)
            .apply {
                keyword?.trim()?.takeIf { it.isNotBlank() }?.let { put("keyword", it) }
            }
        val payload = getPayload(
            withQuery(
                endpoint("drama-app-api/get-all-published-drama"),
                mapOf(
                    "json" to requestJson.toString(),
                    "offset" to offset.toString(),
                    "list_type" to "all",
                    "limit" to DramaSlayerConfig.pageSize.toString(),
                ),
            ),
            cacheTime = 5,
        ) ?: return emptyList()
        return parseDramaItems(payload.toString()).distinctBy { it.dramaId }
    }

    suspend fun search(query: String, page: Int = 1): List<DramaItem> {
        return publishedDrama(page, query)
    }

    suspend fun getAll(page: Int = 1): List<DramaItem> {
        return publishedDrama(page)
    }

    suspend fun details(dramaId: String): DramaDetails? {
        val payload = getPayload(
            withQuery(endpoint("drama-app-api/get-published-drama-info"), mapOf("drama_id" to dramaId)),
            cacheTime = 5,
        ) ?: return null
        return DramaDetails.fromJson(payload as? org.json.JSONObject ?: return null)
    }

    private suspend fun episodePayload(dramaId: String, episodeId: String? = null): Any? {
        val json = org.json.JSONObject().put("drama_id", dramaId)
        if (episodeId != null) json.put("episode_id", episodeId)
        val inf = runCatching { infProvider.getInf() }.getOrElse {
            logDramaSlayer("INF_FAILED exception=${it::class.java.simpleName}")
            ""
        }
        return postPayload(
            endpoint("drama-app-api/get-episodes-auth"),
            mapOf("inf" to inf, "json" to json.toString()),
            cacheTime = if (episodeId == null) 5 else 0,
        )
    }

    suspend fun episodes(dramaId: String): List<DramaEpisode> {
        val payload = episodePayload(dramaId) ?: return emptyList()
        return parseEpisodes(payload.toString())
    }

    suspend fun episode(dramaId: String, episodeId: String): DramaEpisode? {
        val payload = episodePayload(dramaId, episodeId) ?: return null
        val episodes = parseEpisodes(payload.toString())
        return episodes.firstOrNull { it.episodeId == episodeId } ?: episodes.firstOrNull()
    }

    suspend fun expandFw(streamerUrl: String): List<String> {
        val n = Regex("(?:^|[?&])n=([^&#]*)", RegexOption.IGNORE_CASE)
            .find(streamerUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.trim()
            .orEmpty()
        if (n.isBlank()) return emptyList()

        return try {
            val response = app.post(
                DramaSlayerConfig.fwEndpoint,
                data = mapOf("n" to n),
                headers = headers,
                timeout = DramaSlayerConfig.requestTimeoutSeconds,
                cacheTime = 0,
            )
            if (response.code !in 200..299) {
                logDramaSlayer("FW_FAILED code=${response.code}")
                emptyList()
            } else {
                parseFwLinks(response.text)
            }
        } catch (error: Exception) {
            logDramaSlayer("FW_FAILED exception=${error::class.java.simpleName}")
            emptyList()
        }
    }
}
