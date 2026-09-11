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
    runCatching { Log.d(DRAMA_SLAYER_LOG_TAG, message) }
}

internal fun dramaSlayerOffset(page: Int): Int {
    return (page.coerceAtLeast(1) - 1) * DramaSlayerConfig.pageSize
}

private data class DramaRequestContext(
    val operation: String,
    val dramaId: String? = null,
    val episodeId: String? = null,
    val page: Int? = null,
    val listType: String? = null,
)

internal open class DramaSlayerApi(
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

    private fun requestContext(context: DramaRequestContext): String {
        return buildString {
            append("operation=").append(context.operation)
            context.dramaId?.let { append(" drama=").append(it) }
            context.episodeId?.let { append(" episode=").append(it) }
            context.page?.let { append(" page=").append(it.coerceAtLeast(1)) }
            context.listType?.let { append(" list_type=").append(it) }
        }
    }

    private fun decodeApiPayload(text: String, context: DramaRequestContext): Any? {
        val root = parseJsonValue(text)
        logDramaSlayer("PAYLOAD_ROOT ${requestContext(context)} type=${dramaSlayerValueType(root)}")
        val envelope = root as? org.json.JSONObject ?: return root
        val raw = RawResponse.fromJson(envelope)
        if (raw == null) {
            logDramaSlayer("PAYLOAD_PLAINTEXT ${requestContext(context)}")
            return envelope
        }

        logDramaSlayer("ENCRYPTED_PRESENT ${requestContext(context)} value=true")
        return try {
            val decrypted = parseJsonValue(DramaSlayerCrypto.decrypt(raw))
            logDramaSlayer(
                "DECRYPT_OK ${requestContext(context)} root=${dramaSlayerValueType(decrypted)}",
            )
            decrypted
        } catch (error: Exception) {
            logDramaSlayer(
                "DECRYPT_FAILED ${requestContext(context)} " +
                    "exception=${error::class.java.simpleName}",
            )
            null
        }
    }

    private suspend fun getPayload(
        url: String,
        cacheTime: Int,
        context: DramaRequestContext,
    ): Any? {
        logDramaSlayer("REQUEST_START method=GET ${requestContext(context)}")
        return try {
            val response = app.get(
                url,
                headers = headers,
                timeout = DramaSlayerConfig.requestTimeoutSeconds,
                cacheTime = cacheTime,
            )
            if (response.code !in 200..299) {
                logDramaSlayer(
                    "HTTP_FAILED method=GET ${requestContext(context)} code=${response.code}",
                )
                null
            } else {
                logDramaSlayer("HTTP_OK method=GET ${requestContext(context)} code=${response.code}")
                decodeApiPayload(response.text, context)
            }
        } catch (error: Exception) {
            logDramaSlayer(
                "REQUEST_FAILED method=GET ${requestContext(context)} " +
                    "exception=${error::class.java.simpleName}",
            )
            null
        }
    }

    private suspend fun postPayload(
        url: String,
        data: Map<String, String>,
        cacheTime: Int,
        context: DramaRequestContext,
    ): Any? {
        logDramaSlayer("REQUEST_START method=POST ${requestContext(context)}")
        return try {
            val response = app.post(
                url,
                data = data,
                headers = headers,
                timeout = DramaSlayerConfig.requestTimeoutSeconds,
                cacheTime = cacheTime,
            )
            if (response.code !in 200..299) {
                logDramaSlayer(
                    "HTTP_FAILED method=POST ${requestContext(context)} code=${response.code}",
                )
                null
            } else {
                logDramaSlayer("HTTP_OK method=POST ${requestContext(context)} code=${response.code}")
                decodeApiPayload(response.text, context)
            }
        } catch (error: Exception) {
            logDramaSlayer(
                "REQUEST_FAILED method=POST ${requestContext(context)} " +
                    "exception=${error::class.java.simpleName}",
            )
            null
        }
    }

    private suspend fun publishedDrama(
        listType: String,
        page: Int,
        keyword: String? = null,
    ): List<DramaItem> {
        val offset = dramaSlayerOffset(page)
        val requestJson = org.json.JSONObject()
            .put("list_type", listType)
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
                    "list_type" to listType,
                    "limit" to DramaSlayerConfig.pageSize.toString(),
                ),
            ),
            cacheTime = 5,
            context = DramaRequestContext(
                operation = "CATALOG",
                page = page,
                listType = listType,
            ),
        ) ?: return emptyList()
        val items = parseDramaItems(payload.toString()).distinctBy { it.dramaId }
        logDramaSlayer(
            "CATALOG_PARSE_OK ${requestContext(DramaRequestContext("CATALOG", page = page, listType = listType))} " +
                "count=${items.size}",
        )
        return items
    }

    open suspend fun search(query: String, page: Int = 1): List<DramaItem> {
        return publishedDrama("all", page, query)
    }

    open suspend fun getPublishedDrama(listType: String, page: Int = 1): List<DramaItem> {
        return publishedDrama(listType, page)
    }

    open suspend fun details(dramaId: String): DramaDetails? {
        val context = DramaRequestContext(operation = "DETAIL", dramaId = dramaId)
        val payload = getPayload(
            withQuery(endpoint("drama-app-api/get-published-drama-info"), mapOf("drama_id" to dramaId)),
            cacheTime = 5,
            context = context,
        ) ?: return null
        val details = parseDramaDetailsValue(payload)
        if (details == null) {
            logDramaSlayer("DETAIL_PARSE_FAILED ${requestContext(context)}")
        } else {
            logDramaSlayer(
                "DETAIL_PARSE_OK ${requestContext(context)} root=${dramaSlayerValueType(payload)}",
            )
        }
        return details
    }

    private suspend fun episodePayload(dramaId: String, episodeId: String? = null): Any? {
        val context = DramaRequestContext(
            operation = if (episodeId == null) "EPISODES" else "EPISODE_DETAIL",
            dramaId = dramaId,
            episodeId = episodeId,
        )
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
            context = context,
        )
    }

    open suspend fun episodes(dramaId: String): List<DramaEpisode> {
        val payload = episodePayload(dramaId) ?: return emptyList()
        val episodes = parseEpisodes(payload.toString())
        logDramaSlayer("EPISODES_PARSE_OK operation=EPISODES drama=$dramaId count=${episodes.size}")
        return episodes
    }

    open suspend fun episode(dramaId: String, episodeId: String): DramaEpisode? {
        val payload = episodePayload(dramaId, episodeId) ?: return null
        val episodes = parseEpisodes(payload.toString())
        logDramaSlayer(
            "EPISODES_PARSE_OK operation=EPISODE_DETAIL drama=$dramaId episode=$episodeId " +
                "count=${episodes.size}",
        )
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
