package com.asiatv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

class AsiaTvPlugin : MainAPI() {

    override var mainUrl = "https://as1tv.com"
    override var name = "AsiaTv"

    override val hasMainPage = true
    override val hasDownloadSupport = true

    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    private val headers
        get() = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
        )

    override val mainPage = mainPageOf(
        "$mainUrl/types/%D8%A7%D9%84%D8%AF%D8%B1%D8%A7%D9%85%D8%A7-%D8%A7%D9%84%D9%83%D9%88%D8%B1%D9%8A%D8%A9/" to
            "الدراما الكورية",

        "$mainUrl/types/%D8%A7%D9%84%D8%AF%D8%B1%D8%A7%D9%85%D8%A7-%D8%A7%D9%84%D8%B5%D9%8A%D9%86%D9%8A%D8%A9/" to
            "الدراما الصينية",

        "$mainUrl/types/%D8%A7%D9%84%D8%AF%D8%B1%D8%A7%D9%85%D8%A7-%D8%A7%D9%84%D8%AA%D8%A7%D9%8A%D9%84%D8%A7%D9%86%D8%AF%D9%8A%D8%A9/" to
            "الدراما التايلاندية",

        "$mainUrl/types/%D8%A7%D9%84%D8%AF%D8%B1%D8%A7%D9%85%D8%A7-%D8%A7%D9%84%D9%8A%D8%A7%D8%A8%D8%A7%D9%86%D9%8A%D8%A9/" to
            "الدراما اليابانية",

        "$mainUrl/types/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D8%B3%D9%8A%D9%88%D9%8A%D8%A9/" to
            "الأفلام الآسيوية"
    )

    // =========================================================
    // URL
    // =========================================================

    private fun absoluteUrl(
        raw: String?,
        base: String = mainUrl
    ): String? {

        val value = raw
            ?.trim()
            ?.replace("&amp;", "&")
            ?.trim('"', '\'')
            ?: return null

        if (value.isBlank()) return null

        if (
            value.startsWith("javascript:", true) ||
            value.startsWith("#") ||
            value.startsWith("data:", true)
        ) {
            return null
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        if (
            value.startsWith("https://", true) ||
            value.startsWith("http://", true)
        ) {
            return value
        }

        return try {
            URI(base).resolve(value).toString()
        } catch (_: Exception) {
            try {
                fixUrl(value)
            } catch (_: Exception) {
                null
            }
        }
    }

    // =========================================================
    // Posters
    // =========================================================

    private fun isBadPoster(url: String): Boolean {

        val lower = url.lowercase()

        return listOf(
            "placeholder",
            "no-image",
            "noimage",
            "default-image",
            "default_image",
            "logo",
            "asiadrama-270x270"
        ).any {
            lower.contains(it)
        }
    }

    private fun posterFromImage(
        img: Element?,
        base: String = mainUrl
    ): String? {

        if (img == null) return null

        val candidates = mutableListOf<String>()

        listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-image",
            "data-cfsrc"
        ).forEach { attr ->

            img.attr(attr)
                .takeIf { it.isNotBlank() }
                ?.let { candidates.add(it) }
        }

        img.attr("data-srcset")
            .split(",")
            .map { it.trim().substringBefore(" ") }
            .filter { it.isNotBlank() }
            .reversed()
            .forEach {
                candidates.add(it)
            }

        img.attr("srcset")
            .split(",")
            .map { it.trim().substringBefore(" ") }
            .filter { it.isNotBlank() }
            .reversed()
            .forEach {
                candidates.add(it)
            }

        img.attr("src")
            .takeIf { it.isNotBlank() }
            ?.let {
                candidates.add(it)
            }

        val resolved = candidates
            .mapNotNull {
                absoluteUrl(it, base)
            }
            .distinct()

        return resolved.firstOrNull {
            !isBadPoster(it)
        } ?: resolved.firstOrNull()
    }

    private fun posterFromCard(
        element: Element
    ): String? {

        val selectors = listOf(
            "div.image img",
            "div.postmovie-photo img",
            ".image img",
            ".poster img",
            "img"
        )

        selectors.forEach { selector ->

            posterFromImage(
                element.selectFirst(selector)
            )?.let {
                if (!isBadPoster(it)) {
                    return it
                }
            }
        }

        return selectors.firstNotNullOfOrNull { selector ->
            posterFromImage(
                element.selectFirst(selector)
            )
        }
    }

    private fun posterFromPage(
        document: Document,
        pageUrl: String
    ): String? {

        val meta = listOf(
            document.selectFirst(
                "meta[property='og:image']"
            )?.attr("content"),

            document.selectFirst(
                "meta[name='twitter:image']"
            )?.attr("content")
        )
            .mapNotNull {
                absoluteUrl(it, pageUrl)
            }

        meta.firstOrNull {
            !isBadPoster(it)
        }?.let {
            return it
        }

        val selectors = listOf(
            "div.single-thumb-bg > img",
            ".single-thumb-bg img",
            ".single-thumb img",
            ".post-thumbnail img",
            ".poster img"
        )

        selectors.forEach { selector ->

            posterFromImage(
                document.selectFirst(selector),
                pageUrl
            )?.let {
                return it
            }
        }

        return meta.firstOrNull()
    }

    // =========================================================
    // Cards
    // =========================================================

    private fun cardToSearchResponse(
        element: Element
    ): SearchResponse? {

        val anchor =
            element.selectFirst(
                "div.postmovie-photo a[href]"
            )
                ?: element.selectFirst(
                    "a[href*='/drama/']"
                )
                ?: return null

        val url =
            absoluteUrl(
                anchor.attr("href")
            )
                ?: return null

        var title =
            anchor.attr("title")
                .trim()

        if (title.isBlank()) {

            title =
                element.selectFirst(
                    ".title, .Title, h2, h3"
                )
                    ?.text()
                    ?.trim()
                    .orEmpty()
        }

        if (title.isBlank()) {

            title =
                anchor.selectFirst("img")
                    ?.attr("alt")
                    ?.trim()
                    .orEmpty()
        }

        if (title.isBlank()) {
            return null
        }

        val poster =
            posterFromCard(element)

        val fullText =
            element.text()

        val isMovie =
            title.contains("فيلم") ||
            fullText.contains("عدد فيلم") ||
            fullText.contains("افلام اسيوية") ||
            fullText.contains("أفلام اسيوية") ||
            fullText.contains("أفلام آسيوية")

        return if (isMovie) {

            newMovieSearchResponse(
                title,
                url,
                TvType.Movie
            ) {
                posterUrl = poster
            }

        } else {

            newTvSeriesSearchResponse(
                title,
                url,
                TvType.AsianDrama
            ) {
                posterUrl = poster
            }
        }
    }

    private fun parseCards(
        document: Document
    ): List<SearchResponse> {

        val results =
            linkedMapOf<String, SearchResponse>()

        var elements =
            document.select("div.box-item")

        if (elements.isEmpty()) {

            elements =
                document.select(
                    ".box-item, article.post, " +
                    ".post-item, .item-post"
                )
        }

        elements.forEach { element ->

            cardToSearchResponse(element)
                ?.let {
                    results[it.url] = it
                }
        }

        return results.values.toList()
    }

    // =========================================================
    // Main page
    // =========================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val base =
            request.data.trimEnd('/')

        val url =
            if (page <= 1) {
                "$base/"
            } else {
                "$base/page/$page/"
            }

        val document =
            app.get(
                url,
                headers = headers
            ).document

        return newHomePageResponse(
            request.name,
            parseCards(document)
        )
    }

    // =========================================================
    // Search
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encoded =
            URLEncoder.encode(
                query,
                "UTF-8"
            )

        val document =
            app.get(
                "$mainUrl/?s=$encoded",
                headers = headers
            ).document

        return parseCards(document)
    }

    // =========================================================
    // Details
    // =========================================================

    private fun pageTitle(
        document: Document
    ): String? {

        return document
            .selectFirst("h1 span.title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

            ?: document
                .selectFirst("h1.entry-title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            ?: document
                .selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            ?: document
                .selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    private fun plot(
        document: Document
    ): String? {

        return document
            .selectFirst("div.getcontent p")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

            ?: document
                .selectFirst(".getcontent")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            ?: document
                .selectFirst(
                    "meta[property='og:description']"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    private fun tags(
        document: Document
    ): List<String> {

        return document
            .select(
                "div.box-tags a, " +
                "a[href*='/genre/']"
            )
            .map {
                it.text().trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()
    }

    private fun episodeNumber(
        text: String
    ): Int? {

        Regex(
            """الحلقة\s*(?:رقم)?\s*[:：]?\s*(\d+)"""
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        Regex(
            """ح\s*(\d+)"""
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        return null
    }

    private fun episodeElements(
        document: Document
    ): List<Element> {

        val exact =
            document.select(
                "div.loop-episode a[href]"
            )

        if (exact.isNotEmpty()) {
            return exact
        }

        return document.select(
            "a[href*='/episodes/']"
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val response =
            app.get(
                url,
                headers = headers
            )

        val document =
            response.document

        val finalUrl =
            response.url

        val title =
            pageTitle(document)
                ?: return null

        val poster =
            posterFromPage(
                document,
                finalUrl
            )

        val description =
            plot(document)

        val tags =
            tags(document)

        val episodeLinks =
            episodeElements(document)

        if (episodeLinks.isEmpty()) {

            return newMovieLoadResponse(
                title,
                finalUrl,
                TvType.Movie,
                finalUrl
            ) {

                posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }

        val unique =
            linkedMapOf<String, Episode>()

        episodeLinks.forEach { element ->

            val href =
                absoluteUrl(
                    element.attr("href"),
                    finalUrl
                )
                    ?: return@forEach

            if (
                !href.contains("/episodes/")
            ) {
                return@forEach
            }

            val name =
                element.selectFirst(
                    "div.titlepisode"
                )
                    ?.text()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: element.text()
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                    ?: "حلقة"

            val number =
                episodeNumber(
                    "$name $href"
                )

            unique[href] =
                newEpisode(href) {

                    this.name =
                        if (number != null) {
                            "الحلقة $number"
                        } else {
                            name
                        }

                    episode =
                        number

                    season = 1

                    posterUrl =
                        poster
                }
        }

        val episodes =
            unique.values
                .toList()
                .reversed()

        if (episodes.isEmpty()) {

            return newMovieLoadResponse(
                title,
                finalUrl,
                TvType.Movie,
                finalUrl
            ) {
                posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }

        return newTvSeriesLoadResponse(
            title,
            finalUrl,
            TvType.AsianDrama,
            episodes
        ) {

            posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // =========================================================
    // Playback
    // =========================================================

    /**
     * هذه أهم نقطة في قالب AsiaTV.
     *
     * Episode page
     *   ↓
     * div.loop-episode a.current
     *   ↓
     * /watching/
     */
    private fun getWatchingUrl(
        document: Document,
        data: String
    ): String? {

        document.selectFirst(
            "div.loop-episode a.current[href]"
        )
            ?.attr("href")
            ?.let {
                absoluteUrl(it, data)
            }
            ?.let {
                return it
            }

        document.selectFirst(
            "a.watch_player[href]"
        )
            ?.attr("href")
            ?.let {
                absoluteUrl(it, data)
            }
            ?.let {
                return it
            }

        document.selectFirst(
            "a[href*='/watching/']"
        )
            ?.attr("href")
            ?.let {
                absoluteUrl(it, data)
            }
            ?.let {
                return it
            }

        return null
    }

    private fun serverUrls(
        document: Document,
        pageUrl: String
    ): List<String> {

        val urls =
            linkedSetOf<String>()

        document.select(
            "ul.server-list-menu li[data-server]"
        ).forEach { element ->

            absoluteUrl(
                element.attr("data-server"),
                pageUrl
            )?.let {
                urls.add(it)
            }
        }

        /*
         * fallback إذا تغير container فقط.
         */
        if (urls.isEmpty()) {

            document.select(
                "[data-server]"
            ).forEach { element ->

                absoluteUrl(
                    element.attr("data-server"),
                    pageUrl
                )?.let {
                    urls.add(it)
                }
            }
        }

        /*
         * iframe fallback
         */
        if (urls.isEmpty()) {

            document.select(
                "iframe[src], iframe[data-src]"
            ).forEach { iframe ->

                listOf(
                    iframe.attr("src"),
                    iframe.attr("data-src")
                )
                    .firstOrNull {
                        it.isNotBlank()
                    }
                    ?.let {
                        absoluteUrl(
                            it,
                            pageUrl
                        )
                    }
                    ?.let {
                        urls.add(it)
                    }
            }
        }

        return urls.toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val found =
            AtomicBoolean(false)

        /*
         * 1. احصل على صفحة watching.
         */
        val watchingUrl =
            if (
                data.contains(
                    "/watching/"
                )
            ) {

                data

            } else {

                val episodePage =
                    app.get(
                        data,
                        headers = headers,
                        referer = mainUrl
                    )

                getWatchingUrl(
                    episodePage.document,
                    episodePage.url
                )
                    ?: return false
            }

        /*
         * 2. افتح صفحة السيرفرات.
         */
        val watchingResponse =
            app.get(
                watchingUrl,
                headers = headers,
                referer = data
            )

        val watchingDocument =
            watchingResponse.document

        val actualWatchingUrl =
            watchingResponse.url

        /*
         * 3. استخرج data-server.
         */
        val servers =
            serverUrls(
                watchingDocument,
                actualWatchingUrl
            )

        /*
         * 4. CloudStream extractors بالتوازي.
         */
        servers
            .distinct()
            .take(15)
            .amap { server ->

                try {

                    loadExtractor(
                        server,
                        mainUrl,
                        subtitleCallback
                    ) { link ->

                        found.set(true)
                        callback(link)
                    }

                } catch (_: Exception) {
                }
            }

        if (found.get()) {
            return true
        }

        /*
         * 5. Direct m3u8 / mp4 fallback.
         */
        val html =
            watchingResponse.text
                .replace("\\/", "/")
                .replace("&amp;", "&")

        val mediaRegex =
            Regex(
                """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:[^\s"'<>\\]*)?""",
                RegexOption.IGNORE_CASE
            )

        mediaRegex
            .findAll(html)
            .map {
                it.value
            }
            .distinct()
            .forEach { media ->

                val type =
                    if (
                        media.contains(
                            ".m3u8",
                            true
                        )
                    ) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = media,
                        type = type
                    ) {

                        referer =
                            actualWatchingUrl

                        quality =
                            Qualities.Unknown.value
                    }
                )

                found.set(true)
            }

        return found.get()
    }
}
