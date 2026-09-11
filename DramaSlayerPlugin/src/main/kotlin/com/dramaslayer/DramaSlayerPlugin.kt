package com.dramaslayer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

internal data class DramaHomeSection(
    val title: String,
    val listType: String,
)

internal val dramaHomeSections = listOf(
    DramaHomeSection("أحدث المسلسلات", "latest_series"),
    DramaHomeSection("أحدث الأفلام", "latest_movie"),
)

private fun dramaHomePath(listType: String): String = "dramaslayer://home/$listType"

class DramaSlayerPlugin private constructor(
    private val apiOverride: DramaSlayerApi?,
    @Suppress("UNUSED_PARAMETER") private val injected: Unit,
) : MainAPI() {
    constructor() : this(null, Unit)

    internal constructor(api: DramaSlayerApi) : this(api, Unit)

    override var mainUrl = DramaSlayerConfig.apiBase
    override var name = "Drama Slayer"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val api = apiOverride ?: DramaSlayerApi(baseUrl = { mainUrl })
    private val linkResolver = DramaSlayerLinkResolver(api, name)

    override val mainPage = mainPageOf(
        *dramaHomeSections.map { dramaHomePath(it.listType) to it.title }.toTypedArray(),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = dramaHomeSections.firstOrNull { dramaHomePath(it.listType) == request.data }
            ?: return newHomePageResponse(request.name, emptyList())
        val responses = try {
            api.getPublishedDrama(section.listType, page)
                .mapNotNull(::toSearchResponse)
                .distinctBy { it.url }
        } catch (error: Exception) {
            logDramaSlayer(
                "HOME_REQUEST_FAILED list_type=${section.listType} page=${page.coerceAtLeast(1)} " +
                    "exception=${error::class.java.simpleName}",
            )
            emptyList()
        }
        logDramaSlayer(
            "HOME_MAPPING_OK list_type=${section.listType} page=${page.coerceAtLeast(1)} " +
                "count=${responses.size}",
        )
        return newHomePageResponse(request.name, responses)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        return api.search(term).mapNotNull(::toSearchResponse).distinctBy { it.url }
    }

    internal fun toSearchResponse(item: DramaItem): SearchResponse? {
        val id = item.dramaId ?: return null
        val title = item.dramaName ?: item.alternativeTitles ?: return null
        val type = item.dramaType.toTvType()
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, dramaUrl(id), TvType.Movie, fix = false) {
                posterUrl = item.posterUrl
                year = item.releaseDate?.toIntOrNull()
            }
        } else {
            newTvSeriesSearchResponse(title, dramaUrl(id), TvType.TvSeries, fix = false) {
                posterUrl = item.posterUrl
                year = item.releaseDate?.toIntOrNull()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val dramaId = parseDramaId(url) ?: return null
        logDramaSlayer("LOAD_START drama=$dramaId")
        val details = runCatching { api.details(dramaId) }.getOrElse {
            logDramaSlayer("LOAD_DETAILS_FAILED drama=$dramaId exception=${it::class.java.simpleName}")
            return null
        } ?: run {
            logDramaSlayer("LOAD_DETAILS_FAILED drama=$dramaId reason=empty")
            return null
        }
        val title = details.dramaName ?: details.alternativeTitles ?: "Drama Slayer"
        val poster = details.posterUrl
        val tags = details.genres?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val episodes = runCatching { api.episodes(dramaId) }.getOrElse {
            logDramaSlayer("LOAD_EPISODES_FAILED drama=$dramaId exception=${it::class.java.simpleName}")
            return null
        }
            .filter { !it.episodeId.isNullOrBlank() }
            .sortedWith(compareBy({ it.episodeNumber?.toIntOrNull() ?: Int.MAX_VALUE }, { it.episodeId }))

        if (details.dramaType.isMovieType()) {
            val movieEpisodeId = episodes.firstNotNullOfOrNull { episode ->
                episode.episodeId?.takeIf { it.isNotBlank() }
            } ?: run {
                logDramaSlayer("LOAD_MAPPING_FAILED drama=$dramaId type=Movie reason=no_movie_episode")
                return null
            }
            val movieData = EpisodeData(dramaId, movieEpisodeId).asData()
            logDramaSlayer("LOAD_MAPPING_OK drama=$dramaId type=Movie episode=$movieEpisodeId")
            return newMovieLoadResponse(title, dramaUrl(dramaId), TvType.Movie, movieData) {
                posterUrl = poster
                year = details.releaseDate?.toIntOrNull()
                plot = details.description
                this.tags = tags
            }
        }

        val cloudStreamEpisodes = episodes.map { item ->
            newEpisode(
                url = EpisodeData(dramaId, item.episodeId!!).asData(),
                initializer = {
                    name = item.episodeName?.takeIf { it.isNotBlank() }
                        ?: "الحلقة ${item.episodeNumber.orEmpty()}"
                    episode = item.episodeNumber?.toIntOrNull()
                    season = 1
                    posterUrl = poster
                },
                fix = false,
            )
        }
        logDramaSlayer("LOAD_MAPPING_OK drama=$dramaId type=TvSeries episodes=${cloudStreamEpisodes.size}")
        return newTvSeriesLoadResponse(title, dramaUrl(dramaId), TvType.TvSeries, cloudStreamEpisodes) {
            posterUrl = poster
            year = details.releaseDate?.toIntOrNull()
            plot = details.description
            this.tags = tags
            showStatus = details.dramaStatus.toShowStatus()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeData = EpisodeData.parse(data) ?: return false
        val detail = api.episode(episodeData.dramaId, episodeData.episodeId) ?: run {
            logDramaSlayer("EPISODE_DETAIL_FAILED drama=${episodeData.dramaId} episode=${episodeData.episodeId}")
            return false
        }
        return linkResolver.resolve(detail.episodeUrls, subtitleCallback, callback)
    }

    private fun dramaUrl(id: String): String = "dramaslayer://drama/${id.trim()}"
}

internal fun parseDramaId(url: String): String? {
    if (!url.startsWith("dramaslayer://drama/", true)) return null
    return url.substringAfterLast('/').trim().takeIf {
        it.isNotBlank() && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' }
    }
}

private fun String?.toTvType(): TvType = if (isMovieType()) TvType.Movie else TvType.TvSeries

private fun String?.isMovieType(): Boolean {
    val value = this?.trim().orEmpty()
    return value.equals("movie", true) || value.equals("film", true) || value.contains("فيلم")
}

private fun String?.toShowStatus(): ShowStatus? {
    return when (this?.trim()?.lowercase()) {
        "completed", "complete", "released", "منتهي", "مكتمل" -> ShowStatus.Completed
        "ongoing", "airing", "in progress", "مستمر" -> ShowStatus.Ongoing
        else -> null
    }
}
