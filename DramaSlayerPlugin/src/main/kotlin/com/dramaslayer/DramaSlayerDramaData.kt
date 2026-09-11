package com.dramaslayer

import org.json.JSONObject

data class DramaData(
    val dramaId: String,
) {
    fun asData(): String = JSONObject()
        .put("drama_id", dramaId)
        .toString()

    companion object {
        private const val legacyPrefix = "dramaslayer://drama/"

        fun parse(data: String): DramaData? {
            val value = data.trim()
            val jsonData = runCatching {
                val json = JSONObject(value)
                json.optString("drama_id").trim().takeIf { it.isNotBlank() }
            }.getOrNull()
            if (jsonData != null) return DramaData(jsonData)

            val legacyStart = value.indexOf(legacyPrefix, ignoreCase = true)
            if (legacyStart < 0) return null
            val legacyId = value.substring(legacyStart + legacyPrefix.length).trim()
            return legacyId.takeIf {
                it.isNotBlank() && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' }
            }?.let(::DramaData)
        }
    }
}
