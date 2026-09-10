package com.lagradost.cloudstream3.utils

import com.oscartv.OscarItemData
import kotlin.reflect.KClass
import org.json.JSONObject

/** Minimal host shim for JVM tests; CloudStream supplies the real AppUtils on Android. */
object AppUtils {
    fun toJson(value: Any): String {
        val data = value as OscarItemData
        return JSONObject().put("type", data.type).put("id", data.id).toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> parseJson(value: String, type: KClass<T>): T {
        require(type == OscarItemData::class)
        val json = JSONObject(value)
        return OscarItemData(json.getString("type"), json.getInt("id")) as T
    }
}
