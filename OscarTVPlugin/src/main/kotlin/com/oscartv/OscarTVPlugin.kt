package com.oscartv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class OscarTVPlugin : MainAPI() {
    override var mainUrl = OscarTVConfig.apiBase
    override var name = "OscarTV"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries,
    )

    private val api = OscarApiClient { mainUrl }

    override val mainPage = mainPageOf(
        "oscar://main/anime" to "أنمي OscarTV",
        "oscar://main/movies" to "أفلام OscarTV",
        "oscar://main/series" to "مسلسلات OscarTV",
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()

        val results = linkedMapOf<String, SearchResponse>()
        api.animeSearch(term)?.items.orEmpty().mapNotNull(OscarCatalogItem::fromJson)
            .forEach { item -> results[OscarItemId(OscarItemType.Anime, item.id).asData()] = item.toSearchResponse(OscarItemType.Anime) }
        api.movieSearch(term)?.items.orEmpty().mapNotNull(OscarCatalogItem::fromJson)
            .forEach { item -> results[OscarItemId(OscarItemType.Movie, item.id).asData()] = item.toSearchResponse(OscarItemType.Movie) }
        api.seriesSearch(term)?.items.orEmpty().mapNotNull(OscarCatalogItem::fromJson)
            .forEach { item -> results[OscarItemId(OscarItemType.Series, item.id).asData()] = item.toSearchResponse(OscarItemType.Series) }
        return results.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = request.data.substringAfterLast('/').lowercase()
        val (path, query) = when (section) {
            "anime" -> "/api/anime/" to mapOf("featured" to "1")
            "movies" -> "/api/movies/" to mapOf("featured" to "1")
            "series" -> "/api/series/" to emptyMap()
            else -> return newHomePageResponse(request.name, emptyList(), false)
        }

        val pageData = api.listData(
            path,
            query + mapOf("page" to page.toString(), "limit" to "20"),
        )
        val responses = pageData?.items.orEmpty()
            .mapNotNull(OscarCatalogItem::fromJson)
            .map { item ->
                val type = when (section) {
                    "anime" -> OscarItemType.Anime
                    "movies" -> OscarItemType.Movie
                    else -> OscarItemType.Series
                }
                item.toSearchResponse(type)
            }
        val hasNext = pageData?.pagination?.let { page < it.pageCount() } ?: false
        return newHomePageResponse(request.name, responses, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val item = OscarItemId.parse(url) ?: return null
        return when (item.type) {
            OscarItemType.Movie -> loadMovie(item)
            OscarItemType.Series -> loadSeries(item)
            OscarItemType.Anime -> loadAnime(item)
            else -> null
        }
    }

    private fun OscarCatalogItem.toSearchResponse(type: OscarItemType): SearchResponse {
        val item = OscarItemId(type, id)
        return when (type) {
            OscarItemType.Anime -> newAnimeSearchResponse(
                name = title,
                url = item.asData(),
                type = if (isAnimeMovie) TvType.AnimeMovie else TvType.Anime,
                fix = false,
            ) {
                posterUrl = resolveOscarImage(poster)
                year = this@toSearchResponse.year
                score = this@toSearchResponse.rating?.let { Score.from10(it) }
                episodeCount?.takeIf { it > 0 }?.let { episodes[DubStatus.None] = it }
            }

            OscarItemType.Movie -> newMovieSearchResponse(
                name = title,
                url = item.asData(),
                type = TvType.Movie,
                fix = false,
            ) {
                posterUrl = resolveOscarImage(poster)
                year = this@toSearchResponse.year
                score = this@toSearchResponse.rating?.let { Score.from10(it) }
            }

            OscarItemType.Series -> newTvSeriesSearchResponse(
                name = title,
                url = item.asData(),
                type = TvType.TvSeries,
                fix = false,
            ) {
                posterUrl = resolveOscarImage(poster)
                year = this@toSearchResponse.year
                score = this@toSearchResponse.rating?.let { Score.from10(it) }
            }

            else -> error("Only catalog item types can be search responses")
        }
    }

    private suspend fun loadMovie(item: OscarItemId): LoadResponse? {
        val details = api.objectData("/api/movies/show.php", mapOf("id" to item.id.toString()))
            ?.let(OscarMediaDetails::fromJson) ?: return null
        return newMovieLoadResponse(details.title, item.asData(), TvType.Movie, item.asData()) {
            posterUrl = resolveOscarImage(details.poster)
            backgroundPosterUrl = resolveOscarImage(details.banner)
            year = details.year
            plot = details.story
            tags = details.genres.takeIf { it.isNotEmpty() }
            score = details.rating?.let { Score.from10(it) }
            duration = details.runtime
            contentRating = details.ageRating
        }
    }

    private suspend fun loadSeries(item: OscarItemId): LoadResponse? {
        val details = api.objectData("/api/series/show.php", mapOf("id" to item.id.toString()))
            ?.let(OscarMediaDetails::fromJson) ?: return null

        val seasons = api.listData("/api/seasons/", mapOf("series_id" to item.id.toString()))
            ?.items.orEmpty().mapNotNull(OscarSeason::fromJson)
            .ifEmpty { details.seasons }
            .sortedBy { it.seasonNumber }

        val episodes = seasons.flatMap { season ->
            api.seriesEpisodes(season.id).mapNotNull { episode ->
                episode.toCloudStreamEpisode(
                    type = OscarItemType.SeriesEpisode,
                    fallbackSeason = season.seasonNumber,
                    fallbackPoster = details.poster,
                )
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

        return newTvSeriesLoadResponse(details.title, item.asData(), TvType.TvSeries, episodes) {
            posterUrl = resolveOscarImage(details.poster)
            backgroundPosterUrl = resolveOscarImage(details.banner)
            year = details.year
            plot = details.story
            tags = details.genres.takeIf { it.isNotEmpty() }
            score = details.rating?.let { Score.from10(it) }
            showStatus = details.status.toShowStatus()
            contentRating = details.ageRating
            seasonNames = seasons.map { SeasonData(it.seasonNumber, it.title) }
        }
    }

    private suspend fun loadAnime(item: OscarItemId): LoadResponse? {
        val details = api.objectData("/api/anime/show.php", mapOf("id" to item.id.toString()))
            ?.let(OscarMediaDetails::fromJson) ?: return null
        val seasons = details.seasons.sortedBy { it.seasonNumber }

        // Only episode list pages are fetched here. Episode detail, including watch
        // links, is deliberately deferred to loadLinks for the selected episode.
        val episodes = seasons.flatMap { season ->
            api.animeEpisodes(season.id).mapNotNull { episode ->
                episode.toCloudStreamEpisode(
                    type = OscarItemType.AnimeEpisode,
                    fallbackSeason = season.seasonNumber,
                    fallbackPoster = details.poster,
                )
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

        return newAnimeLoadResponse(details.title, item.asData(), TvType.Anime) {
            posterUrl = resolveOscarImage(details.poster)
            backgroundPosterUrl = resolveOscarImage(details.banner)
            year = details.year
            plot = details.story
            tags = details.genres.takeIf { it.isNotEmpty() }
            score = details.rating?.let { Score.from10(it) }
            showStatus = details.status.toShowStatus()
            contentRating = details.ageRating
            seasonNames = seasons.map { SeasonData(it.seasonNumber, it.title) }
            this.episodes[DubStatus.None] = episodes
        }
    }

    private fun OscarEpisode.toCloudStreamEpisode(
        type: OscarItemType,
        fallbackSeason: Int,
        fallbackPoster: String?,
    ): Episode? {
        val season = seasonNumber ?: fallbackSeason
        return this@OscarTVPlugin.newEpisode(
            url = OscarItemId(type, id).asData(),
            initializer = {
            name = episodeName(episodeNumber, title)
            episode = episodeNumber
            this.season = season
            posterUrl = resolveOscarImage(thumbnail ?: poster ?: fallbackPoster)
            runTime = duration
            description = this@toCloudStreamEpisode.description
            addDate(airDate)
            },
            fix = false,
        )
    }

    private fun episodeName(number: Int?, title: String?): String {
        val cleanTitle = title?.trim().takeUnless { it.isNullOrBlank() }
        return when {
            number != null && cleanTitle != null -> "الحلقة $number - $cleanTitle"
            number != null -> "الحلقة $number"
            cleanTitle != null -> cleanTitle
            else -> "حلقة"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val item = OscarItemId.parse(data) ?: return false
        val links = when (item.type) {
            OscarItemType.Movie -> api.objectData("/api/movies/show.php", mapOf("id" to item.id.toString()))
                ?.watchLinks().orEmpty()

            OscarItemType.SeriesEpisode -> seriesEpisodeLinks(item.id)

            // Anime IDs and series-episode IDs are separate namespaces. Anime playback
            // must use the anime episode detail endpoint and never the generic endpoint.
            OscarItemType.AnimeEpisode -> api.objectData(
                "/api/anime/episodes/show.php",
                mapOf("id" to item.id.toString()),
            )?.watchLinks().orEmpty()

            else -> emptyList()
        }

        var found = false
        links.forEach { link ->
            if (emitWatchLink(link, subtitleCallback, callback)) found = true
        }
        return found
    }

    private suspend fun seriesEpisodeLinks(episodeId: Int): List<OscarWatchLink> {
        val embedded = api.objectData("/api/episodes/show.php", mapOf("id" to episodeId.toString()))
            ?.watchLinks().orEmpty()
        if (embedded.isNotEmpty()) return embedded

        return api.listData("/api/watch_links/", mapOf("episode_id" to episodeId.toString()))
            ?.items.orEmpty().mapNotNull(OscarWatchLink::fromJson)
    }

    private suspend fun emitWatchLink(
        link: OscarWatchLink,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = link.url?.trim().takeUnless { it.isNullOrBlank() } ?: return false
        val quality = parseOscarQuality(link.quality ?: url) ?: Qualities.Unknown.value
        val direct = link.type?.equals("direct", true) == true || isDirectMedia(url)

        return try {
            if (direct) {
                val type = if (url.substringBefore('?').endsWith(".m3u8", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
                callback(
                    newExtractorLink(
                        source = name,
                        name = link.serverName?.takeIf { it.isNotBlank() } ?: name,
                        url = url,
                        type = type,
                    ) {
                        referer = "$mainUrl/"
                        this.quality = quality
                    }
                )
                true
            } else {
                var emitted = false
                loadExtractor(url, "$mainUrl/", subtitleCallback) {
                    emitted = true
                    callback(it)
                }
                emitted
            }
        } catch (_: Exception) {
            // A bad server must not hide the remaining qualities/servers.
            false
        }
    }

    private fun isDirectMedia(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".m3u8") ||
            path.endsWith(".webm") || path.endsWith(".mkv")
    }

    private fun JSONObject.watchLinks(): List<OscarWatchLink> {
        return optJSONArray("watch_links")?.objects().orEmpty().mapNotNull(OscarWatchLink::fromJson)
    }
}

private fun String?.toShowStatus(): ShowStatus? = when (this?.lowercase()) {
    "completed", "released" -> ShowStatus.Completed
    "ongoing", "airing" -> ShowStatus.Ongoing
    else -> null
}
