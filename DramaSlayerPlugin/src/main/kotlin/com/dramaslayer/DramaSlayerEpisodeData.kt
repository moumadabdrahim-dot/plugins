package com.dramaslayer

import org.json.JSONObject

data class EpisodeData(
    val dramaId: String,
    val episodeId: String,
) {
    fun asData(): String = JSONObject()
        .put("drama_id", dramaId)
        .put("episode_id", episodeId)
        .toString()

    companion object {
        fun parse(data: String): EpisodeData? {
            return runCatching {
                val json = JSONObject(data)
                val dramaId = json.optString("drama_id").trim().ifBlank {
                    json.optString("dramaId").trim()
                }
                val episodeId = json.optString("episode_id").trim().ifBlank {
                    json.optString("episodeId").trim()
                }
                if (dramaId.isBlank() || episodeId.isBlank()) null
                else EpisodeData(dramaId, episodeId)
            }.getOrNull()
        }
    }
}
