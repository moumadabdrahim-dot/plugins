package com.oscartv

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class OscarSeriesMp4ResolverTest {
    @Test
    fun extractsAbsoluteIframeUrl() {
        val result = extractOscarIframeUrls(
            "<iframe src=\"https://downloader.disk.yandex.ru/disk/abc\"></iframe>",
            "https://cdn.seriesmp4.com/video.mp4",
        )
        assertEquals(listOf("https://downloader.disk.yandex.ru/disk/abc"), result)
    }

    @Test
    fun normalizesProtocolRelativeIframeUrl() {
        val result = extractOscarIframeUrls(
            "<iframe src=\"//downloader.disk.yandex.ru/disk/abc\"></iframe>",
            "https://cdn.seriesmp4.com/video.mp4",
        )
        assertEquals(listOf("https://downloader.disk.yandex.ru/disk/abc"), result)
    }

    @Test
    fun normalizesRelativeIframeUrl() {
        val result = extractOscarIframeUrls(
            "<iframe src=\"/player/abc\"></iframe>",
            "https://cdn.seriesmp4.com/path/video.mp4",
        )
        assertEquals(listOf("https://cdn.seriesmp4.com/player/abc"), result)
    }

    @Test
    fun ignoresMissingIframeAndKeepsDistinctMultipleIframes() {
        assertEquals(emptyList(), extractOscarIframeUrls("<html></html>", "https://example.com/video"))

        val result = extractOscarIframeUrls(
            """
            <iframe src="https://ads.example/ad"></iframe>
            <iframe src="//downloader.disk.yandex.ru/disk/abc"></iframe>
            <iframe src="https://downloader.disk.yandex.ru/disk/abc"></iframe>
            """.trimIndent(),
            "https://cdn.seriesmp4.com/video.mp4",
        )
        assertEquals(
            listOf(
                "https://ads.example/ad",
                "https://downloader.disk.yandex.ru/disk/abc",
            ),
            result,
        )
        assertEquals(
            listOf("https://downloader.disk.yandex.ru/disk/abc"),
            selectOscarPlayerIframeUrls(result),
        )
    }

    @Test
    fun seriesMp4IsAlwaysClassifiedAsWrapperEvenWithMp4Extension() {
        val link = OscarWatchLink(
            serverName = "seriesmp4",
            url = "https://cdn.seriesmp4.com/video.mp4",
            quality = "480p",
            type = "direct",
        )
        assertEquals(OscarPlaybackKind.WRAPPER, link.playbackKind())
    }

    @Test
    fun verifiedDirectHostKeepsDirectFallbackBehavior() {
        val link = OscarWatchLink(
            serverName = "direct",
            url = "https://video.example.com/video.mp4",
            quality = "480p",
            type = "direct",
        )
        assertEquals(OscarPlaybackKind.DIRECT, link.playbackKind())
    }

    @Test
    fun onlyProvenYandexPlayerHostsUseHttpFallback() {
        assertEquals(
            true,
            "https://downloader.disk.yandex.ru/disk/abc".isOscarYandexPlayerUrl(),
        )
        assertEquals(false, "https://player.example.com/video.mp4".isOscarYandexPlayerUrl())
    }

    @Test
    fun htmlIsNeverAcceptedAsFinalMedia() {
        assertNull(
            resolveOscarFinalMediaType(
                "https://cdn.seriesmp4.com/video.mp4",
                "text/html; charset=UTF-8",
            ),
        )
        assertNull(
            resolveOscarFinalMediaType(
                "https://cdn.seriesmp4.com/video.m3u8",
                "text/html; charset=UTF-8",
            ),
        )
        assertEquals(
            OscarMediaType.VIDEO,
            resolveOscarFinalMediaType("https://storage.yandex.net/file", "video/mp4"),
        )
        assertEquals(
            OscarMediaType.M3U8,
            resolveOscarFinalMediaType(
                "https://storage.yandex.net/file",
                "application/vnd.apple.mpegurl",
            ),
        )
    }

    @Test
    fun finalMediaDeduplicationUsesUrlAndQuality() {
        val candidates = listOf(
            "https://storage.yandex.net/a.mp4" to 480,
            "https://storage.yandex.net/a.mp4" to 480,
            "https://storage.yandex.net/a.mp4" to 720,
        )
        val deduped = candidates.distinctBy { (url, quality) -> oscarMediaDedupKey(url, quality) }
        assertEquals(
            listOf(
                "https://storage.yandex.net/a.mp4" to 480,
                "https://storage.yandex.net/a.mp4" to 720,
            ),
            deduped,
        )
    }
}
