package com.dramaslayer

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

interface DramaSlayerServerResolver {
    suspend fun resolve(
        streamer: EpisodeStreamer,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean
}

internal class DramaSlayerLinkResolver(
    private val api: DramaSlayerApi,
    private val sourceName: String,
    private val futureResolvers: List<DramaSlayerServerResolver> = emptyList(),
) {
    suspend fun resolve(
        streamers: List<EpisodeStreamer>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        val emitted = hashSetOf<String>()

        fun emit(link: ExtractorLink) {
            val key = "${link.url}|${link.quality}|${link.type}"
            if (emitted.add(key)) {
                callback(link)
                found = true
            }
        }

        streamers.forEach { streamer ->
            val url = streamer.url?.trim().orEmpty()
            if (url.isBlank()) return@forEach
            val server = streamer.serverName?.trim().orEmpty()

            try {
                if (server.equals("CDN", true) && isVerifiedCdn(url)) {
                    emit(
                        newExtractorLink(
                            source = sourceName,
                            name = "Drama Slayer CDN",
                            url = url,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            quality = Qualities.Unknown.value
                        },
                    )
                    return@forEach
                }

                if (server.equals("multi", true) || isFwUrl(url)) {
                    api.expandFw(url).forEach { externalUrl ->
                        val normalized = normalizeExternalUrl(externalUrl) ?: return@forEach
                        var extractorEmitted = false
                        runCatching {
                            loadExtractor(normalized, DramaSlayerConfig.apiBase, subtitleCallback) {
                                extractorEmitted = true
                                emit(it)
                            }
                        }.onFailure {
                            logDramaSlayer(
                                "EXTRACTOR_FAILED host=${hostOf(normalized)} " +
                                    "exception=${it::class.java.simpleName}",
                            )
                        }
                        if (!extractorEmitted) {
                            logDramaSlayer("EXTRACTOR_EMPTY host=${hostOf(normalized)}")
                        }
                    }
                    return@forEach
                }

                // Reserved extension point for a future JS/native resolver. v1 does not
                // fetch olDrama.js because verified CDN and /fw links are sufficient.
                futureResolvers.forEach { resolver ->
                    found = runCatching {
                        resolver.resolve(streamer, subtitleCallback, callback)
                    }.getOrElse {
                        logDramaSlayer("FUTURE_RESOLVER_FAILED exception=${it::class.java.simpleName}")
                        false
                    } || found
                }
            } catch (error: Exception) {
                logDramaSlayer(
                    "STREAMER_FAILED server=${server.ifBlank { "unknown" }} " +
                        "exception=${error::class.java.simpleName}",
                )
            }
        }
        return found
    }

    private fun isVerifiedCdn(url: String): Boolean = hostOf(url).equals("s.drslayer.com", true)

    private fun isFwUrl(url: String): Boolean {
        return hostOf(url).equals("drslayer.com", true) &&
            runCatching { URI(url).path.endsWith("/servers/public/api/fw") }.getOrDefault(false)
    }

    private fun normalizeExternalUrl(url: String): String? {
        val value = url.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return null
        return if (
            value.startsWith("http://ok.ru/", true) ||
            value.startsWith("http://www.ok.ru/", true)
        ) {
            "https" + value.substring(4)
        } else {
            value
        }
    }

    private fun hostOf(url: String): String {
        return runCatching { URI(url).host.orEmpty() }.getOrDefault("")
    }
}
