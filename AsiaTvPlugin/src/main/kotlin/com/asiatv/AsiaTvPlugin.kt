package com.asiatv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class AsiaTvPlugin : MainAPI() {
    override var mainUrl = "https://as1tv.com"
    override var name = "AsiaTv"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        TvType.TvSeries
    )

    // ==============================
    // الصفحة الرئيسية
    // ==============================
    override val mainPage = mainPageOf(
        "$mainUrl/types/%d8%a7%d9%84%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d8%a7%d9%84%d9%83%d9%88%d8%b1%d9%8a%d8%a9/" to "الدراما الكورية",
        "$mainUrl/types/%d8%a7%d9%84%d8%b1%d8%a7%d9%85%d8%a7-%d8%a7%d9%84%d8%b5%d9%8a%d9%86%d9%8a%d8%a9/" to "الدراما الصينية",
        "$mainUrl/types/%d8%a7%d9%84%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d8%a7%d9%84%d8%aa%d8%a7%d9%8a%d9%84%d9%86%d8%af%d9%8a%d8%a9/" to "الدراما التايلاندية",
        "$mainUrl/types/%d8%a7%d9%84%d8%af%d8%b1%d8%a7%d9%85%d8%a7-%d8%a7%d9%84%d9%8a%d8%a7%d8%a8%d8%a7%d9%86%d9%8a%d8%a9/" to "الدراما اليابانية",
        "$mainUrl/types/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        val doc = app.get(url).document

        val items = doc.select(
            "article.post, .post-item, .MovieBlock"
        ).mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null

            val href = a.attr("href")

            val title = it.selectFirst(
                "h2, h3, .Title, .BlockTitle"
            )?.text()?.trim()
                ?: a.attr("title").trim()

            val img = it.selectFirst("img")?.attr("src")
                ?: it.selectFirst("img")?.attr("data-src")
                ?: ""

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.AsianDrama
            ) {
                this.posterUrl = img
            }
        }

        return newHomePageResponse(
            request.name,
            items
        )
    }

    // ==============================
    // البحث
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select(
            "article.post, .post-item, .MovieBlock"
        ).mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null

            val href = a.attr("href")

            val title = it.selectFirst(
                "h2, h3, .Title, .BlockTitle"
            )?.text()?.trim()
                ?: a.attr("title").trim()

            val img = it.selectFirst("img")?.attr("src")
                ?: it.selectFirst("img")?.attr("data-src")
                ?: ""

            val isMovie = href.contains("film") ||
                title.contains("فيلم")

            if (isMovie) {
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    this.posterUrl = img
                }
            } else {
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.AsianDrama
                ) {
                    this.posterUrl = img
                }
            }
        }
    }

    // ==============================
    // تحميل بيانات المسلسل/الفيلم
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst(
            "h1.entry-title, h1, .Title"
        )?.text()?.trim() ?: return null

        val poster = doc.selectFirst(
            ".post-thumbnail img, .entry-content img, article img"
        )?.let {
            it.attr("src").ifBlank {
                it.attr("data-src")
            }
        } ?: ""

        val description = doc.selectFirst(
            ".entry-content p, p.anime-story, .Description"
        )?.text()?.trim() ?: ""

        val genres = doc.select(
            ".genre a, ul.anime-genres li a, a[rel='category tag']"
        ).map {
            it.text()
        }

        val isMovie = url.contains("film") ||
            title.contains("فيلم")

        // قائمة الحلقات
        val episodes = doc.select(
            ".seasons-episodes a, ul.episodesList li a, .EpisodesList a"
        ).mapNotNull { ep ->

            val epUrl = ep.attr("href")
            val epName = ep.text().trim()

            val epNum = Regex("\\d+")
                .find(epName)
                ?.value
                ?.toIntOrNull()
                ?: 1

            newEpisode(epUrl) {
                name = epName
                episode = epNum
            }
        }

        return if (isMovie || episodes.isEmpty()) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        } else {
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.AsianDrama,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }
    }

    // ==============================
    // استخراج روابط الفيديو
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        val html = doc.html()

        // 1. محاولة استخراج iframe مباشر
        val iframes = doc.select("iframe[src]")
            .map {
                it.attr("src")
            }

        for (iframeUrl in iframes) {
            if (iframeUrl.isNotBlank()) {
                loadExtractor(
                    iframeUrl,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        // 2. محاولة استخراج من data-link أو data-src
        doc.select(
            "[data-link], [data-src], [data-url]"
        ).forEach {

            val link = it.attr("data-link")
                .ifBlank {
                    it.attr("data-src")
                }
                .ifBlank {
                    it.attr("data-url")
                }

            if (link.isNotBlank()) {
                loadExtractor(
                    link,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        // 3. فك تشفير Packer (eval) — الطريقة الأساسية
        val packerRegex = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),\d+,'(.+?)'""",
            RegexOption.DOT_MATCHES_ALL
        )

        packerRegex.find(html)?.let { match ->

            val unpacked = unpackPacker(
                match.groupValues[1],
                match.groupValues[2].toInt(),
                match.groupValues[3].split("|")
            )

            // استخراج M3U8 من النتيجة
            val m3u8Regex = Regex(
                """https?://[^\s"']+\.m3u8[^\s"']*"""
            )

            m3u8Regex.findAll(unpacked).forEach { m3u ->

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m3u.value,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            // استخراج MP4
            val mp4Regex = Regex(
                """https?://[^\s"']+\.mp4[^\s"']*"""
            )

            mp4Regex.findAll(unpacked).forEach { mp4 ->

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name MP4",
                        url = mp4.value,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // 4. بحث مباشر في HTML عن M3U8
        val directM3u8 = Regex(
            """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""
        ).findAll(html)

        directM3u8.forEach {

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Direct",
                    url = it.value,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }

    // ==============================
    // فك تشفير Packer
    // ==============================
    private fun unpackPacker(
        payload: String,
        radix: Int,
        keys: List<String>
    ): String {

        var result = payload

        keys.forEachIndexed { index, key ->

            if (key.isNotEmpty()) {

                val base36 = index.toString(radix)

                result = result.replace(
                    Regex("\\b$base36\\b"),
                    key
                )
            }
        }

        return result
    }
}
