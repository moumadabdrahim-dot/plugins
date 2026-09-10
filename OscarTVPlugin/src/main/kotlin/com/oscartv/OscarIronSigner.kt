package com.oscartv

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OscarTV's request guard. The key is derived from the APK constants and every
 * signature covers only the URL path, never its query string.
 */
internal object OscarIronSigner {
    private const val certificateSha256 =
        "6e2fcda8631eb49ebcba4ca8ef4c597abe84654c7d3e8096db32bdd21ecf763f"
    private const val packageName = "com.drama.mp4"
    private const val diagnostic = "f=6e2fcda8 p=6e2fcda8 h=0"
    private const val userAgent = "okhttp/4.12.0"

    private val random = SecureRandom()
    private val key: ByteArray by lazy { deriveKey() }

    fun headersForUrl(url: String): Map<String, String> {
        val timestamp = System.currentTimeMillis() / 1000L
        val nonce = randomNonce()
        return mapOf(
            "User-Agent" to userAgent,
            "X-Iron-Sig" to signature(url, timestamp, nonce),
            "X-Iron-Ts" to timestamp.toString(),
            "X-Iron-Nonce" to nonce,
            "X-Iron-Diag" to diagnostic,
        )
    }

    fun signature(urlOrPath: String, timestamp: Long, nonce: String): String {
        val payload = "${signedPath(urlOrPath)}|$timestamp|$nonce"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).toLowerHex()
    }

    fun signedPath(urlOrPath: String): String {
        return runCatching {
            URI(urlOrPath).rawPath.takeUnless { it.isNullOrBlank() } ?: "/"
        }.getOrElse {
            urlOrPath.substringBefore('?').ifBlank { "/" }
        }
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(4)
        random.nextBytes(bytes)
        return bytes.toLowerHex()
    }

    private fun deriveKey(): ByteArray {
        var value = ("$certificateSha256|$packageName").toByteArray(Charsets.UTF_8)
        value = xorRepeated(value, "OscarTVIronGuard".toByteArray(Charsets.UTF_8).copyOf(7))
        value.reverse()
        value = xorRepeated(value, "IronGuard".toByteArray(Charsets.UTF_8))
        repeat(3) {
            value = MessageDigest.getInstance("SHA-256").digest(value)
        }
        return value
    }

    private fun xorRepeated(value: ByteArray, mask: ByteArray): ByteArray {
        return value.mapIndexed { index, byte ->
            (byte.toInt() xor mask[index % mask.size].toInt()).toByte()
        }.toByteArray()
    }

    // Kept for deterministic unit tests; production code never logs this value.
    internal fun derivedKeyForTests(): ByteArray = key.copyOf()
    internal fun nonceForTests(bytes: ByteArray): String = bytes.toLowerHex()
}

internal fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
        val value = byte.toInt() and 0xff
        append("0123456789abcdef"[value ushr 4])
        append("0123456789abcdef"[value and 0x0f])
    }
}
