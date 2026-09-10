package com.oscartv

import android.util.Log
import com.lagradost.cloudstream3.app
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject

internal const val OSCAR_LOG_TAG = "OscarTV"

internal fun logOscar(message: String) {
    Log.e(OSCAR_LOG_TAG, message)
}

private fun logOscarDebug(message: String) {
    Log.d(OSCAR_LOG_TAG, message)
}

private fun Throwable.safeLogMessage(): String {
    return message.orEmpty().replace(Regex("\\s+"), " ").take(200)
}

internal object OscarTVConfig {
    const val apiBase = "https://ostvapp.cam"
    // Both hosts currently serve the same /uploads paths; keep this decision centralized.
    const val imageBase = "https://ostvapp.cam"
    const val requestTimeoutSeconds = 20
    const val pageConcurrency = 4
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

    private fun requestPath(path: String, query: Map<String, String>): String {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return normalizedPath + queryString.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    }

    private fun durationMs(startedNanos: Long): Long {
        return ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    private suspend fun request(path: String, query: Map<String, String> = emptyMap()): JSONObject? {
        val requestUrl = url(path, query)
        val logPath = requestPath(path, query)
        val startedNanos = System.nanoTime()

        return try {
            val response = app.get(
                requestUrl,
                headers = OscarIronSigner.headersForUrl(requestUrl) + mapOf(
                    "Accept" to "application/json",
                ),
                timeout = OscarTVConfig.requestTimeoutSeconds.toLong(),
                cacheTime = 0,
            )
            val duration = durationMs(startedNanos)

            if (response.code !in 200..299) {
                logOscar("HTTP_FAILED path=$logPath HTTP=${response.code} duration_ms=$duration")
                return null
            }

            val envelope = try {
                JSONObject(response.text)
            } catch (error: Exception) {
                logOscar(
                    "JSON_PARSE_FAILED path=$logPath exception=${error::class.java.simpleName} " +
                        "message=${error.safeLogMessage()} duration_ms=$duration",
                )
                return null
            }

            val status = envelope.optString("status")
            if (!status.equals("success", true)) {
                val message = envelope.optString("message").ifBlank {
                    envelope.optString("error")
                }.replace(Regex("\\s+"), " ").take(200)
                logOscar(
                    "API_STATUS_FAILED path=$logPath status=${status.ifBlank { "<missing>" }} " +
                        "message=${message.ifBlank { "<none>" }} duration_ms=$duration",
                )
                return null
            }

            logOscarDebug("REQUEST_OK path=$logPath HTTP=${response.code} duration_ms=$duration")
            envelope
        } catch (error: Exception) {
            logOscar(
                "HTTP_FAILED path=$logPath HTTP=? exception=${error::class.java.simpleName} " +
                    "message=${error.safeLogMessage()} duration_ms=${durationMs(startedNanos)}",
            )
            null
        }
    }

    suspend fun objectData(path: String, query: Map<String, String> = emptyMap()): JSONObject? {
        val envelope = request(path, query) ?: return null
        return envelope.optJSONObject("data") ?: run {
            logOscar("LOAD_MAPPING_FAILED path=${requestPath(path, query)} reason=data_not_object")
            null
        }
    }

    suspend fun listData(path: String, query: Map<String, String> = emptyMap()): OscarJsonPage? {
        val envelope = request(path, query) ?: return null
        val rawData = envelope.opt("data")
        val data = rawData as? JSONArray
        if (data == null) {
            logOscar("LOAD_MAPPING_FAILED path=${requestPath(path, query)} reason=data_not_array")
            return null
        }
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
        kind = "anime",
        seasonId = seasonId,
    ) { OscarEpisode.fromJson(it) }

    suspend fun seriesEpisodes(seasonId: Int): List<OscarEpisode> = pagedList(
        path = "/api/episodes/",
        fixedQuery = mapOf("season_id" to seasonId.toString(), "per_page" to "100"),
        kind = "series",
        seasonId = seasonId,
    ) { OscarEpisode.fromJson(it) }

    suspend fun animeEpisodesForSeasons(seasonIds: List<Int>): List<OscarEpisode> {
        return mapSeasonsBounded(seasonIds, "anime") { animeEpisodes(it) }.flatten()
    }

    suspend fun seriesEpisodesForSeasons(seasonIds: List<Int>): List<OscarEpisode> {
        return mapSeasonsBounded(seasonIds, "series") { seriesEpisodes(it) }.flatten()
    }

    private suspend fun <R> mapSeasonsBounded(
        seasonIds: List<Int>,
        kind: String,
        block: suspend (Int) -> List<R>,
    ): List<List<R>> {
        return seasonIds.distinct().chunked(OscarTVConfig.pageConcurrency).flatMap { chunk ->
            supervisorScope {
                chunk.map { seasonId ->
                    async {
                        try {
                            block(seasonId)
                        } catch (error: Exception) {
                            logOscar(
                                "LOAD_MAPPING_FAILED kind=$kind season=$seasonId " +
                                    "exception=${error::class.java.simpleName} message=${error.safeLogMessage()}",
                            )
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun <T> pagedList(
        path: String,
        fixedQuery: Map<String, String>,
        kind: String,
        seasonId: Int,
        mapper: (JSONObject) -> T?,
    ): List<T> {
        val firstPage = fetchEpisodePage(path, fixedQuery, kind, seasonId, 1) ?: return emptyList()
        val pages = mutableMapOf(1 to firstPage.items.mapNotNull(mapper))
        val totalPages = firstPage.pagination?.pageCount()?.coerceIn(1, 1000) ?: 1

        if (totalPages > 1) {
            val remainingPages = (2..totalPages).toList()
            remainingPages.chunked(OscarTVConfig.pageConcurrency).forEach { chunk ->
                val fetched = supervisorScope {
                    chunk.map { page ->
                        async {
                            page to fetchEpisodePage(path, fixedQuery, kind, seasonId, page)
                        }
                    }.awaitAll()
                }

                fetched.forEach { (page, response) ->
                    if (response != null) {
                        pages[page] = response.items.mapNotNull(mapper)
                    }
                }
            }
        }

        return aggregateOscarPages(pages)
    }

    private suspend fun fetchEpisodePage(
        path: String,
        fixedQuery: Map<String, String>,
        kind: String,
        seasonId: Int,
        page: Int,
    ): OscarJsonPage? {
        val retry = retryOscarPage(maxAttempts = 2) {
            listData(path, fixedQuery + ("page" to page.toString()))
        }
        if (retry.value == null) {
            logOscar(
                "EPISODE_PAGE_FAILED kind=$kind season=$seasonId page=$page " +
                    "attempts=${retry.attempts}",
            )
        }
        return retry.value
    }
}
