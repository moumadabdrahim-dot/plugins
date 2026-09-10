package com.oscartv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
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

    // One provider page builds the three ordered HomePageList sections below.
    private val homePage = "oscar://home"
    override val mainPage = mainPageOf(homePage to "OscarTV")

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()

        val results = linkedMapOf<String, SearchResponse>()
        api.animeSearch(term)?.items.orEmpty().mapNotNull(OscarCatalogItem::fromJson)
            .forEach { item ->
                val data = OscarItemId(OscarItemType.Anime, item.id).asData()
                results[data] = item.toSearchResponse(OscarItemType.Anime)
            }
        api.movieSearch(term)?.items.orEmpty().mapNotNull(OscarCatalogItem::fromJson)
            .forEach { item ->
                val data = OscarItemId(OscarItemType.Movie, item.id).asData()
                results[data] = item.toSearchResponse(OscarItemType.Movie)
            }
        api.seriesSearch(term)?.items.orEmpty().mapNotNull(OscarCatalogItem::fromJson)
            .forEach { item ->
                val data = OscarItemId(OscarItemType.Series, item.id).asData()
                results[data] = item.toSearchResponse(OscarItemType.Series)
            }
        return results.values.toList()
    }

    private data class OscarHomeSection(
        val title: String,
        val path: String,
        val type: OscarItemType,
    )

    private val homeSections = listOf(
        OscarHomeSection("أحدث الأفلام", "/api/movies/", OscarItemType.Movie),
        OscarHomeSection("أحدث المسلسلات", "/api/series/", OscarItemType.Series),
        OscarHomeSection("أحدث الأنمي", "/api/anime/", OscarItemType.Anime),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestedPage = page.coerceAtLeast(1)
        val homePageLists = supervisorScope {
            homeSections.map { section ->
                async {
                    try {
                        fetchHomeSection(section, requestedPage)
                    } catch (error: Exception) {
                        logOscar(
                            "LOAD_MAPPING_FAILED section=${section.type.key} " +
                                "exception=${error::class.java.simpleName} message=${error.safeLogMessageForLog()}",
                        )
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        // awaitAll returns results in input order, so network completion cannot reorder sections.
        return newHomePageResponse(homePageLists)
    }

    private suspend fun fetchHomeSection(
        section: OscarHomeSection,
        page: Int,
    ): HomePageList? {
        val pageData = api.listData(
            section.path,
            mapOf("page" to page.toString(), "limit" to "20"),
        )
        val responses = pageData?.items.orEmpty().mapNotNull { json ->
            try {
                OscarCatalogItem.fromJson(json)?.toSearchResponse(section.type)
            } catch (error: Exception) {
                logOscar(
                    "LOAD_MAPPING_FAILED section=${section.type.key} reason=item_mapping " +
                        "exception=${error::class.java.simpleName} message=${error.safeLogMessageForLog()}",
                )
                null
            }
        }
        if (responses.isEmpty()) return null
        return HomePageList(section.title, responses)
    }

    override suspend fun load(url: String): LoadResponse? {
        val item = OscarItemData.parse(url)?.toItemId()
        if (item == null) {
            logOscar("LOAD_MAPPING_FAILED reason=invalid_item_data value=${url.safeLogValue()}")
            return null
        }

        return try {
            when (item.type) {
                OscarItemType.Movie -> loadMovie(item)
                OscarItemType.Series -> loadSeries(item)
                OscarItemType.Anime -> loadAnime(item)
                else -> {
                    logOscar("LOAD_MAPPING_FAILED type=${item.type.key} id=${item.id} reason=not_loadable")
                    null
                }
            }
        } catch (error: Exception) {
            logOscar(
                "LOAD_MAPPING_FAILED type=${item.type.key} id=${item.id} " +
                    "exception=${error::class.java.simpleName} message=${error.safeLogMessageForLog()}",
            )
            null
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
        val rawDetails = api.objectData("/api/movies/show.php", mapOf("id" to item.id.toString()))
        if (rawDetails == null) {
            logOscar("DETAIL_FAILED movie id=${item.id}")
            return null
        }
        val details = OscarMediaDetails.fromJson(rawDetails)
        if (details == null) {
            logOscar("DETAIL_FAILED movie id=${item.id} reason=data_mapping")
            return null
        }

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
        val rawDetails = api.objectData("/api/series/show.php", mapOf("id" to item.id.toString()))
        if (rawDetails == null) {
            logOscar("DETAIL_FAILED series id=${item.id}")
            return null
        }
        val details = OscarMediaDetails.fromJson(rawDetails)
        if (details == null) {
            logOscar("DETAIL_FAILED series id=${item.id} reason=data_mapping")
            return null
        }

        val seasonsFromEndpoint = api.listData(
            "/api/seasons/",
            mapOf("series_id" to item.id.toString()),
        )?.items.orEmpty().mapNotNull(OscarSeason::fromJson)
        val seasons = (seasonsFromEndpoint.ifEmpty { details.seasons })
            .sortedBy { it.seasonNumber }
        if (seasons.isEmpty()) {
            logOscar("LOAD_MAPPING_FAILED type=series id=${item.id} reason=no_seasons")
        }

        val episodes = api.seriesEpisodesForSeasons(seasons.map { it.id })
            .mapNotNull { episode ->
                episode.toCloudStreamEpisode(
                    type = OscarItemType.SeriesEpisode,
                    fallbackSeason = seasons.firstOrNull { it.id == episode.seasonId }?.seasonNumber
                        ?: episode.seasonNumber ?: 1,
                    fallbackPoster = details.poster,
                )
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE })

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
        val rawDetails = api.objectData("/api/anime/show.php", mapOf("id" to item.id.toString()))
        if (rawDetails == null) {
            logOscar("DETAIL_FAILED anime id=${item.id}")
            return null
        }
        val details = OscarMediaDetails.fromJson(rawDetails)
        if (details == null) {
            logOscar("DETAIL_FAILED anime id=${item.id} reason=data_mapping")
            return null
        }

        val seasons = details.seasons.sortedBy { it.seasonNumber }
        if (seasons.isEmpty()) {
            logOscar("LOAD_MAPPING_FAILED type=anime id=${item.id} reason=no_seasons")
        }

        val episodes = api.animeEpisodesForSeasons(seasons.map { it.id })
            .mapNotNull { episode ->
                episode.toCloudStreamEpisode(
                    type = OscarItemType.AnimeEpisode,
                    fallbackSeason = seasons.firstOrNull { it.id == episode.seasonId }?.seasonNumber
                        ?: episode.seasonNumber ?: 1,
                    fallbackPoster = details.poster,
                )
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE })

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
    ): Episode {
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
        val item = OscarItemData.parse(data)?.toItemId()
        if (item == null) {
            logOscar("LOAD_MAPPING_FAILED stage=loadLinks reason=invalid_item_data value=${data.safeLogValue()}")
            return false
        }

        val links = when (item.type) {
            OscarItemType.Movie -> api.objectData(
                "/api/movies/show.php",
                mapOf("id" to item.id.toString()),
            )?.watchLinks().orEmpty()

            OscarItemType.SeriesEpisode -> seriesEpisodeLinks(item.id)

            // Anime IDs and series-episode IDs are separate namespaces. Anime playback
            // must use the anime episode detail endpoint and never the generic endpoint.
            OscarItemType.AnimeEpisode -> api.objectData(
                "/api/anime/episodes/show.php",
                mapOf("id" to item.id.toString()),
            )?.watchLinks().orEmpty()

            else -> emptyList()
        }

        val emittedKeys = mutableSetOf<String>()
        fun emitUnique(link: ExtractorLink): Boolean {
            val key = oscarMediaDedupKey(link.url, link.quality)
            if (!emittedKeys.add(key)) return false

            val host = link.url.toOscarHost().orEmpty().ifBlank { "<unknown>" }
            val userAgent = link.headers.entries
                .firstOrNull { (key) -> key.equals("User-Agent", ignoreCase = true) }
                ?.value
                .orEmpty()
            logOscar("FINAL_MEDIA_FOUND host=$host type=${link.type.name}")
            logOscar(
                "MEDIA_LINK_EMIT host=$host quality=${link.quality} type=${link.type.name} " +
                    "ua=${userAgent.safeLogValue()} referer=${!link.referer.isNullOrBlank()}",
            )
            callback(link)
            return true
        }

        var found = false
        links.forEach { link ->
            if (emitWatchLink(link, subtitleCallback, ::emitUnique)) found = true
        }
        if (!found) {
            logOscar("LOAD_MAPPING_FAILED stage=loadLinks type=${item.type.key} id=${item.id} reason=no_usable_links")
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
        emit: (ExtractorLink) -> Boolean,
    ): Boolean {
        val url = link.url?.trim().takeUnless { it.isNullOrBlank() } ?: return false
        val quality = parseOscarQuality(link.quality ?: url) ?: Qualities.Unknown.value

        return try {
            when (link.playbackKind()) {
                OscarPlaybackKind.WRAPPER -> {
                    OscarSeriesMp4Resolver.resolve(link, subtitleCallback, emit)
                }

                OscarPlaybackKind.DIRECT -> {
                    val mediaType = url.toOscarMediaType()
                    val extractorType = when (mediaType) {
                        OscarMediaType.M3U8 -> ExtractorLinkType.M3U8
                        OscarMediaType.VIDEO -> ExtractorLinkType.VIDEO
                    }
                    val headers = link.mediaHeaders()
                    emit(
                        newExtractorLink(
                            source = name,
                            name = link.serverName?.takeIf { it.isNotBlank() } ?: name,
                            url = url,
                            type = extractorType,
                        ) {
                            this.headers = headers
                            this.quality = quality
                        },
                    )
                }

                OscarPlaybackKind.EXTRACTOR -> {
                    var emitted = false
                    loadExtractor(url, "$mainUrl/", subtitleCallback) {
                        emitted = emit(it) || emitted
                    }
                    emitted
                }
            }
        } catch (error: Exception) {
            logOscar(
                "LOAD_MAPPING_FAILED stage=loadLinks server=${link.serverName.safeLogValue()} " +
                    "exception=${error::class.java.simpleName} message=${error.safeLogMessageForLog()}",
            )
            false
        }
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

internal fun Throwable.safeLogMessageForLog(): String {
    return message.orEmpty().replace(Regex("\\s+"), " ").take(200)
}

internal fun String?.safeLogValue(): String {
    return this.orEmpty().replace(Regex("\\s+"), " ").take(160)
}
