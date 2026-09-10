package com.oscartv

import kotlinx.coroutines.runBlocking
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test

class OscarModelsTest {
    @Test
    fun itemDataUsesJsonAndReadsLegacyValues() {
        val animeData = OscarItemId(OscarItemType.Anime, 46).asData()
        assertTrue(animeData.trimStart().startsWith("{"))
        assertEquals(OscarItemData("anime", 46), OscarItemData.parse(animeData))
        assertEquals(
            OscarItemId(OscarItemType.Anime, 46),
            OscarItemId.parse("oscar://anime/46"),
        )
        assertEquals(
            OscarItemId(OscarItemType.AnimeEpisode, 8609),
            OscarItemId.parse("oscar://anime-episode/8609"),
        )
    }

    @Test
    fun itemNamespacesDoNotCollide() {
        val anime = OscarItemId(OscarItemType.Anime, 46).asData()
        val series = OscarItemId(OscarItemType.Series, 46).asData()
        assertNotEquals(anime, series)
        assertEquals(OscarItemId(OscarItemType.Anime, 46), OscarItemId.parse(anime))
        assertEquals(OscarItemId(OscarItemType.Series, 46), OscarItemId.parse(series))
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

    @Test
    fun pageAggregationOrdersPagesAndKeepsOtherPagesAfterFailure() {
        val pages = mapOf(
            3 to listOf("page3"),
            1 to listOf("page1-a", "page1-b"),
            4 to listOf("page4"),
            // Page 2 is intentionally absent, representing a failed page fetch.
        )
        assertEquals(
            listOf("page1-a", "page1-b", "page3", "page4"),
            aggregateOscarPages(pages),
        )
    }

    @Test
    fun pageRetryCanRecoverOnce() = runBlocking {
        var attempts = 0
        val result = retryOscarPage(maxAttempts = 2) {
            attempts++
            if (attempts == 2) "ok" else null
        }
        assertEquals("ok", result.value)
        assertEquals(2, result.attempts)
    }

    @Test
    fun pageRetryTerminatesAfterTheConfiguredAttempts() = runBlocking {
        var attempts = 0
        val result = retryOscarPage<String>(maxAttempts = 2) {
            attempts++
            null
        }
        assertNull(result.value)
        assertEquals(2, result.attempts)
        assertEquals(2, attempts)
    }

    @Test
    fun watchLinkKeepsDeepLinkAndExtractsUserAgent() {
        val link = OscarWatchLink.fromJson(
            JSONObject(
                """
                {
                  "url": "https://cdn.seriesmp4.com/video.mp4",
                  "quality": "480p",
                  "type": "direct",
                  "deep_link": "tdmvideo://play?url=https://cdn.seriesmp4.com/video.mp4&ua=TDMuaPlayer"
                }
                """.trimIndent(),
            ),
        )
        assertEquals("tdmvideo://play?url=https://cdn.seriesmp4.com/video.mp4&ua=TDMuaPlayer", link?.deepLink)
        assertEquals("TDMuaPlayer", link?.mediaHeaders()?.get("User-Agent"))
    }

    @Test
    fun deepLinkQueryValuesAreUrlDecoded() {
        val hints = "tdmvideo://play?ua=TDMuaPlayer%2F1.0&title=One%20Piece Ep 001".toOscarPlaybackHints()
        assertEquals("TDMuaPlayer/1.0", hints.userAgent)
    }

    @Test
    fun nullDeepLinkUsesMediaUserAgentFallback() {
        val link = OscarWatchLink(
            serverName = "server",
            url = "https://cdn.seriesmp4.com/video.mp4",
            quality = "480p",
            type = "direct",
        )
        assertEquals("TDMuaPlayer", link.mediaHeaders()["User-Agent"])
    }

    @Test
    fun mediaHeadersNeverContainIronHeadersOrReferer() {
        val link = OscarWatchLink(
            serverName = "server",
            url = "https://cdn.seriesmp4.com/video.mp4",
            quality = "480p",
            type = "direct",
            deepLink = "tdmvideo://play?ua=TDMuaPlayer",
        )
        val headers = link.mediaHeaders()
        assertTrue(headers.keys.none { it.startsWith("X-Iron-", ignoreCase = true) })
        assertTrue(headers.keys.none { it.equals("Referer", ignoreCase = true) })
    }

    @Test
    fun mediaHostAndTypeAreMappedSafely() {
        val link = OscarWatchLink(
            serverName = "server",
            url = "https://cdn.seriesmp4.com/video.mp4?token=1",
            quality = "480p",
            type = "direct",
        )
        assertEquals("cdn.seriesmp4.com", link.mediaHost())
        assertEquals(OscarMediaType.VIDEO, link.url!!.toOscarMediaType())
        assertEquals(OscarMediaType.M3U8, "https://cdn.example/live.m3u8?token=1".toOscarMediaType())
    }
}
