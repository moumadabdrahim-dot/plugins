package com.oscartv

import com.lagradost.cloudstream3.app
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

internal object OscarTVConfig {
    const val apiBase = "https://ostvapp.cam"
    // Both hosts currently serve the same /uploads paths; keep this decision centralized.
    const val imageBase = "https://ostvapp.cam"
}

internal fun resolveOscarImage(path: String?): String? {
    val value = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
    if (value.startsWith("//")) return "https:$value"
    return runCatching {
        URI(OscarTVConfig.imageBase).resolve(if (value.startsWith('/')) value else "/$value").toString()
    }.getOrNull()
}

internal class OscarApiClient(
    private val baseUrl: () -> String = { OscarTVConfig.apiBase },
) {
    private fun url(path: String, query: Map<String, String> = emptyMap()): String {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val querySuffix = queryString.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return baseUrl().trimEnd('/') + normalizedPath + querySuffix
    }

    private suspend fun request(path: String, query: Map<String, String> = emptyMap()): JSONObject? {
        val requestUrl = url(path, query)
        return runCatching {
            val response = app.get(
                requestUrl,
                headers = OscarIronSigner.headersForUrl(requestUrl),
                cacheTime = 0,
            )
            val envelope = JSONObject(response.text)
            if (!envelope.optString("status").equals("success", true)) null else envelope
        }.getOrNull()
    }

    suspend fun objectData(path: String, query: Map<String, String> = emptyMap()): JSONObject? {
        return request(path, query)?.optJSONObject("data")
    }

    suspend fun listData(path: String, query: Map<String, String> = emptyMap()): OscarJsonPage? {
        val envelope = request(path, query) ?: return null
        val data = envelope.optJSONArray("data") ?: JSONArrayEmpty
        return OscarJsonPage(
            items = data.objects(),
            pagination = OscarPagination.fromJson(envelope.optJSONObject("pagination")),
        )
    }

    suspend fun animeSearch(query: String): OscarJsonPage? = listData(
        "/api/anime/",
        mapOf("page" to "1", "limit" to "20", "search" to query),
    )

    suspend fun movieSearch(query: String): OscarJsonPage? = listData(
        "/api/movies/",
        mapOf("page" to "1", "limit" to "20", "search" to query),
    )

    suspend fun seriesSearch(query: String): OscarJsonPage? = listData(
        "/api/series/",
        mapOf("page" to "1", "limit" to "20", "search" to query),
    )

    suspend fun animeEpisodes(seasonId: Int): List<OscarEpisode> = pagedList(
        path = "/api/anime/episodes/",
        fixedQuery = mapOf("season_id" to seasonId.toString(), "per_page" to "100"),
    ) { OscarEpisode.fromJson(it) }

    suspend fun seriesEpisodes(seasonId: Int): List<OscarEpisode> = pagedList(
        path = "/api/episodes/",
        fixedQuery = mapOf("season_id" to seasonId.toString(), "per_page" to "100"),
    ) { OscarEpisode.fromJson(it) }

    private suspend fun <T> pagedList(
        path: String,
        fixedQuery: Map<String, String>,
        mapper: (JSONObject) -> T?,
    ): List<T> {
        val result = mutableListOf<T>()
        var page = 1
        var totalPages: Int? = null

        while (page <= (totalPages ?: 1000)) {
            val response = listData(path, fixedQuery + ("page" to page.toString())) ?: break
            result += response.items.mapNotNull(mapper)
            totalPages = response.pagination?.pageCount() ?: totalPages

            if (totalPages == null && response.items.size < (fixedQuery["per_page"]?.toIntOrNull() ?: 100)) {
                break
            }
            if (response.items.isEmpty()) break
            page++
        }
        return result
    }
}

private val JSONArrayEmpty = org.json.JSONArray()
