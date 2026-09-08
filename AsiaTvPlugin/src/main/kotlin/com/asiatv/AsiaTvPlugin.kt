package com.asiatv

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

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

    private val logTag = "AsiaTvPlugin"

    private val browserHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",

            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,image/apng,*/*;q=0.8",

            "Accept-Language" to
                "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",

            "Referer" to "$mainUrl/"
        )

    private val postHeaders: Map<String, String>
        get() = browserHeaders + mapOf(
            "Accept" to
                "text/html,application/xhtml+xml,application/json," +
                "text/javascript,*/*;q=0.8",

            "X-Requested-With" to "XMLHttpRequest",

            "Content-Type" to
                "application/x-www-form-urlencoded; charset=UTF-8"
        )

    // =========================================================
    // Main page
    // =========================================================

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
            "أفلام آسيوية"
    )

    private data class PageSnapshot(
        val url: String,
        val document: Document,
        val html: String
    )

    private data class EpisodeItem(
        val url: String,
        val name: String,
        val episode: Int?,
        val season: Int?
    )

    // =========================================================
    // URL helpers
    // =========================================================

    private fun resolveUrl(
        raw: String?,
        base: String = mainUrl
    ): String? {

        var value =
            raw
                ?.trim()
                ?.replace("&amp;", "&")
                ?: return null

        value = value.trim('"', '\'')

        if (value.isBlank()) return null

        if (
            value.startsWith("data:", true) ||
            value.startsWith("javascript:", true) ||
            value.startsWith("#")
        ) {
            return null
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        if (
            value.startsWith("http://", true) ||
            value.startsWith("https://", true)
        ) {
            return value
        }

        return try {

            URI(base)
                .resolve(value)
                .toString()

        } catch (_: Exception) {

            try {
                fixUrl(value)
            } catch (_: Exception) {
                null
            }
        }
    }

    // =========================================================
    // Images
    // =========================================================

    private fun srcsetCandidates(srcset: String): List<String> {

        if (srcset.isBlank()) {
            return emptyList()
        }

        return srcset
            .split(',')
            .map {
                it.trim()
                    .substringBefore(' ')
                    .trim()
            }
            .filter {
                it.isNotBlank()
            }
            .reversed()
    }

    private fun isPlaceholderImage(url: String): Boolean {

        val lower = url.lowercase()

        return listOf(
            "placeholder",
            "no-image",
            "noimage",
            "default-image",
            "default_image",
            "cropped-asiadrama",
            "/logo."
        ).any {
            lower.contains(it)
        }
    }

    /**
     * يأخذ صور Lazy Loading قبل src العادي.
     * هذا مهم لأن src في الموقع قد يكون صورة AsiaDrama الافتراضية.
     */
    private fun extractPoster(
        element: Element?,
        base: String = mainUrl
    ): String? {

        if (element == null) {
            return null
        }

        val rawCandidates =
            mutableListOf<String>()

        val images =
            element.select("img")

        for (img in images) {

            listOf(
                "data-original",
                "data-lazy-src",
                "data-src",
                "data-image",
                "data-cfsrc"
            ).forEach { attribute ->

                img.attr(attribute)
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        rawCandidates.add(it)
                    }
            }

            srcsetCandidates(
                img.attr("data-srcset")
            ).forEach {
                rawCandidates.add(it)
            }

            srcsetCandidates(
                img.attr("srcset")
            ).forEach {
                rawCandidates.add(it)
            }

            img.attr("src")
                .takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    rawCandidates.add(it)
                }
        }

        element.select(
            "[data-bg], " +
            "[data-background], " +
            "[style*='background']"
        ).forEach { node ->

            node.attr("data-bg")
                .takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    rawCandidates.add(it)
                }

            node.attr("data-background")
                .takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    rawCandidates.add(it)
                }

            val style =
                node.attr("style")

            Regex(
                """url\((['"]?)(.*?)\1\)""",
                RegexOption.IGNORE_CASE
            )
                .find(style)
                ?.groupValues
                ?.getOrNull(2)
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    rawCandidates.add(it)
                }
        }

        val resolved =
            rawCandidates
                .mapNotNull {
                    resolveUrl(it, base)
                }
                .filterNot {
                    it.startsWith(
                        "data:",
                        true
                    )
                }
                .distinct()

        return resolved
            .firstOrNull {
                !isPlaceholderImage(it)
            }
            ?: resolved.firstOrNull()
    }

    private fun pagePoster(
        document: Document,
        base: String
    ): String? {

        val metadataCandidates =
            listOf(
                document
                    .selectFirst(
                        "meta[property='og:image']"
                    )
                    ?.attr("content"),

                document
                    .selectFirst(
                        "meta[name='twitter:image']"
                    )
                    ?.attr("content"),

                document
                    .selectFirst(
                        "meta[property='twitter:image']"
                    )
                    ?.attr("content")
            )

        metadataCandidates
            .mapNotNull {
                resolveUrl(it, base)
            }
            .firstOrNull {
                !isPlaceholderImage(it)
            }
            ?.let {
                return it
            }

        val containers =
            listOf(
                ".poster",
                ".single-poster",
                ".single-thumb",
                ".post-thumbnail",
                ".featured-image",
                ".cover",
                "article"
            )

        for (selector in containers) {

            val poster =
                extractPoster(
                    document.selectFirst(selector),
                    base
                )

            if (!poster.isNullOrBlank()) {
                return poster
            }
        }

        return null
    }

    private fun imageHeaders(): Map<String, String> {

        return mapOf(
            "User-Agent" to
                browserHeaders.getValue(
                    "User-Agent"
                ),

            "Referer" to "$mainUrl/"
        )
    }

    // =========================================================
    // Listing parser
    // =========================================================

    private fun titleFromElement(
        element: Element,
        anchor: Element
    ): String? {

        return element
            .selectFirst(
                "h1, h2, h3, h4, " +
                ".Title, .title, " +
                ".BlockTitle, .post-title"
            )
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

            ?: anchor
                .attr("title")
                .trim()
                .takeIf {
                    it.isNotBlank()
                }

            ?: anchor
                .selectFirst("img")
                ?.attr("alt")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
    }

    private fun toSearchResponse(
        element: Element
    ): SearchResponse? {

        val anchor =
            element.selectFirst(
                "a[href*='/drama/'], " +
                "a[href*='/movies/'], " +
                "a[href*='/movie/'], " +
                "a[href*='/episodes/'], " +
                "a[href]"
            )
                ?: return null

        val href =
            resolveUrl(
                anchor.attr("href")
            )
                ?: return null

        val title =
            titleFromElement(
                element,
                anchor
            )
                ?: return null

        if (
            href == mainUrl ||
            href == "$mainUrl/"
        ) {
            return null
        }

        val poster =
            extractPoster(
                element,
                mainUrl
            )
                ?: extractPoster(
                    anchor,
                    mainUrl
                )

        val lowerUrl =
            href.lowercase()

        val isMovie =
            lowerUrl.contains("/movies/") ||
            lowerUrl.contains("/movie/") ||
            title.contains("فيلم")

        return if (isMovie) {

            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                this.posterUrl = poster
                this.posterHeaders =
                    imageHeaders()
            }

        } else {

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.AsianDrama
            ) {
                this.posterUrl = poster
                this.posterHeaders =
                    imageHeaders()
            }
        }
    }

    private fun parseListing(
        document: Document
    ): List<SearchResponse> {

        val unique =
            linkedMapOf<
                String,
                SearchResponse
            >()

        val primary =
            document.select(
                "article.post, " +
                ".post-item, " +
                ".MovieBlock, " +
                ".box-item, " +
                ".item-post, " +
                ".post-card"
            )

        for (element in primary) {

            val response =
                toSearchResponse(element)
                    ?: continue

            unique[response.url] =
                response
        }

        /*
         * إذا تغيرت أسماء CSS في الموقع،
         * نبحث عن روابط الأعمال نفسها.
         */
        if (unique.isEmpty()) {

            document.select(
                "a[href*='/drama/'], " +
                "a[href*='/movies/'], " +
                "a[href*='/movie/']"
            ).forEach { anchor ->

                val container =
                    anchor.closest(
                        "article, li, " +
                        ".item, .post, " +
                        ".card, div"
                    )
                        ?: anchor

                val response =
                    toSearchResponse(
                        container
                    )
                        ?: return@forEach

                unique[response.url] =
                    response
            }
        }

        return unique
            .values
            .toList()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val base =
            request.data
                .trimEnd('/')

        val url =
            if (page <= 1) {

                "$base/"

            } else {

                "$base/page/$page/"
            }

        val document =
            app.get(
                url,
                headers = browserHeaders,
                referer = mainUrl
            ).document

        return newHomePageResponse(
            request.name,
            parseListing(document)
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
                query.trim(),
                "UTF-8"
            )

        val document =
            app.get(
                "$mainUrl/?s=$encoded",
                headers = browserHeaders,
                referer = mainUrl
            ).document

        return parseListing(
            document
        )
    }

    // =========================================================
    // Details parser
    // =========================================================

    private fun labelValue(
        document: Document,
        label: String
    ): String? {

        val nodes =
            document.select(
                "li, p, div, span"
            )

        for (node in nodes) {

            val own =
                node.ownText()
                    .trim()

            val full =
                node.text()
                    .trim()

            for (
                candidate
                in listOf(
                    own,
                    full
                )
            ) {

                if (
                    candidate.length > 300
                ) {
                    continue
                }

                if (
                    !candidate.startsWith(
                        label
                    )
                ) {
                    continue
                }

                val value =
                    candidate
                        .removePrefix(label)
                        .trim()
                        .trimStart(
                            ':',
                            '：',
                            '-',
                            '–'
                        )
                        .trim()

                if (value.isNotBlank()) {
                    return value
                }
            }
        }

        return null
    }

    private fun parseEpisodeNumber(
        text: String
    ): Int? {

        val patterns =
            listOf(
                Regex(
                    """الحلقة\s*(?:رقم)?\s*[:：-]?\s*(\d+)""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """(?:^|\s)ح\s*(\d+)(?:\s|$)""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """[-_/]ح(\d+)[-_/]""",
                    RegexOption.IGNORE_CASE
                )
            )

        for (pattern in patterns) {

            pattern
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let {
                    return it
                }
        }

        return null
    }

    private fun parseSeasonNumber(
        text: String
    ): Int? {

        Regex(
            """(?:الموسم|الجزء)\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        return when {

            text.contains("الجزء الأول") ||
            text.contains("الموسم الأول") ->
                1

            text.contains("الجزء الثاني") ||
            text.contains("الموسم الثاني") ->
                2

            text.contains("الجزء الثالث") ||
            text.contains("الموسم الثالث") ->
                3

            text.contains("الجزء الرابع") ||
            text.contains("الموسم الرابع") ->
                4

            text.contains("الجزء الخامس") ||
            text.contains("الموسم الخامس") ->
                5

            else ->
                null
        }
    }

    /**
     * لا يعتمد على اسم CSS واحد.
     * الرابط /episodes/ أكثر ثباتًا من class الموقع.
     */
    private fun extractEpisodes(
        document: Document
    ): List<EpisodeItem> {

        val unique =
            linkedMapOf<
                String,
                EpisodeItem
            >()

        document
            .select(
                "a[href*='/episodes/']"
            )
            .forEach { anchor ->

                val href =
                    resolveUrl(
                        anchor.attr("href")
                    )
                        ?: return@forEach

                if (
                    !href.contains(
                        "/episodes/"
                    )
                ) {
                    return@forEach
                }

                val titleAttr =
                    anchor
                        .attr("title")
                        .trim()

                val text =
                    anchor
                        .text()
                        .trim()

                val combined =
                    "$text $titleAttr $href"

                val episodeNumber =
                    parseEpisodeNumber(
                        combined
                    )

                if (
                    episodeNumber == null &&
                    !combined.contains(
                        "الحلقة"
                    )
                ) {
                    return@forEach
                }

                val seasonNumber =
                    parseSeasonNumber(
                        combined
                    )
                        ?: 1

                val isEnd =
                    combined.contains(
                        "END",
                        true
                    )

                val displayName =
                    when {

                        episodeNumber != null &&
                        isEnd ->

                            "الحلقة $episodeNumber - END"

                        episodeNumber != null ->

                            "الحلقة $episodeNumber"

                        text.isNotBlank() ->

                            text

                        titleAttr.isNotBlank() ->

                            titleAttr

                        else ->

                            "حلقة"
                    }

                unique[href] =
                    EpisodeItem(
                        url = href,
                        name = displayName,
                        episode =
                            episodeNumber,
                        season =
                            seasonNumber
                    )
            }

        return unique
            .values
            .sortedWith(
                compareBy<EpisodeItem> {
                    it.season ?: 1
                }.thenBy {
                    it.episode
                        ?: Int.MAX_VALUE
                }
            )
    }

    private fun extractDescription(
        document: Document
    ): String? {

        val selectors =
            listOf(
                ".story p",
                ".Story p",
                ".description p",
                ".Description",
                ".post-content .description",
                "article .entry-content > p",
                ".entry-content > p"
            )

        for (selector in selectors) {

            val value =
                document
                    .select(selector)
                    .map {
                        it.text().trim()
                    }
                    .firstOrNull {
                        it.length >= 30
                    }

            if (
                !value.isNullOrBlank()
            ) {
                return value
            }
        }

        return document
            .selectFirst(
                "meta[property='og:description']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun extractGenres(
        document: Document
    ): List<String> {

        return document
            .select(
                ".genre a, " +
                ".genres a, " +
                "ul.anime-genres li a, " +
                "a[rel='category tag'], " +
                "a[href*='/genre/']"
            )
            .map {
                it.text().trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()
            .take(12)
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val response =
            app.get(
                url,
                headers =
                    browserHeaders,
                referer =
                    mainUrl
            )

        val document =
            response.document

        val finalUrl =
            response.url

        val pageTitle =
            document
                .selectFirst(
                    "h1.entry-title, " +
                    "h1.post-title, " +
                    "h1 .title, h1"
                )
                ?.text()
                ?.trim()

                ?: document
                    .selectFirst(
                        "meta[property='og:title']"
                    )
                    ?.attr("content")
                    ?.trim()

                ?: return null

        /*
         * إذا دخلنا من صفحة حلقة مباشرة،
         * نحاول إظهار اسم المسلسل الحقيقي.
         */
        val workName =
            labelValue(
                document,
                "اسم العمل"
            )

        val arabicName =
            labelValue(
                document,
                "الاسم العربي"
            )

        val title =
            if (
                finalUrl.contains(
                    "/episodes/"
                ) &&
                !workName.isNullOrBlank()
            ) {

                if (
                    !arabicName.isNullOrBlank() &&
                    !workName.contains(
                        arabicName,
                        true
                    )
                ) {

                    "$workName - $arabicName"

                } else {

                    workName
                }

            } else {

                pageTitle
            }

        val poster =
            pagePoster(
                document,
                finalUrl
            )

        val description =
            extractDescription(
                document
            )

        val genres =
            extractGenres(
                document
            )

        val year =
            Regex(
                """\b(19|20)\d{2}\b"""
            )
                .find(pageTitle)
                ?.value
                ?.toIntOrNull()

        val episodeItems =
            extractEpisodes(
                document
            )

        val episodes =
            episodeItems.map { item ->

                newEpisode(
                    item.url
                ) {
                    name =
                        item.name

                    episode =
                        item.episode

                    season =
                        item.season

                    posterUrl =
                        poster
                }
            }

        val isSeries =
            episodes.isNotEmpty() ||
            finalUrl.contains(
                "/drama/"
            ) ||
            finalUrl.contains(
                "/episodes/"
            ) ||
            pageTitle.contains(
                "مسلسل"
            )

        return if (isSeries) {

            newTvSeriesLoadResponse(
                title,
                finalUrl,
                TvType.AsianDrama,
                episodes
            ) {

                this.posterUrl =
                    poster

                this.posterHeaders =
                    imageHeaders()

                this.plot =
                    description

                this.tags =
                    genres

                this.year =
                    year
            }

        } else {

            newMovieLoadResponse(
                title,
                finalUrl,
                TvType.Movie,
                finalUrl
            ) {

                this.posterUrl =
                    poster

                this.posterHeaders =
                    imageHeaders()

                this.plot =
                    description

                this.tags =
                    genres

                this.year =
                    year
            }
        }
    }

    // =========================================================
    // HTTP page helpers
    // =========================================================

    private suspend fun getPage(
        url: String,
        referer: String
    ): PageSnapshot? {

        return try {

            val response =
                app.get(
                    url,
                    headers =
                        browserHeaders,
                    referer =
                        referer,
                    allowRedirects =
                        true
                )

            PageSnapshot(
                url =
                    response.url,

                document =
                    response.document,

                html =
                    response.text
            )

        } catch (e: Exception) {

            Log.w(
                logTag,
                "GET failed: $url -> ${e.message}"
            )

            null
        }
    }

    private suspend fun postPage(
        url: String,
        data: Map<String, String>,
        referer: String
    ): PageSnapshot? {

        return try {

            val response =
                app.post(
                    url,
                    data = data,
                    headers =
                        postHeaders,
                    referer =
                        referer
                )

            PageSnapshot(
                url =
                    response.url,

                document =
                    response.document,

                html =
                    response.text
            )

        } catch (e: Exception) {

            Log.w(
                logTag,
                "POST failed: $url -> ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // Watch page resolver
    // =========================================================

    private fun watchMarker(
        element: Element
    ): String {

        return buildString {

            append(
                element.text()
            )

            append(' ')

            append(
                element.attr(
                    "class"
                )
            )

            append(' ')

            append(
                element.attr(
                    "id"
                )
            )

            append(' ')

            append(
                element.attr(
                    "value"
                )
            )

            append(' ')

            append(
                element.attr(
                    "name"
                )
            )

            append(' ')

            append(
                element.attr(
                    "action"
                )
            )

        }.lowercase()
    }

    private fun findWatchLink(
        document: Document,
        base: String
    ): String? {

        document
            .select("a[href]")
            .forEach { anchor ->

                val marker =
                    watchMarker(
                        anchor
                    )

                if (
                    marker.contains(
                        "مشاهدة الحلقة"
                    ) ||
                    marker.contains(
                        "شاهد الحلقة"
                    ) ||
                    marker.contains(
                        "watch_player"
                    ) ||
                    marker.contains(
                        "watch-player"
                    ) ||
                    marker.contains(
                        "watch now"
                    ) ||
                    marker.contains(
                        "watching"
                    )
                ) {

                    resolveUrl(
                        anchor.attr(
                            "href"
                        ),
                        base
                    )?.let {
                        return it
                    }
                }
            }

        return null
    }

    /**
     * موقع AsiaTV يظهر زر مشاهدة الحلقة
     * كـ input/form في بعض الصفحات.
     */
    private fun findWatchForm(
        document: Document
    ): Element? {

        return document
            .select("form")
            .firstOrNull { form ->

                val marker =
                    buildString {

                        append(
                            watchMarker(
                                form
                            )
                        )

                        form
                            .select(
                                "input, button"
                            )
                            .forEach {

                                append(' ')

                                append(
                                    watchMarker(
                                        it
                                    )
                                )
                            }
                    }

                marker.contains(
                    "مشاهدة الحلقة"
                ) ||
                marker.contains(
                    "شاهد الحلقة"
                ) ||
                marker.contains(
                    "watch"
                ) ||
                marker.contains(
                    "player"
                )
            }
    }

    private fun formData(
        form: Element
    ): MutableMap<String, String> {

        val data =
            linkedMapOf<
                String,
                String
            >()

        form
            .select(
                "input[name]"
            )
            .forEach { input ->

                val name =
                    input
                        .attr("name")
                        .trim()

                if (name.isBlank()) {
                    return@forEach
                }

                val type =
                    input
                        .attr("type")
                        .lowercase()

                if (
                    (
                        type == "checkbox" ||
                        type == "radio"
                    ) &&
                    !input.hasAttr(
                        "checked"
                    )
                ) {
                    return@forEach
                }

                data[name] =
                    input.attr(
                        "value"
                    )
            }

        val submit =
            form.selectFirst(
                "input[type='submit'][name], " +
                "button[type='submit'][name], " +
                "button[name]"
            )

        if (submit != null) {

            val submitName =
                submit
                    .attr("name")
                    .trim()

            if (
                submitName.isNotBlank()
            ) {

                data[submitName] =
                    submit
                        .attr("value")
                        .ifBlank {
                            submit
                                .text()
                                .trim()
                        }
            }
        }

        return data
    }

    private fun appendQuery(
        url: String,
        data: Map<String, String>
    ): String {

        if (data.isEmpty()) {
            return url
        }

        val query =
            data.entries
                .joinToString(
                    "&"
                ) {
                    (
                        key,
                        value
                    ) ->

                    "${URLEncoder.encode(key, "UTF-8")}=" +
                    URLEncoder.encode(
                        value,
                        "UTF-8"
                    )
                }

        return url +
            if (
                url.contains('?')
            ) {
                "&$query"
            } else {
                "?$query"
            }
    }

    private suspend fun submitWatchForm(
        page: PageSnapshot
    ): PageSnapshot? {

        val form =
            findWatchForm(
                page.document
            )
                ?: return null

        val action =
            resolveUrl(
                form
                    .attr("action")
                    .ifBlank {
                        page.url
                    },
                page.url
            )
                ?: return null

        val data =
            formData(
                form
            )

        val method =
            form
                .attr("method")
                .trim()
                .lowercase()

        return if (
            method == "post"
        ) {

            postPage(
                action,
                data,
                page.url
            )

        } else {

            getPage(
                appendQuery(
                    action,
                    data
                ),
                page.url
            )
        }
    }

    // =========================================================
    // Intermediate redirect / bridge
    // =========================================================

    private suspend fun followKnownBridge(
        page: PageSnapshot
    ): PageSnapshot? {

        val normalized =
            page.html
                .replace(
                    "\\/",
                    "/"
                )

        /*
         * هذا النمط موجود في بعض قوالب
         * المشاهدة العربية المستخدمة أيضًا
         * في إضافات 3rabi.
         */
        val nextUrl =
            Regex(
                """var\s+myUrl\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    normalized
                )
                ?.groupValues
                ?.getOrNull(1)

        val news =
            Regex(
                """myInput\.value\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    normalized
                )
                ?.groupValues
                ?.getOrNull(1)

        if (
            !nextUrl.isNullOrBlank() &&
            !news.isNullOrBlank()
        ) {

            val resolved =
                resolveUrl(
                    nextUrl,
                    page.url
                )
                    ?: return null

            return postPage(
                resolved,
                mapOf(
                    "news" to news,
                    "u" to "",
                    "submit" to "submit"
                ),
                page.url
            )
        }

        val metaRefresh =
            page.document
                .select(
                    "meta[http-equiv]"
                )
                .firstOrNull {
                    it.attr(
                        "http-equiv"
                    ).equals(
                        "refresh",
                        true
                    )
                }
                ?.attr(
                    "content"
                )
                ?.substringAfter(
                    "url=",
                    ""
                )
                ?.trim()
                ?.trim(
                    '"',
                    '\''
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    resolveUrl(
                        it,
                        page.url
                    )
                }

        if (
            !metaRefresh.isNullOrBlank() &&
            metaRefresh != page.url
        ) {

            return getPage(
                metaRefresh,
                page.url
            )
        }

        val jsRedirect =
            Regex(
                """(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    normalized
                )
                ?.groupValues
                ?.getOrNull(1)
                ?.let {
                    resolveUrl(
                        it,
                        page.url
                    )
                }

        if (
            !jsRedirect.isNullOrBlank() &&
            jsRedirect != page.url
        ) {

            return getPage(
                jsRedirect,
                page.url
            )
        }

        return null
    }

    // =========================================================
    // Server URL decoding
    // =========================================================

    private fun decodeCandidate(
        raw: String,
        base: String
    ): String? {

        var value =
            raw
                .trim()
                .replace(
                    "&amp;",
                    "&"
                )

        if (value.isBlank()) {
            return null
        }

        /*
         * URL encoded server.
         */
        if (
            value.contains(
                "%3A",
                true
            ) ||
            value.contains(
                "%2F",
                true
            )
        ) {

            try {

                value =
                    URLDecoder.decode(
                        value,
                        "UTF-8"
                    )

            } catch (_: Exception) {
            }
        }

        /*
         * Direct / relative URL.
         */
        if (
            value.startsWith(
                "http",
                true
            ) ||
            value.startsWith("//") ||
            value.startsWith("/") ||
            value.contains('/') ||
            value.contains('.')
        ) {

            resolveUrl(
                value,
                base
            )?.let {
                return it
            }
        }

        /*
         * بعض المواقع تخزن embed URL
         * داخل Base64.
         */
        if (
            value.length >= 12 &&
            value.none {
                it.isWhitespace()
            }
        ) {

            val flags =
                listOf(
                    Base64.DEFAULT,
                    Base64.NO_WRAP,
                    Base64.URL_SAFE or
                        Base64.NO_WRAP or
                        Base64.NO_PADDING
                )

            for (flag in flags) {

                try {

                    val decoded =
                        String(
                            Base64.decode(
                                value,
                                flag
                            ),
                            Charsets.UTF_8
                        )
                            .trim()

                    if (
                        decoded.startsWith(
                            "http",
                            true
                        ) ||
                        decoded.startsWith("//") ||
                        decoded.startsWith("/")
                    ) {

                        resolveUrl(
                            decoded,
                            base
                        )?.let {
                            return it
                        }
                    }

                } catch (_: Exception) {
                }
            }
        }

        return null
    }

    // =========================================================
    // Video/server discovery
    // =========================================================

    private fun isAssetUrl(
        url: String
    ): Boolean {

        val clean =
            url
                .substringBefore('?')
                .lowercase()

        return listOf(
            ".jpg",
            ".jpeg",
            ".png",
            ".webp",
            ".gif",
            ".svg",
            ".css",
            ".js",
            ".woff",
            ".woff2",
            ".ttf",
            ".ico"
        ).any {
            clean.endsWith(it)
        }
    }

    private fun isMediaUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()

        return lower.contains(
            ".m3u8"
        ) ||
            lower.contains(
                ".mp4"
            ) ||
            lower.contains(
                ".mkv"
            ) ||
            lower.contains(
                ".webm"
            )
    }

    private fun harvestCandidates(
        page: PageSnapshot
    ): List<String> {

        val candidates =
            linkedSetOf<String>()

        /*
         * iframe
         */
        page.document
            .select("iframe")
            .forEach { iframe ->

                listOf(
                    "src",
                    "data-src",
                    "data-url"
                ).forEach { attribute ->

                    resolveUrl(
                        iframe.attr(
                            attribute
                        ),
                        page.url
                    )?.let {
                        candidates.add(it)
                    }
                }
            }

        /*
         * Common server attributes.
         */
        page.document
            .select(
                "[data-server], " +
                "[data-link], " +
                "[data-url], " +
                "[data-embed], " +
                "[data-player], " +
                "[data-video]"
            )
            .forEach { element ->

                if (
                    element.tagName()
                        .equals(
                            "img",
                            true
                        )
                ) {
                    return@forEach
                }

                listOf(
                    "data-server",
                    "data-link",
                    "data-url",
                    "data-embed",
                    "data-player",
                    "data-video"
                ).forEach { attribute ->

                    val raw =
                        element
                            .attr(
                                attribute
                            )
                            .trim()

                    decodeCandidate(
                        raw,
                        page.url
                    )?.let {
                        candidates.add(it)
                    }
                }
            }

        /*
         * Server anchors.
         */
        page.document
            .select("a[href]")
            .forEach { anchor ->

                val marker =
                    watchMarker(
                        anchor
                    )

                val resolved =
                    resolveUrl(
                        anchor.attr(
                            "href"
                        ),
                        page.url
                    )
                        ?: return@forEach

                if (
                    isMediaUrl(
                        resolved
                    ) ||
                    marker.contains(
                        "server"
                    ) ||
                    marker.contains(
                        "سيرفر"
                    ) ||
                    marker.contains(
                        "player"
                    ) ||
                    marker.contains(
                        "embed"
                    )
                ) {

                    candidates.add(
                        resolved
                    )
                }
            }

        /*
         * Direct links hidden in HTML/JS.
         */
        extractDirectMedia(
            page.html
        ).forEach {
            candidates.add(it)
        }

        /*
         * Packed JS fallback.
         */
        extractPackedMedia(
            page.html
        ).forEach {
            candidates.add(it)
        }

        return candidates
            .filter {
                it.isNotBlank()
            }
            .filterNot {
                isAssetUrl(it)
            }
            .filterNot {
                it == page.url ||
                it == "$mainUrl/"
            }
            .distinct()
    }

    // =========================================================
    // Direct video extraction
    // =========================================================

    private fun extractDirectMedia(
        html: String
    ): Set<String> {

        val normalized =
            html
                .replace(
                    "\\/",
                    "/"
                )
                .replace(
                    "&amp;",
                    "&"
                )
                .replace(
                    "\\u0026",
                    "&"
                )

        val results =
            linkedSetOf<String>()

        Regex(
            """https?://[^\s"'<>\\]+\.(?:m3u8|mp4|mkv|webm)(?:[^\s"'<>\\]*)?""",
            setOf(
                RegexOption.IGNORE_CASE
            )
        )
            .findAll(
                normalized
            )
            .forEach {

                results.add(
                    it.value
                )
            }

        return results
    }

    // =========================================================
    // Packer fallback
    // =========================================================

    private fun intToBase(
        value: Int,
        radixInput: Int
    ): String {

        if (value == 0) {
            return "0"
        }

        val digits =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        val radix =
            radixInput.coerceIn(
                2,
                digits.length
            )

        var number =
            value

        val out =
            StringBuilder()

        while (number > 0) {

            out.append(
                digits[
                    number % radix
                ]
            )

            number /=
                radix
        }

        return out
            .reverse()
            .toString()
    }

    private fun unpackPacker(
        payload: String,
        radix: Int,
        count: Int,
        keys: List<String>
    ): String {

        var result =
            payload
                .replace(
                    "\\/",
                    "/"
                )
                .replace(
                    "\\'",
                    "'"
                )
                .replace(
                    "\\\"",
                    "\""
                )

        for (
            index
            in count - 1 downTo 0
        ) {

            val key =
                keys
                    .getOrNull(
                        index
                    )
                    .orEmpty()

            if (key.isBlank()) {
                continue
            }

            val token =
                intToBase(
                    index,
                    radix
                )

            result =
                Regex(
                    "\\b" +
                    Regex.escape(
                        token
                    ) +
                    "\\b"
                )
                    .replace(
                        result,
                        key
                    )
        }

        return result
    }

    private fun extractPackedMedia(
        html: String
    ): Set<String> {

        val results =
            linkedSetOf<String>()

        val pattern =
            Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*(["'])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(["'])(.*?)\5\.split""",
                setOf(
                    RegexOption.DOT_MATCHES_ALL,
                    RegexOption.IGNORE_CASE
                )
            )

        pattern
            .findAll(
                html
            )
            .forEach { match ->

                try {

                    val payload =
                        match.groupValues[2]

                    val radix =
                        match
                            .groupValues[3]
                            .toIntOrNull()
                            ?: return@forEach

                    val count =
                        match
                            .groupValues[4]
                            .toIntOrNull()
                            ?: return@forEach

                    val keys =
                        match
                            .groupValues[6]
                            .split('|')

                    val unpacked =
                        unpackPacker(
                            payload,
                            radix,
                            count,
                            keys
                        )

                    extractDirectMedia(
                        unpacked
                    ).forEach {
                        results.add(it)
                    }

                } catch (e: Exception) {

                    Log.d(
                        logTag,
                        "Packer fallback failed: ${e.message}"
                    )
                }
            }

        return results
    }

    // =========================================================
    // Emit links
    // =========================================================

    private fun emitDirectMedia(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {

        val type =
            if (
                url.contains(
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
                source =
                    name,

                name =
                    "$name Direct",

                url =
                    url,

                type =
                    type
            ) {

                this.referer =
                    referer

                this.quality =
                    Qualities.Unknown.value
            }
        )
    }

    // =========================================================
    // CloudStream extractors
    // =========================================================

    private suspend fun processCandidates(
        candidates: List<String>,
        referer: String,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit,
        found: AtomicBoolean
    ) {

        /*
         * Parallel processing = أسرع عند وجود
         * عدة سيرفرات.
         *
         * نضع حدًا حتى لا نفتح عشرات الطلبات
         * إذا تغير الموقع وأدخل روابط كثيرة.
         */
        candidates
            .take(12)
            .amap { candidate ->

                if (
                    isMediaUrl(
                        candidate
                    )
                ) {

                    found.set(
                        true
                    )

                    emitDirectMedia(
                        candidate,
                        referer,
                        callback
                    )

                    return@amap
                }

                try {

                    loadExtractor(
                        candidate,
                        referer,
                        subtitleCallback
                    ) { link ->

                        found.set(
                            true
                        )

                        callback(
                            link
                        )
                    }

                } catch (e: Exception) {

                    Log.d(
                        logTag,
                        "Extractor rejected $candidate: ${e.message}"
                    )
                }
            }
    }

    // =========================================================
    // Deep fallback
    // =========================================================

    private suspend fun deepFallback(
        page: PageSnapshot,
        candidates: List<String>,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit,
        found: AtomicBoolean
    ) {

        candidates
            .filterNot {
                isMediaUrl(it)
            }
            .take(6)
            .amap { candidate ->

                val nested =
                    getPage(
                        candidate,
                        page.url
                    )
                        ?: return@amap

                val direct =
                    extractDirectMedia(
                        nested.html
                    )

                val packed =
                    extractPackedMedia(
                        nested.html
                    )

                (
                    direct +
                    packed
                )
                    .distinct()
                    .forEach { media ->

                        found.set(
                            true
                        )

                        emitDirectMedia(
                            media,
                            nested.url,
                            callback
                        )
                    }

                if (found.get()) {
                    return@amap
                }

                /*
                 * Nested iframe / server page.
                 */
                val secondLevel =
                    harvestCandidates(
                        nested
                    )

                processCandidates(
                    secondLevel,
                    nested.url,
                    subtitleCallback,
                    callback,
                    found
                )
            }
    }

    // =========================================================
    // Playback
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Boolean {

        val found =
            AtomicBoolean(
                false
            )

        var page =
            getPage(
                data,
                mainUrl
            )
                ?: return false

        /*
         * FAST PATH
         *
         * إذا كانت الصفحة تحتوي iframe
         * أو data-server مباشرة فلا حاجة
         * لأي POST إضافي.
         */
        var candidates =
            harvestCandidates(
                page
            )

        processCandidates(
            candidates,
            page.url,
            subtitleCallback,
            callback,
            found
        )

        if (found.get()) {
            return true
        }

        /*
         * ASIATV NORMAL PATH
         *
         * Episode/movie page
         *      ↓
         * "مشاهدة الحلقة"
         *      ↓
         * player/server page
         */
        val watchLink =
            findWatchLink(
                page.document,
                page.url
            )

        if (
            !watchLink.isNullOrBlank() &&
            watchLink != page.url
        ) {

            getPage(
                watchLink,
                page.url
            )?.let {

                page = it
            }
        }

        /*
         * قد تكون صفحة المشاهدة
         * عبارة عن form وليس رابطًا.
         */
        submitWatchForm(
            page
        )?.let {

            page = it
        }

        /*
         * بعض القوالب تضع bridge
         * JavaScript/POST آخر قبل iframe.
         *
         * نجرب مرحلتين فقط حتى لا ندخل
         * في loop لا نهائي.
         */
        repeat(2) {

            val bridged =
                followKnownBridge(
                    page
                )
                    ?: return@repeat

            if (
                bridged.url == page.url &&
                bridged.html == page.html
            ) {
                return@repeat
            }

            page =
                bridged
        }

        /*
         * الآن يجب أن نكون في صفحة السيرفرات.
         */
        candidates =
            harvestCandidates(
                page
            )

        processCandidates(
            candidates,
            page.url,
            subtitleCallback,
            callback,
            found
        )

        if (found.get()) {
            return true
        }

        /*
         * آخر حل فقط:
         * نفتح صفحات السيرفرات غير المعروفة
         * ونبحث عن iframe / m3u8 / packer.
         */
        deepFallback(
            page,
            candidates,
            subtitleCallback,
            callback,
            found
        )

        /*
         * لا نقول true إلا إذا وجدنا رابطًا
         * حقيقيًا بالفعل.
         */
        return found.get()
    }
}
