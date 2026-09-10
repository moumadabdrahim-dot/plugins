@file:OptIn(com.lagradost.cloudstream3.InternalAPI::class)

package com.oscartv

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URI
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

internal enum class OscarItemType(
    val key: String,
    val legacyKey: String = key,
) {
    Anime("anime"),
    Movie("movie"),
    Series("series"),
    AnimeEpisode("anime_episode", "anime-episode"),
    SeriesEpisode("series_episode", "series-episode");

    companion object {
        fun fromKey(value: String?): OscarItemType? {
            val key = value?.trim() ?: return null
            return entries.firstOrNull { it.key == key || it.legacyKey == key }
        }
    }
}

internal data class OscarItemData(
    val type: String,
    val id: Int,
) {
    fun toItemId(): OscarItemId? {
        return OscarItemType.fromKey(type)?.let { OscarItemId(it, id) }
    }

    companion object {
        fun parse(value: String): OscarItemData? {
            val normalized = value.trim()
            runCatching { parseJson<OscarItemData>(normalized, OscarItemData::class) }
                .getOrNull()
                ?.takeIf { OscarItemType.fromKey(it.type) != null }
                ?.let { return it }

            val match = LEGACY_ITEM_PATTERN.matchEntire(normalized) ?: return null
            val type = OscarItemType.fromKey(match.groupValues[1]) ?: return null
            return match.groupValues[2].toIntOrNull()?.let {
                OscarItemData(type.key, it)
            }
        }
    }
}

internal data class OscarItemId(
    val type: OscarItemType,
    val id: Int,
) {
    fun asData(): String = OscarItemData(type.key, id).toJson()

    companion object {
        fun parse(value: String): OscarItemId? {
            return OscarItemData.parse(value)?.toItemId()
        }
    }
}

private val LEGACY_ITEM_PATTERN =
    Regex("^oscar://(anime|movie|series|anime-episode|series-episode)/(\\d+)$")

internal data class OscarRetryResult<T>(
    val value: T?,
    val attempts: Int,
)

internal suspend fun <T> retryOscarPage(
    maxAttempts: Int = 2,
    fetch: suspend () -> T?,
): OscarRetryResult<T> {
    val attemptsLimit = maxAttempts.coerceAtLeast(1)
    var attempts = 0
    while (attempts < attemptsLimit) {
        attempts++
        val value = fetch()
        if (value != null) return OscarRetryResult(value, attempts)
    }
    return OscarRetryResult(null, attempts)
}

internal fun <T> aggregateOscarPages(pages: Map<Int, List<T>>): List<T> {
    return pages.toSortedMap().values.flatten()
}

internal data class OscarPagination(
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val totalPages: Int? = null,
) {
    fun pageCount(): Int {
        totalPages?.takeIf { it > 0 }?.let { return it }
        val count = total ?: return 1
        val size = limit?.takeIf { it > 0 } ?: return 1
        return ((count + size - 1) / size).coerceAtLeast(1)
    }

    companion object {
        fun fromJson(json: JSONObject?): OscarPagination? {
            if (json == null) return null
            return OscarPagination(
                page = json.optIntOrNull("page"),
                limit = json.optIntOrNull("limit") ?: json.optIntOrNull("per_page"),
                total = json.optIntOrNull("total"),
                totalPages = json.optIntOrNull("total_pages") ?: json.optIntOrNull("last_page"),
            )
        }
    }
}

internal data class OscarJsonPage(
    val items: List<JSONObject>,
    val pagination: OscarPagination?,
)

internal data class OscarCatalogItem(
    val id: Int,
    val titleAr: String?,
    val titleEn: String?,
    val titleJp: String?,
    val poster: String?,
    val banner: String?,
    val story: String?,
    val year: Int?,
    val rating: Double?,
    val episodeCount: Int?,
    val animeType: String?,
    val status: String?,
    val genres: List<String>,
) {
    val title: String
        get() = titleAr.orEmpty().ifBlank { titleEn.orEmpty() }
            .ifBlank { titleJp.orEmpty() }
            .ifBlank { "OscarTV #$id" }

    val isAnimeMovie: Boolean
        get() = animeType.equals("movie", ignoreCase = true)

    companion object {
        fun fromJson(json: JSONObject): OscarCatalogItem? {
            val id = json.optIntOrNull("id") ?: return null
            return OscarCatalogItem(
                id = id,
                titleAr = json.optStringOrNull("title_ar"),
                titleEn = json.optStringOrNull("title_en"),
                titleJp = json.optStringOrNull("title_jp"),
                poster = json.optStringOrNull("poster"),
                banner = json.optStringOrNull("banner"),
                story = json.optStringOrNull("story"),
                year = json.optIntOrNull("release_year") ?: json.optIntOrNull("season_year"),
                rating = json.optDoubleOrNull("rating"),
                episodeCount = json.optIntOrNull("episode_count")
                    ?: json.optIntOrNull("episodes_count"),
                animeType = json.optStringOrNull("anime_type"),
                status = json.optStringOrNull("status"),
                genres = json.readGenres(),
            )
        }
    }
}

internal data class OscarMediaDetails(
    val id: Int,
    val titleAr: String?,
    val titleEn: String?,
    val titleJp: String?,
    val poster: String?,
    val banner: String?,
    val story: String?,
    val year: Int?,
    val rating: Double?,
    val runtime: Int?,
    val status: String?,
    val ageRating: String?,
    val animeType: String?,
    val genres: List<String>,
    val seasons: List<OscarSeason>,
    val watchLinks: List<OscarWatchLink>,
) {
    val title: String
        get() = titleAr.orEmpty().ifBlank { titleEn.orEmpty() }
            .ifBlank { titleJp.orEmpty() }
            .ifBlank { "OscarTV #$id" }

    companion object {
        fun fromJson(json: JSONObject): OscarMediaDetails? {
            val id = json.optIntOrNull("id") ?: return null
            return OscarMediaDetails(
                id = id,
                titleAr = json.optStringOrNull("title_ar"),
                titleEn = json.optStringOrNull("title_en"),
                titleJp = json.optStringOrNull("title_jp"),
                poster = json.optStringOrNull("poster"),
                banner = json.optStringOrNull("banner"),
                story = json.optStringOrNull("story"),
                year = json.optIntOrNull("release_year") ?: json.optIntOrNull("season_year"),
                rating = json.optDoubleOrNull("rating"),
                runtime = json.optIntOrNull("runtime") ?: json.optIntOrNull("episode_duration"),
                status = json.optStringOrNull("status"),
                ageRating = json.optStringOrNull("age_rating"),
                animeType = json.optStringOrNull("anime_type"),
                genres = json.readGenres(),
                seasons = json.optJSONArray("seasons")?.objects()?.mapNotNull(OscarSeason::fromJson)
                    .orEmpty(),
                watchLinks = json.optJSONArray("watch_links")?.objects()
                    ?.mapNotNull(OscarWatchLink::fromJson).orEmpty(),
            )
        }
    }
}

internal data class OscarSeason(
    val id: Int,
    val seasonNumber: Int,
    val title: String?,
    val poster: String?,
    val episodesCount: Int?,
) {
    companion object {
        fun fromJson(json: JSONObject): OscarSeason? {
            val id = json.optIntOrNull("id") ?: return null
            val seasonNumber = json.optIntOrNull("season_number") ?: return null
            return OscarSeason(
                id = id,
                seasonNumber = seasonNumber,
                title = json.optStringOrNull("title"),
                poster = json.optStringOrNull("poster"),
                episodesCount = json.optIntOrNull("episodes_count"),
            )
        }
    }
}

internal data class OscarEpisode(
    val id: Int,
    val seasonId: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val title: String?,
    val thumbnail: String?,
    val poster: String?,
    val airDate: String?,
    val duration: Int?,
    val description: String?,
    val watchLinks: List<OscarWatchLink>,
) {
    companion object {
        fun fromJson(json: JSONObject): OscarEpisode? {
            val id = json.optIntOrNull("id") ?: return null
            return OscarEpisode(
                id = id,
                seasonId = json.optIntOrNull("season_id"),
                seasonNumber = json.optIntOrNull("season_number"),
                episodeNumber = json.optIntOrNull("episode_number"),
                title = json.optStringOrNull("title"),
                thumbnail = json.optStringOrNull("thumbnail"),
                poster = json.optStringOrNull("anime_poster")
                    ?: json.optStringOrNull("series_poster"),
                airDate = json.optStringOrNull("air_date"),
                duration = json.optIntOrNull("duration"),
                description = json.optStringOrNull("description"),
                watchLinks = json.optJSONArray("watch_links")?.objects()
                    ?.mapNotNull(OscarWatchLink::fromJson).orEmpty(),
            )
        }
    }
}

internal data class OscarWatchLink(
    val serverName: String?,
    val url: String?,
    val quality: String?,
    val type: String?,
    val deepLink: String? = null,
) {
    fun mediaHeaders(): Map<String, String> {
        val userAgent = deepLink
            .toOscarPlaybackHints()
            .userAgent
            ?: OSCAR_DEFAULT_MEDIA_USER_AGENT
        return mapOf("User-Agent" to userAgent)
    }

    companion object {
        fun fromJson(json: JSONObject): OscarWatchLink? {
            val url = json.optStringOrNull("url") ?: return null
            return OscarWatchLink(
                serverName = json.optStringOrNull("server_name"),
                url = url,
                quality = json.optStringOrNull("quality"),
                type = json.optStringOrNull("type"),
                deepLink = json.optStringOrNull("deep_link"),
            )
        }
    }
}

internal data class OscarPlaybackHints(
    val userAgent: String? = null,
)

internal enum class OscarMediaType {
    VIDEO,
    M3U8,
}

internal const val OSCAR_DEFAULT_MEDIA_USER_AGENT = "TDMuaPlayer"

internal fun String?.toOscarPlaybackHints(): OscarPlaybackHints {
    val deepLink = this?.trim().orEmpty()
    if (deepLink.isBlank()) return OscarPlaybackHints()

    val query = runCatching {
        URI(deepLink.replace(" ", "%20")).rawQuery.orEmpty()
    }.getOrElse {
        deepLink.substringAfter('?', "")
    }
    val params = query.split('&').mapNotNull { part ->
        val separator = part.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val key = decodeOscarQueryValue(part.substring(0, separator)) ?: return@mapNotNull null
        val value = decodeOscarQueryValue(part.substring(separator + 1)) ?: return@mapNotNull null
        key to value
    }.toMap()

    return OscarPlaybackHints(
        userAgent = params["ua"]?.trim()?.takeIf(::isReasonableOscarHeaderValue),
    )
}

internal fun OscarWatchLink.mediaHost(): String? {
    return url?.let { value ->
        runCatching { URI(value).host?.lowercase() }.getOrNull()
    }
}

internal fun String.toOscarMediaType(): OscarMediaType {
    return if (substringBefore('?').endsWith(".m3u8", true)) {
        OscarMediaType.M3U8
    } else {
        OscarMediaType.VIDEO
    }
}

private fun decodeOscarQueryValue(value: String): String? {
    return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
}

private fun isReasonableOscarHeaderValue(value: String): Boolean {
    return value.length in 1..200 && !value.contains('\r') && !value.contains('\n')
}

internal fun parseOscarQuality(value: String?): Int? {
    val text = value.orEmpty()
    val match = Regex("(?i)(?<!\\d)(\\d{3,4})\\s*p?(?!\\d)").find(text) ?: return null
    return match.groupValues[1].toIntOrNull()
}

internal fun JSONObject.optStringOrNull(name: String): String? {
    if (isNull(name)) return null
    return optString(name).trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
}

internal fun JSONObject.optIntOrNull(name: String): Int? {
    if (isNull(name)) return null
    val value = opt(name)
    return when (value) {
        is Number -> value.toInt()
        null -> null
        else -> value.toString().trim().toIntOrNull()
    }
}

internal fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (isNull(name)) return null
    val value = opt(name)
    return when (value) {
        is Number -> value.toDouble()
        null -> null
        else -> value.toString().trim().toDoubleOrNull()
    }
}

internal fun JSONObject.readGenres(): List<String> {
    val value = opt("genres") ?: opt("genre_list") ?: return emptyList()
    return when (value) {
        is JSONArray -> value.objects().mapNotNull { item ->
            item.optStringOrNull("name")
                ?: item.optStringOrNull("name_ar")
                ?: item.optStringOrNull("name_en")
        }
        else -> value.toString().split(',').map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("null", true) }
    }.distinct()
}

internal fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) {
        optJSONObject(index)?.let(::add)
    }
}
