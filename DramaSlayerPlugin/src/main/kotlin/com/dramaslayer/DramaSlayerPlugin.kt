package com.dramaslayer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class DramaSlayerPlugin : MainAPI() {
    override var mainUrl = DramaSlayerConfig.apiBase
    override var name = "Drama Slayer"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val api = DramaSlayerApi(baseUrl = { mainUrl })
    private val linkResolver = DramaSlayerLinkResolver(api, name)

    private val homePage = "dramaslayer://home"
    override val mainPage = mainPageOf(homePage to "جميع الأعمال")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val responses = try {
            api.getAll(page)
                .mapNotNull(::toSearchResponse)
                .distinctBy { it.url }
        } catch (error: Exception) {
            logDramaSlayer(
                "HOME_FAILED page=${page.coerceAtLeast(1)} " +
                    "exception=${error::class.java.simpleName}",
            )
            emptyList()
        }
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
        val details = api.details(dramaId) ?: return null
        val title = details.dramaName ?: details.alternativeTitles ?: "Drama Slayer"
        val poster = details.posterUrl
        val tags = details.genres?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val episodes = api.episodes(dramaId)
            .filter { !it.episodeId.isNullOrBlank() }
            .sortedWith(compareBy({ it.episodeNumber?.toIntOrNull() ?: Int.MAX_VALUE }, { it.episodeId }))

        if (details.dramaType.isMovieType()) {
            val movieData = episodes.singleOrNull()?.episodeId?.let { EpisodeData(dramaId, it).asData() }
                ?: dramaUrl(dramaId)
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

    private fun parseDramaId(url: String): String? {
        if (!url.startsWith("dramaslayer://drama/", true)) return null
        return url.substringAfterLast('/').trim().takeIf {
            it.isNotBlank() && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' }
        }
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
