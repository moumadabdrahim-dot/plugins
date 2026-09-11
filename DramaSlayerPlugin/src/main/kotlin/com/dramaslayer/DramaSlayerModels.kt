package com.dramaslayer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class RawResponse(
    val result: String,
) {
    companion object {
        fun fromJson(json: JSONObject): RawResponse? {
            val result = json.optString("result").trim().ifBlank { return null }
            return RawResponse(result)
        }
    }
}

data class DramaItem(
    val dramaId: String?,
    val dramaName: String?,
    val alternativeTitles: String?,
    val dramaType: String?,
    val country: String?,
    val releaseDate: String?,
    val genres: String?,
    val status: String?,
    val posterUrl: String?,
) {
    companion object {
        fun fromJson(json: JSONObject): DramaItem? {
            val id = json.stringOrNull("drama_id", "id") ?: return null
            val name = json.stringOrNull("drama_name", "name", "title")
            val poster = json.stringOrNull("drama_cover_image_url", "drama_cover_image", "poster", "image")
            if (name == null && poster == null) return null
            return DramaItem(
                dramaId = id,
                dramaName = name,
                alternativeTitles = json.stringOrNull("alternative_titles"),
                dramaType = json.stringOrNull("drama_type", "type"),
                country = json.stringOrNull("drama_country", "country"),
                releaseDate = json.stringOrNull("drama_release_date", "release_date", "year"),
                genres = json.stringOrNull("drama_genres", "genres"),
                status = json.stringOrNull("drama_status", "status"),
                posterUrl = poster,
            )
        }
    }
}

data class DramaDetails(
    val dramaId: String?,
    val dramaName: String?,
    val alternativeTitles: String?,
    val dramaType: String?,
    val dramaStatus: String?,
    val country: String?,
    val releaseDate: String?,
    val description: String?,
    val posterUrl: String?,
    val genreIds: String?,
    val genres: String?,
    val serverName: String?,
    val casts: Casts?,
) {
    companion object {
        fun fromJson(json: JSONObject): DramaDetails? {
            val source = json.detailsObject() ?: return null
            val id = source.stringOrNull("drama_id", "id") ?: return null
            return DramaDetails(
                dramaId = id,
                dramaName = source.stringOrNull("drama_name", "name", "title"),
                alternativeTitles = source.stringOrNull("alternative_titles"),
                dramaType = source.stringOrNull("drama_type", "type"),
                dramaStatus = source.stringOrNull("drama_status", "status"),
                country = source.stringOrNull("drama_country", "country"),
                releaseDate = source.stringOrNull("drama_release_date", "release_date", "year"),
                description = source.stringOrNull("drama_description", "description", "plot"),
                posterUrl = source.stringOrNull("drama_cover_image_url", "drama_cover_image", "poster", "image"),
                genreIds = source.stringOrNull("drama_genre_ids", "genre_ids"),
                genres = source.stringOrNull("drama_genres", "genres"),
                serverName = source.stringOrNull("server_name"),
                casts = source.optJSONObject("casts")?.let(Casts::fromJson),
            )
        }
    }
}

data class Casts(
    val roles: List<String>,
    val groups: Map<String, List<Cast>>,
) {
    companion object {
        fun fromJson(json: JSONObject): Casts {
            val roles = json.optJSONArray("roles").strings()
            val groupsObject = json.optJSONObject("groups")
            val groups = linkedMapOf<String, List<Cast>>()
            if (groupsObject != null) {
                val keys = groupsObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    groups[key] = groupsObject.optJSONArray(key).objects().mapNotNull(Cast::fromJson)
                }
            }
            return Casts(roles, groups)
        }
    }
}

data class Cast(
    val castId: String?,
    val castName: String?,
    val castRole: String?,
    val actorId: String?,
    val actorName: String?,
    val actorImageUrl: String?,
) {
    companion object {
        fun fromJson(json: JSONObject): Cast? {
            return Cast(
                castId = json.stringOrNull("cast_id"),
                castName = json.stringOrNull("cast_name"),
                castRole = json.stringOrNull("cast_role"),
                actorId = json.stringOrNull("actor_id"),
                actorName = json.stringOrNull("actor_name"),
                actorImageUrl = json.stringOrNull("actor_image_url", "actor_image"),
            ).takeIf { it.actorName != null || it.castName != null }
        }
    }
}

data class DramaEpisode(
    val episodeId: String?,
    val episodeName: String?,
    val episodeNumber: String?,
    val rating: String?,
    val episodeUrls: List<EpisodeStreamer>,
) {
    companion object {
        fun fromJson(json: JSONObject): DramaEpisode? {
            val id = json.stringOrNull("episode_id", "id") ?: return null
            return DramaEpisode(
                episodeId = id,
                episodeName = json.stringOrNull("episode_name", "name", "title"),
                episodeNumber = json.stringOrNull("episode_number", "number"),
                rating = json.stringOrNull("episode_rating", "rating"),
                episodeUrls = json.optJSONArray("episode_urls").objects().mapNotNull(EpisodeStreamer::fromJson),
            )
        }
    }
}

data class EpisodeStreamer(
    val serverName: String?,
    val url: String?,
) {
    companion object {
        fun fromJson(json: JSONObject): EpisodeStreamer? {
            val url = json.stringOrNull("episode_url", "url")
            val server = json.stringOrNull("episode_server_name", "server_name", "server")
            if (url == null && server == null) return null
            return EpisodeStreamer(server, url)
        }
    }
}

internal fun parseJsonValue(text: String): Any? {
    return runCatching {
        val first = JSONTokener(text.trim().removePrefix("\uFEFF")).nextValue()
        if (first is String && first.trim().firstOrNull() in listOf('{', '[')) {
            JSONTokener(first.trim()).nextValue()
        } else {
            first
        }
    }.getOrNull()
}

internal fun parseDramaItems(payload: String): List<DramaItem> {
    return dramaItemArray(parseJsonValue(payload)).objects().mapNotNull(DramaItem::fromJson)
}

internal fun parseDramaDetails(payload: String): DramaDetails? {
    return parseDramaDetailsValue(parseJsonValue(payload))
}

internal fun parseDramaDetailsValue(value: Any?): DramaDetails? {
    return when (value) {
        is JSONObject -> {
            if (value.has("drama_id") || value.has("drama_name") || value.has("drama_cover_image_url")) {
                DramaDetails.fromJson(value)
            } else {
                listOf("data", "drama", "result")
                    .asSequence()
                    .mapNotNull { key -> parseDramaDetailsValue(value.opt(key)) }
                    .firstOrNull()
            }
        }

        is JSONArray -> {
            (0 until value.length())
                .asSequence()
                .mapNotNull { index -> parseDramaDetailsValue(value.opt(index)) }
                .firstOrNull()
        }

        is String -> {
            val parsed = parseJsonValue(value)
            if (parsed is String && parsed == value) null else parseDramaDetailsValue(parsed)
        }

        else -> null
    }
}

internal fun dramaSlayerValueType(value: Any?): String {
    return when (value) {
        is JSONObject -> "JSONObject"
        is JSONArray -> "JSONArray"
        is String -> "String"
        null -> "null"
        else -> value::class.java.simpleName
    }
}

internal fun parseEpisodes(payload: String): List<DramaEpisode> {
    return episodeArray(parseJsonValue(payload)).objects().mapNotNull(DramaEpisode::fromJson)
}

internal fun parseFwLinks(payload: String): List<String> {
    val value = parseJsonValue(payload)
    val array = when (value) {
        is JSONArray -> value
        is JSONObject -> listOf("urls", "links", "data", "result")
            .firstNotNullOfOrNull { value.optJSONArray(it) }
        else -> null
    } ?: return emptyList()
    return array.strings().map { it.trim() }
        .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
        .distinct()
}

private fun dramaItemArray(value: Any?): JSONArray {
    if (value is JSONArray) return value
    val objectValue = value as? JSONObject ?: return JSONArray()
    listOf("dramas", "items", "results", "data").forEach { key ->
        objectValue.optJSONArray(key)?.let { return it }
    }
    return JSONArray().put(objectValue)
}

private fun episodeArray(value: Any?): JSONArray {
    if (value is JSONArray) return value
    val objectValue = value as? JSONObject ?: return JSONArray()
    listOf("episodes", "items", "results", "data").forEach { key ->
        objectValue.optJSONArray(key)?.let { return it }
    }
    return if (objectValue.has("episode_id")) JSONArray().put(objectValue) else JSONArray()
}

private fun JSONObject.detailsObject(): JSONObject? {
    if (has("drama_id") || has("drama_name") || has("drama_cover_image_url")) return this
    listOf("data", "drama", "result").forEach { key ->
        optJSONObject(key)?.let { candidate ->
            if (candidate.has("drama_id") || candidate.has("drama_name")) return candidate
        }
    }
    return null
}

private fun JSONObject.stringOrNull(vararg keys: String): String? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = opt(key)?.toString()?.trim().orEmpty()
        if (value.isNotBlank() && value != "null") return value
    }
    return null
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        opt(index)?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }
    }
}
