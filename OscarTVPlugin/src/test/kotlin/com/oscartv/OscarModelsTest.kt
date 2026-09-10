package com.oscartv

import kotlin.test.assertEquals
import org.json.JSONObject
import org.junit.Test

class OscarModelsTest {
    @Test
    fun itemNamespacesDoNotCollide() {
        assertEquals("oscar://anime/46", OscarItemId(OscarItemType.Anime, 46).asData())
        assertEquals("oscar://series/46", OscarItemId(OscarItemType.Series, 46).asData())
        assertEquals(
            OscarItemId(OscarItemType.Anime, 46),
            OscarItemId.parse("oscar://anime/46"),
        )
    }

    @Test
    fun qualityParserHandlesKnownAndUnknownValues() {
        assertEquals(1080, parseOscarQuality("1080p"))
        assertEquals(720, parseOscarQuality("720p"))
        assertEquals(480, parseOscarQuality("480p"))
        assertEquals(360, parseOscarQuality("360p"))
        assertEquals(null, parseOscarQuality("HD"))
    }

    @Test
    fun paginationUsesAllReportedPages() {
        val pagination = OscarPagination.fromJson(
            JSONObject("{\"page\":1,\"limit\":100,\"total\":1177,\"total_pages\":12}"),
        )
        assertEquals(12, pagination?.pageCount())
    }
}
