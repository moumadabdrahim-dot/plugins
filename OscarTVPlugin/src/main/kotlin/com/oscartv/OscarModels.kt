package com.oscartv

import org.json.JSONArray
import org.json.JSONObject

internal enum class OscarItemType(val key: String) {
    Anime("anime"),
    Movie("movie"),
    Series("series"),
    AnimeEpisode("anime-episode"),
    SeriesEpisode("series-episode"),
}

internal data class OscarItemId(
    val type: OscarItemType,
    val id: Int,
) {
    fun asData(): String = "oscar://${type.key}/$id"

    companion object {
        private val pattern = Regex("^oscar://(anime|movie|series|anime-episode|series-episode)/(\\d+)$")

        fun parse(value: String): OscarItemId? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val type = OscarItemType.entries.firstOrNull { it.key == match.groupValues[1] } ?: return null
            return match.groupValues[2].toIntOrNull()?.let { OscarItemId(type, it) }
        }
    }
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
) {
    companion object {
        fun fromJson(json: JSONObject): OscarWatchLink? {
            val url = json.optStringOrNull("url") ?: return null
            return OscarWatchLink(
                serverName = json.optStringOrNull("server_name"),
                url = url,
                quality = json.optStringOrNull("quality"),
                type = json.optStringOrNull("type"),
            )
        }
    }
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
        else -> value?.toString()?.trim()?.toIntOrNull()
    }
}

internal fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (isNull(name)) return null
    val value = opt(name)
    return when (value) {
        is Number -> value.toDouble()
        else -> value?.toString()?.trim()?.toDoubleOrNull()
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
