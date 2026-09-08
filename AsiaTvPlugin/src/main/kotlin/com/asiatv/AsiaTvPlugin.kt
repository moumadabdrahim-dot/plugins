package com.asiatv

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
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

    private val tag = "AsiaTvDiag"

    private val headers: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
        )

    private fun imageHeaders() = mapOf(
        "User-Agent" to headers.getValue("User-Agent"),
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/types/%D8%A7%D9%84%D8%AF%D8%B1%D8%A7%D9%85%D8%A7-%D8%A7%D9%84%D9%83%D9%88%D8%B1%D9%8A%D8%A9/" to "الدراما الكورية",
        "$mainUrl/types/%D8%A7%D9%84%D8%AF%D8%B1%D8%A7%D9%85%D8%A7-%D8%A7%D9%84%D8%B5%D9%8A%D9%86%D9%8A%D8%A9/" to "الدراما الصينية",
        "$mainUrl/types/%D8%A7%D9%84%D8%AF%D8%B1%D8%A7%D9%85%D8%A7-%D8%A7%D9%84%D8%AA%D8%A7%D9%8A%D9%84%D8%A7%D9%86%D8%AF%D9%8A%D8%A9/" to "الدراما التايلاندية",
        "$mainUrl/types/%D8%A7%D9%84%D8%AF%D8%B1%D8%A7%D9%85%D8%A7-%D8%A7%D9%84%D9%8A%D8%A7%D8%A8%D8%A7%D9%86%D9%8A%D8%A9/" to "الدراما اليابانية",
        "$mainUrl/types/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D8%B3%D9%8A%D9%88%D9%8A%D8%A9/" to "الأفلام الآسيوية"
    )

    private data class Page(
        val url: String,
        val document: Document,
        val html: String
    )

    // -----------------------------------------------------------------
    // URL / HTTP
    // -----------------------------------------------------------------

    private fun absoluteUrl(raw: String?, base: String = mainUrl): String? {
        var value = raw?.trim()?.replace("&amp;", "&") ?: return null
        value = value.trim('"', '\'')
        if (value.isBlank()) return null
        if (value.startsWith("javascript:", true) || value.startsWith("data:", true) || value.startsWith("#")) return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return if (value.startsWith("http://as1tv.com", true)) {
                "https://" + value.substringAfter("http://")
            } else value
        }
        return try {
            URI(base).resolve(value).toString()
        } catch (_: Exception) {
            try { fixUrl(value) } catch (_: Exception) { null }
        }
    }

    private suspend fun getPage(url: String, referer: String? = null): Page? {
        return try {
            val response = if (referer.isNullOrBlank()) {
                app.get(url, headers = headers)
            } else {
                app.get(url, headers = headers, referer = referer)
            }
            Page(response.url, response.document, response.text)
        } catch (e: Exception) {
            Log.w(tag, "GET_FAIL url=$url error=${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------
    // Posters
    // -----------------------------------------------------------------

    private fun badPoster(url: String): Boolean {
        val v = url.lowercase()
        return listOf(
            "placeholder", "no-image", "noimage", "default-image", "default_image",
            "cropped-asiadrama", "logo-footer", "/logo."
        ).any(v::contains)
    }

    private fun posterFromImg(img: Element?, base: String): String? {
        if (img == null) return null
        val raw = mutableListOf<String>()

        listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-cfsrc").forEach { attr ->
            img.attr(attr).takeIf { it.isNotBlank() }?.let(raw::add)
        }

        listOf("data-srcset", "srcset").forEach { attr ->
            img.attr(attr)
                .split(',')
                .map { it.trim().substringBefore(' ').trim() }
                .filter { it.isNotBlank() }
                .reversed()
                .forEach(raw::add)
        }

        img.attr("src").takeIf { it.isNotBlank() }?.let(raw::add)

        val resolved = raw.mapNotNull { absoluteUrl(it, base) }.distinct()
        return resolved.firstOrNull { !badPoster(it) } ?: resolved.firstOrNull()
    }

    private fun posterFromElement(element: Element, base: String = mainUrl): String? {
        val selectors = listOf(
            "div.image img", "div.postmovie-photo img", ".single-thumb-bg img",
            ".poster img", ".image img", "img"
        )

        selectors.forEach { selector ->
            posterFromImg(element.selectFirst(selector), base)?.let {
                if (!badPoster(it)) return it
            }
        }

        element.select("[data-bg], [data-background], [style*='background']").forEach { node ->
            listOf(node.attr("data-bg"), node.attr("data-background")).forEach { raw ->
                absoluteUrl(raw, base)?.let { if (!badPoster(it)) return it }
            }
            Regex("""url\((['\"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
                .find(node.attr("style"))?.groupValues?.getOrNull(2)
                ?.let { absoluteUrl(it, base) }
                ?.let { if (!badPoster(it)) return it }
        }

        return selectors.firstNotNullOfOrNull { posterFromImg(element.selectFirst(it), base) }
    }

    private fun posterFromPage(document: Document, base: String): String? {
        listOf(
            document.selectFirst("meta[property='og:image']")?.attr("content"),
            document.selectFirst("meta[name='twitter:image']")?.attr("content")
        ).mapNotNull { absoluteUrl(it, base) }
            .firstOrNull { !badPoster(it) }
            ?.let { return it }

        listOf(
            "div.single-thumb-bg > img", ".single-thumb-bg img", ".single-thumb img",
            ".post-thumbnail img", ".poster img", "article img"
        ).forEach { selector ->
            posterFromImg(document.selectFirst(selector), base)?.let {
                if (!badPoster(it)) return it
            }
        }
        return null
    }

    // -----------------------------------------------------------------
    // Home / search
    // -----------------------------------------------------------------

    private fun cardToResponse(element: Element): SearchResponse? {
        val anchor = element.selectFirst("div.postmovie-photo a[href]")
            ?: element.selectFirst("a[href*='/drama/']")
            ?: element.selectFirst("a[href*='/movie/']")
            ?: element.selectFirst("a[href]")
            ?: return null

        val url = absoluteUrl(anchor.attr("href")) ?: return null
        if (url == mainUrl || url == "$mainUrl/") return null

        val title = anchor.attr("title").trim().ifBlank {
            element.selectFirst(".title, .Title, h2, h3, h4")?.text()?.trim().orEmpty()
        }.ifBlank {
            anchor.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val poster = posterFromElement(element)
        val fullText = element.text()
        val isMovie = url.contains("/movie/", true) || url.contains("/movies/", true) || title.contains("فيلم") || fullText.contains("فيلم")

        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                posterHeaders = imageHeaders()
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
                posterUrl = poster
                posterHeaders = imageHeaders()
            }
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val unique = linkedMapOf<String, SearchResponse>()
        var items = document.select("div.box-item")
        if (items.isEmpty()) {
            items = document.select("article.post, .post-item, .MovieBlock, .item-post, .post-card")
        }
        items.forEach { item -> cardToResponse(item)?.let { unique[it.url] = it } }

        if (unique.isEmpty()) {
            document.select("div.postmovie-photo a[href], a[href*='/drama/'], a[href*='/movie/']").forEach { a ->
                val container = a.parent() ?: a
                cardToResponse(container)?.let { unique[it.url] = it }
            }
        }
        return unique.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val document = app.get(url, headers = headers).document
        return newHomePageResponse(request.name, parseCards(document))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", headers = headers).document
        return parseCards(document)
    }

    // -----------------------------------------------------------------
    // Details / episodes
    // -----------------------------------------------------------------

    private fun pageTitle(document: Document): String? {
        return document.selectFirst("h1 span.title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1.entry-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun description(document: Document): String? {
        return document.selectFirst("div.getcontent p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".getcontent")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun tags(document: Document): List<String> {
        return document.select("div.box-tags a, a[href*='/genre/'], li:contains(البلد) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun episodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("""الحلقة\s*(?:رقم)?\s*[:：-]?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|\s)ح\s*(\d+)(?:\s|$)""", RegexOption.IGNORE_CASE),
            Regex("""[-_/]ح(\d+)(?:[-_/]|$)""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun episodeAnchors(document: Document): List<Element> {
        val exact = document.select("div.loop-episode a[href]")
        if (exact.isNotEmpty()) return exact
        return document.select("a[href*='/episodes/'], a[href*='/episode/']")
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = headers)
        val document = response.document
        val finalUrl = response.url
        val title = pageTitle(document) ?: return null
        val poster = posterFromPage(document, finalUrl)
        val plot = description(document)
        val genres = tags(document)

        val unique = linkedMapOf<String, Episode>()
        episodeAnchors(document).forEach { a ->
            val href = absoluteUrl(a.attr("href"), finalUrl) ?: return@forEach
            if (!href.contains("/episode/", true) && !href.contains("/episodes/", true)) return@forEach

            val rawName = a.selectFirst("div.titlepisode")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                ?: a.text().trim().takeIf { it.isNotBlank() }
                ?: "حلقة"
            val number = episodeNumber("$rawName $href")

            unique[href] = newEpisode(href) {
                name = if (number != null) "الحلقة $number" else rawName
                episode = number
                season = 1
                posterUrl = poster
            }
        }

        val episodes = unique.values.toList().sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE }
        )

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, finalUrl, TvType.AsianDrama, episodes) {
                posterUrl = poster
                posterHeaders = imageHeaders()
                this.plot = plot
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) {
                posterUrl = poster
                posterHeaders = imageHeaders()
                this.plot = plot
                this.tags = genres
            }
        }
    }

    // -----------------------------------------------------------------
    // Watch transition
    // -----------------------------------------------------------------

    private fun marker(e: Element): String = buildString {
        append(e.text()).append(' ')
        append(e.attr("value")).append(' ')
        append(e.attr("name")).append(' ')
        append(e.attr("class")).append(' ')
        append(e.attr("id")).append(' ')
        append(e.attr("action"))
    }.lowercase()

    private fun looksLikeWatch(e: Element): Boolean {
        val m = marker(e)
        return m.contains("مشاهدة") || m.contains("شاهد") || m.contains("watch") || m.contains("player")
    }

    private fun findWatchLink(document: Document, base: String): String? {
        listOf(
            "div.loop-episode a.current[href]",
            "a.watch_player[href]",
            "a[href*='/watching/']"
        ).forEach { selector ->
            document.selectFirst(selector)?.attr("href")?.let { absoluteUrl(it, base) }?.let { return it }
        }
        document.select("a[href]").firstOrNull(::looksLikeWatch)
            ?.attr("href")?.let { absoluteUrl(it, base) }?.let { return it }
        return null
    }

    private fun findWatchForm(document: Document): Element? {
        return document.select("form").firstOrNull { form ->
            looksLikeWatch(form) || form.select("input, button").any(::looksLikeWatch)
        }
    }

    private fun formData(form: Element): MutableMap<String, String> {
        val data = linkedMapOf<String, String>()
        form.select("input[name], textarea[name], select[name]").forEach { field ->
            val name = field.attr("name").trim()
            if (name.isBlank()) return@forEach
            val type = field.attr("type").lowercase()
            if ((type == "checkbox" || type == "radio") && !field.hasAttr("checked")) return@forEach
            val value = if (field.tagName() == "select") {
                field.selectFirst("option[selected]")?.attr("value")
                    ?: field.selectFirst("option")?.attr("value").orEmpty()
            } else {
                field.attr("value")
            }
            data[name] = value
        }

        form.select("input[type='submit'][name], button[name]")
            .firstOrNull(::looksLikeWatch)
            ?.let { submit ->
                val name = submit.attr("name").trim()
                if (name.isNotBlank()) data[name] = submit.attr("value").ifBlank { submit.text().trim() }
            }
        return data
    }

    private fun appendQuery(url: String, data: Map<String, String>): String {
        if (data.isEmpty()) return url
        val query = data.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return url + if (url.contains('?')) "&$query" else "?$query"
    }

    private suspend fun submitWatchForm(page: Page): Page? {
        val form = findWatchForm(page.document) ?: return null
        val action = absoluteUrl(form.attr("action").ifBlank { page.url }, page.url) ?: return null
        val data = formData(form)
        val method = form.attr("method").lowercase().ifBlank { "get" }
        Log.d(tag, "WATCH_FORM method=$method action=$action fields=${data.size}")

        return try {
            val response = if (method == "post") {
                app.post(action, data = data, headers = headers, referer = page.url)
            } else {
                app.get(appendQuery(action, data), headers = headers, referer = page.url)
            }
            Page(response.url, response.document, response.text)
        } catch (e: Exception) {
            Log.w(tag, "WATCH_FORM_FAIL action=$action error=${e.message}")
            null
        }
    }

    private suspend fun followJsBridge(page: Page): Page? {
        val html = page.html.replace("\\/", "/")
        val myUrl = Regex("""var\s+myUrl\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        val news = Regex("""myInput\.value\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)

        if (myUrl.isNullOrBlank() || news.isNullOrBlank()) return null
        val target = absoluteUrl(myUrl, page.url) ?: return null
        Log.d(tag, "JS_BRIDGE target=$target")
        return try {
            val response = app.post(
                target,
                data = mapOf("news" to news, "u" to "", "submit" to "submit"),
                headers = headers,
                referer = page.url
            )
            Page(response.url, response.document, response.text)
        } catch (e: Exception) {
            Log.w(tag, "JS_BRIDGE_FAIL target=$target error=${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------
    // Player discovery
    // -----------------------------------------------------------------

    private fun csrfHeaders(page: Page): Map<String, String> {
        val token = page.document.selectFirst("meta[name='csrf-token']")?.attr("content")?.trim()
        val withOrigin = headers + ("Origin" to mainUrl)
        return if (token.isNullOrBlank()) withOrigin else withOrigin + ("X-CSRF-TOKEN" to token)
    }

    private fun ajaxCandidates(page: Page): List<String> {
        val found = linkedSetOf<String>()
        listOf(
            Regex("""ajaxRequest\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE),
            Regex("""ajaxurl\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        ).forEach { regex ->
            regex.findAll(page.html).forEach { match ->
                absoluteUrl(match.groupValues.getOrNull(1), page.url)?.let(found::add)
            }
        }
        if (page.html.contains("/ajaxGetRequest", true)) absoluteUrl("/ajaxGetRequest", page.url)?.let(found::add)
        if (page.html.contains("admin-ajax.php", true)) absoluteUrl("/wp-admin/admin-ajax.php", page.url)?.let(found::add)
        if (found.isEmpty()) {
            absoluteUrl("/ajaxGetRequest", page.url)?.let(found::add)
            absoluteUrl("/wp-admin/admin-ajax.php", page.url)?.let(found::add)
        }
        return found.toList()
    }

    private fun directMedia(html: String): List<String> {
        val normalized = html
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
        return Regex(
            """https?://[^\s\"'<>\\]+\.(?:m3u8|mp4|webm|mkv)(?:[^\s\"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized).map { it.value }.distinct().toList()
    }

    private fun iframeUrls(document: Document, base: String): List<String> {
        return document.select("iframe").flatMap { iframe ->
            listOf("src", "data-src", "data-url").mapNotNull { attr -> absoluteUrl(iframe.attr(attr), base) }
        }.distinct()
    }

    private fun attributeUrls(document: Document, base: String): List<String> {
        val urls = linkedSetOf<String>()
        document.select("[data-server], [data-link], [data-url], [data-embed], [data-video]").forEach { e ->
            listOf("data-server", "data-link", "data-url", "data-embed", "data-video").forEach { attr ->
                absoluteUrl(e.attr(attr), base)?.let(urls::add)
            }
        }
        return urls.toList()
    }

    private fun dataCodes(document: Document): List<String> {
        return document.select("[data-code]")
            .map { it.attr("data-code").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun codePlayHtml(text: String): String? {
        return try {
            JSONObject(text).optString("codeplay").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            Regex("""\"codeplay\"\s*:\s*\"((?:\\.|[^\"])*)\"""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
        }
    }

    private suspend fun resolveAjaxCodes(page: Page): List<String> {
        val codes = dataCodes(page.document)
        if (codes.isEmpty()) return emptyList()

        val endpoints = ajaxCandidates(page)
        Log.d(tag, "DATA_CODE count=${codes.size} ajaxCandidates=${endpoints.size}")
        val discovered = linkedSetOf<String>()

        codes.take(12).amap { code ->
            var solved = false
            for (endpoint in endpoints) {
                if (solved) break
                try {
                    val response = app.post(
                        endpoint,
                        data = mapOf("action" to "iframe_server", "code" to code),
                        headers = csrfHeaders(page),
                        referer = page.url
                    )
                    val codeplay = codePlayHtml(response.text)
                    if (!codeplay.isNullOrBlank()) {
                        solved = true
                        val doc = Jsoup.parse(codeplay, response.url)
                        val urls = iframeUrls(doc, response.url) + attributeUrls(doc, response.url) + directMedia(codeplay)
                        synchronized(discovered) { discovered.addAll(urls) }
                    }
                } catch (e: Exception) {
                    Log.d(tag, "AJAX_CODE_FAIL endpoint=$endpoint error=${e.message}")
                }
            }
        }
        return discovered.toList()
    }

    // -----------------------------------------------------------------
    // Extractors
    // -----------------------------------------------------------------

    private fun isDirectMedia(url: String): Boolean {
        val v = url.lowercase()
        return v.contains(".m3u8") || v.contains(".mp4") || v.contains(".webm") || v.contains(".mkv")
    }

    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(
                source = name,
                name = "$name Direct",
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun processUrls(
        urls: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        found: AtomicBoolean
    ) {
        urls.distinct().take(18).amap { url ->
            if (isDirectMedia(url)) {
                emitDirect(url, referer, callback)
                found.set(true)
                return@amap
            }
            try {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    found.set(true)
                    callback(link)
                }
            } catch (e: Exception) {
                Log.d(tag, "EXTRACTOR_FAIL host=${runCatching { URI(url).host }.getOrNull()} error=${e.message}")
            }
        }
    }

    private suspend fun inspectUnknownPage(
        url: String,
        referer: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        found: AtomicBoolean
    ) {
        if (depth < 0 || found.get()) return
        val page = getPage(url, referer) ?: return

        val rawDirect = linkedSetOf<String>()
        rawDirect.addAll(directMedia(page.html))
        runCatching { getAndUnpack(page.html) }.getOrNull()?.let { rawDirect.addAll(directMedia(it)) }
        rawDirect.forEach { media -> emitDirect(media, page.url, callback); found.set(true) }
        if (found.get() || depth == 0) return

        val nested = (iframeUrls(page.document, page.url) + attributeUrls(page.document, page.url) + resolveAjaxCodes(page)).distinct()
        processUrls(nested, page.url, subtitleCallback, callback, found)
        if (found.get()) return

        nested.filterNot(::isDirectMedia).take(6).forEach { child ->
            inspectUnknownPage(child, page.url, depth - 1, subtitleCallback, callback, found)
            if (found.get()) return
        }
    }

    private suspend fun collectAndProcess(
        page: Page,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        found: AtomicBoolean
    ): List<String> {
        val direct = linkedSetOf<String>()
        direct.addAll(directMedia(page.html))
        runCatching { getAndUnpack(page.html) }.getOrNull()?.let { direct.addAll(directMedia(it)) }

        val iframes = iframeUrls(page.document, page.url)
        val attributes = attributeUrls(page.document, page.url)
        val ajax = resolveAjaxCodes(page)
        val all = (direct + iframes + attributes + ajax).distinct()

        Log.d(
            tag,
            "PLAYER url=${page.url} iframe=${iframes.size} attributes=${attributes.size} dataCode=${dataCodes(page.document).size} direct=${direct.size} ajaxResolved=${ajax.size}"
        )
        processUrls(all, page.url, subtitleCallback, callback, found)
        return all
    }

    // -----------------------------------------------------------------
    // Playback
    // -----------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val found = AtomicBoolean(false)
        var page = getPage(data, mainUrl) ?: return false

        Log.d(
            tag,
            "START url=${page.url} forms=${page.document.select("form").size} watchInputs=${page.document.select("input,button").count(::looksLikeWatch)} watchLinks=${page.document.select("a[href]").count(::looksLikeWatch)}"
        )

        var candidates = collectAndProcess(page, subtitleCallback, callback, found)
        if (found.get()) return true

        val watchLink = findWatchLink(page.document, page.url)
        if (!watchLink.isNullOrBlank() && watchLink != page.url) {
            Log.d(tag, "WATCH_LINK url=$watchLink")
            getPage(watchLink, page.url)?.let { page = it }
            candidates = collectAndProcess(page, subtitleCallback, callback, found)
            if (found.get()) return true
        } else {
            Log.d(tag, "WATCH_LINK none")
        }

        submitWatchForm(page)?.let { submitted ->
            page = submitted
            candidates = collectAndProcess(page, subtitleCallback, callback, found)
            if (found.get()) return true
        }

        repeat(2) {
            val bridged = followJsBridge(page) ?: return@repeat
            if (bridged.url == page.url && bridged.html == page.html) return@repeat
            page = bridged
            candidates = collectAndProcess(page, subtitleCallback, callback, found)
            if (found.get()) return true
        }

        candidates.filterNot(::isDirectMedia).distinct().take(8).forEach { candidate ->
            inspectUnknownPage(candidate, page.url, 2, subtitleCallback, callback, found)
            if (found.get()) return true
        }

        Log.d(tag, "END success=${found.get()}")
        return found.get()
    }
}
