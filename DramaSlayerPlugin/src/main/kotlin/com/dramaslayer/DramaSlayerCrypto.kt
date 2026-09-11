package com.dramaslayer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object DramaSlayerCrypto {
    private const val VERSION = 3
    private const val PASSWORD_OPTIONS = 1
    private const val SALT_SIZE = 8
    private const val IV_SIZE = 16
    private const val HMAC_SIZE = 32
    private const val PBKDF2_ITERATIONS = 10_000
    private const val KEY_SIZE_BITS = 256

    // This is the verified application password used by the service's API.
    // It is intentionally isolated here and is never written to logs.
    private const val API_PASSWORD = "9>E>VBa=X%;[5BX~=Q~K"

    fun decrypt(response: RawResponse): String = decrypt(response.result, API_PASSWORD)

    internal fun decrypt(response: RawResponse, password: String): String = decrypt(response.result, password)

    internal fun decrypt(result: String, password: String = API_PASSWORD): String {
        val encoded = result.trim()
        require(encoded.isNotEmpty()) { "Empty RNCryptor result" }

        val raw = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid RNCryptor base64", error)
        }
        val minimumSize = 2 + SALT_SIZE + SALT_SIZE + IV_SIZE + HMAC_SIZE + 16
        require(raw.size >= minimumSize) { "RNCryptor payload is too short" }
        require(raw[0].toInt() and 0xff == VERSION) { "Unsupported RNCryptor version" }
        require(raw[1].toInt() and 0xff == PASSWORD_OPTIONS) { "Unsupported RNCryptor options" }

        val encryptionSalt = raw.copyOfRange(2, 2 + SALT_SIZE)
        val hmacSalt = raw.copyOfRange(2 + SALT_SIZE, 2 + SALT_SIZE + SALT_SIZE)
        val ivStart = 2 + SALT_SIZE + SALT_SIZE
        val iv = raw.copyOfRange(ivStart, ivStart + IV_SIZE)
        val ciphertextStart = ivStart + IV_SIZE
        val ciphertextEnd = raw.size - HMAC_SIZE
        val ciphertext = raw.copyOfRange(ciphertextStart, ciphertextEnd)
        val expectedHmac = raw.copyOfRange(ciphertextEnd, raw.size)
        require(ciphertext.isNotEmpty() && ciphertext.size % 16 == 0) {
            "Invalid RNCryptor ciphertext length"
        }

        val encryptionKey = deriveKey(password, encryptionSalt)
        val hmacKey = deriveKey(password, hmacSalt)
        val actualHmac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(hmacKey, "HmacSHA256"))
            doFinal(raw.copyOfRange(0, ciphertextEnd))
        }
        require(MessageDigest.isEqual(actualHmac, expectedHmac)) {
            "RNCryptor HMAC verification failed"
        }

        val plaintext = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
            doFinal(ciphertext)
        }
        return plaintext.toString(StandardCharsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
