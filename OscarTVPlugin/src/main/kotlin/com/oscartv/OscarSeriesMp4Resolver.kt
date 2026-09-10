package com.oscartv

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

internal const val OSCAR_SERIES_MP4_HOST = "cdn.seriesmp4.com"

internal enum class OscarPlaybackKind {
    WRAPPER,
    DIRECT,
    EXTRACTOR,
}

internal fun OscarWatchLink.playbackKind(): OscarPlaybackKind {
    if (mediaHost().equals(OSCAR_SERIES_MP4_HOST, ignoreCase = true)) {
        return OscarPlaybackKind.WRAPPER
    }
    return if (type.equals("direct", ignoreCase = true) || url.orEmpty().isOscarDirectMediaUrl()) {
        OscarPlaybackKind.DIRECT
    } else {
        OscarPlaybackKind.EXTRACTOR
    }
}

internal fun String.isOscarDirectMediaUrl(): Boolean {
    val path = substringBefore('?').lowercase()
    return path.endsWith(".mp4") || path.endsWith(".m3u8") ||
        path.endsWith(".webm") || path.endsWith(".mkv")
}

internal fun extractOscarIframeUrls(html: String, wrapperUrl: String): List<String> {
    return Jsoup.parse(html, wrapperUrl).select("iframe[src]")
        .mapNotNull { iframe ->
            iframe.absUrl("src").trim().takeIf { it.isNotBlank() }
        }
        .distinct()
}

internal fun selectOscarPlayerIframeUrls(iframeUrls: List<String>): List<String> {
    if (iframeUrls.size <= 1) return iframeUrls
    val nonAdvertisement = iframeUrls.filterNot { it.isOscarObviousAdvertisement() }
    return nonAdvertisement.ifEmpty { iframeUrls }
}

internal fun String.isOscarYandexPlayerUrl(): Boolean {
    val host = toOscarHost() ?: return false
    return host == "downloader.disk.yandex.ru" ||
        host == "disk.yandex.ru" ||
        host.endsWith(".yandex.ru") ||
        host.endsWith(".yandex.net")
}

internal fun resolveOscarFinalMediaType(
    finalUrl: String,
    contentType: String?,
): OscarMediaType? {
    val normalizedType = contentType.orEmpty().substringBefore(';').trim().lowercase()
    return when {
        normalizedType.startsWith("video/") -> OscarMediaType.VIDEO
        normalizedType == "application/vnd.apple.mpegurl" ||
            normalizedType == "application/x-mpegurl" ||
            normalizedType == "audio/mpegurl" -> OscarMediaType.M3U8
        normalizedType.isBlank() &&
            finalUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true) -> OscarMediaType.M3U8
        else -> null
    }
}

internal fun oscarMediaDedupKey(url: String, quality: Int): String {
    return "$url\u0000$quality"
}

internal object OscarSeriesMp4Resolver {
    suspend fun resolve(
        link: OscarWatchLink,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Boolean,
    ): Boolean {
        val wrapperUrl = link.url?.trim().takeUnless { it.isNullOrBlank() } ?: return false
        val wrapperHost = link.mediaHost().orEmpty().ifBlank { "<unknown>" }
        val mediaHeaders = link.mediaHeaders()
        val quality = parseOscarQuality(link.quality ?: wrapperUrl) ?: Qualities.Unknown.value

        logOscar("WRAPPER_DETECTED host=$wrapperHost")

        val wrapperResponse = try {
            app.get(
                wrapperUrl,
                headers = mediaHeaders,
                timeout = OscarTVConfig.requestTimeoutSeconds.toLong(),
                cacheTime = 0,
            )
        } catch (error: Exception) {
            logOscar(
                "WRAPPER_FETCH_FAILED host=$wrapperHost exception=${error::class.java.simpleName} " +
                    "message=${error.safeLogMessageForLog()}",
            )
            return false
        }

        if (wrapperResponse.code !in 200..299) {
            logOscar("WRAPPER_FETCH_FAILED host=$wrapperHost HTTP=${wrapperResponse.code}")
            return false
        }

        val wrapperContentType = wrapperResponse.headers["Content-Type"].orEmpty()
        logOscar(
            "WRAPPER_FETCH_OK wrapper_host=$wrapperHost content_type=" +
                wrapperContentType.safeLogValue(),
        )

        val iframeUrls = selectOscarPlayerIframeUrls(
            extractOscarIframeUrls(wrapperResponse.text, wrapperUrl),
        )
        if (iframeUrls.isEmpty()) {
            logOscar("IFRAME_NOT_FOUND host=$wrapperHost")
            return false
        }

        var emitted = false
        iframeUrls.forEach { iframeUrl ->
            val iframeHost = iframeUrl.toOscarHost().orEmpty().ifBlank { "<unknown>" }
            logOscar("IFRAME_FOUND host=$iframeHost")
            logOscar("PLAYER_RESOLVE_START host=$iframeHost")

            var extractorEmitted = false
            try {
                loadExtractor(iframeUrl, wrapperUrl, subtitleCallback) { extracted ->
                    extractorEmitted = true
                    emitted = emit(extracted) || emitted
                }
            } catch (error: Exception) {
                logOscar(
                    "PLAYER_RESOLVE_FAILED host=$iframeHost extractor=" +
                        "${error::class.java.simpleName} message=${error.safeLogMessageForLog()}",
                )
            }
            if (extractorEmitted) {
                logOscar(
                    "WRAPPER_RESOLVED wrapper_host=$wrapperHost player_host=$iframeHost " +
                        "method=cloudstream_extractor",
                )
                return@forEach
            }

            if (!iframeUrl.isOscarYandexPlayerUrl()) {
                logOscar(
                    "PLAYER_RESOLVE_FAILED host=$iframeHost reason=no_cloudstream_extractor",
                )
                return@forEach
            }

            val finalResponse = try {
                app.head(
                    iframeUrl,
                    headers = mediaHeaders,
                    timeout = OscarTVConfig.requestTimeoutSeconds.toLong(),
                )
            } catch (error: Exception) {
                logOscar(
                    "PLAYER_RESOLVE_FAILED host=$iframeHost exception=" +
                        "${error::class.java.simpleName} message=${error.safeLogMessageForLog()}",
                )
                return@forEach
            }

            val finalUrl = finalResponse.url.trim()
            val mediaType = resolveOscarFinalMediaType(
                finalUrl = finalUrl,
                contentType = finalResponse.headers["Content-Type"],
            )
            if (finalResponse.code !in 200..299 || mediaType == null || finalUrl.isBlank()) {
                logOscar(
                    "PLAYER_RESOLVE_FAILED host=$iframeHost HTTP=${finalResponse.code} " +
                        "content_type=${finalResponse.headers["Content-Type"].orEmpty().safeLogValue()}",
                )
                return@forEach
            }

            logOscar(
                "FINAL_MEDIA_FOUND host=${finalUrl.toOscarHost().orEmpty().ifBlank { "<unknown>" }} " +
                    "type=${mediaType.name} quality=$quality",
            )
            val extractorType = when (mediaType) {
                OscarMediaType.M3U8 -> ExtractorLinkType.M3U8
                OscarMediaType.VIDEO -> ExtractorLinkType.VIDEO
            }
            emitted = emit(
                newExtractorLink(
                    source = "OscarTV",
                    name = link.serverName?.takeIf { it.isNotBlank() } ?: "OscarTV",
                    url = finalUrl,
                    type = extractorType,
                ) {
                    this.headers = mediaHeaders
                    this.quality = quality
                },
            ) || emitted
            logOscar(
                "WRAPPER_RESOLVED wrapper_host=$wrapperHost player_host=$iframeHost " +
                    "method=http_redirect",
            )
        }

        return emitted
    }
}

internal fun String.toOscarHost(): String? {
    return runCatching { java.net.URI(this).host?.lowercase() }.getOrNull()
}

private fun String.isOscarObviousAdvertisement(): Boolean {
    val normalized = lowercase()
    return normalized.contains("/ads/") || normalized.contains("/advert") ||
        normalized.contains("ads.") || normalized.contains("adserver.")
}
