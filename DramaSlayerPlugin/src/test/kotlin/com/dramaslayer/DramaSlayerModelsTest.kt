package com.dramaslayer

import com.lagradost.cloudstream3.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class DramaSlayerModelsTest {
    @Test
    fun exposesOneHomePage() {
        val plugin = DramaSlayerPlugin()

        assertTrue(plugin.hasMainPage)
        assertTrue(plugin.mainPage.isNotEmpty())
    }

    @Test
    fun homePaginationUsesTwentyOneItemPages() {
        assertEquals(0, dramaSlayerOffset(1))
        assertEquals(21, dramaSlayerOffset(2))
    }

    @Test
    fun homeMappingUsesMovieAndTvSeriesResponses() {
        val plugin = DramaSlayerPlugin()

        val movie = assertNotNull(plugin.toSearchResponse(sampleItem("Movie")))
        assertTrue(movie is MovieSearchResponse)
        assertEquals(TvType.Movie, movie.type)

        val series = assertNotNull(plugin.toSearchResponse(sampleItem("Series")))
        assertTrue(series is TvSeriesSearchResponse)
        assertEquals(TvType.TvSeries, series.type)
    }

    @Test
    fun episodeDataRoundTripsOnlyStableIds() {
        val original = EpisodeData("8", "92")
        assertEquals(original, EpisodeData.parse(original.asData()))
        assertTrue(original.asData().contains("episode_id"))
    }

    @Test
    fun parsesFwLinksDefensively() {
        val links = parseFwLinks(
            "[\"https://estream.to/a\",\"https://example.com/b\",\"http://ok.ru/video/123\",\"not-a-url\"]",
        )
        assertEquals(
            listOf("https://estream.to/a", "https://example.com/b", "http://ok.ru/video/123"),
            links,
        )
    }

    @Test
    fun parsesDetailsEpisodesAndTypedCasts() {
        val details = parseDramaDetails(
            """
            {
              "drama_id":"8", "drama_name":"49 Days", "drama_type":"Series",
              "drama_status":"Completed", "drama_country":"كوري", "drama_release_date":"2011",
              "drama_description":"plot", "drama_genres":"خيال, رومانسي",
              "drama_cover_image_url":"https://img.example/49.jpg",
              "casts":{"roles":["Main Role"],"groups":{"Main Role":[
                {"cast_id":"35","cast_name":"Ji Hyun","actor_name":"Lee Yo Won","actor_image_url":"https://img.example/a.jpg"}
              ]}}
            }
            """.trimIndent(),
        )
        assertNotNull(details)
        assertEquals("8", details.dramaId)
        assertEquals(1, details.casts?.groups?.get("Main Role")?.size)

        val episodes = parseEpisodes(
            """
            {"episodes":[
              {"episode_id":"92","episode_name":"الحلقة : 1","episode_number":"1",
               "episode_urls":[{"episode_server_name":"CDN","episode_url":"https://s.drslayer.com/a.mp4"}]},
              {"episode_id":"93","episode_name":"الحلقة : 2","episode_number":"2"}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, episodes.size)
        assertEquals("92", episodes.first().episodeId)
        assertEquals("CDN", episodes.first().episodeUrls.first().serverName)
    }

    private fun sampleItem(type: String): DramaItem {
        return DramaItem(
            dramaId = "8",
            dramaName = "49 Days",
            alternativeTitles = null,
            dramaType = type,
            country = "كوري",
            releaseDate = "2011",
            genres = null,
            status = null,
            posterUrl = "https://img.example/49.jpg",
        )
    }
}
