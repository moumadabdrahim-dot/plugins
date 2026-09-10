package com.oscartv

import kotlin.test.assertEquals
import org.junit.Test

class OscarIronSignerTest {
    @Test
    fun keyDerivationIsDeterministic() {
        assertEquals(
            "9ef3a33307a80297903ef62dfd7b840e8a558405a1acc875c5a24ac664781ae3",
            OscarIronSigner.derivedKeyForTests().toLowerHex(),
        )
    }

    @Test
    fun signatureExcludesQueryString() {
        val expected = OscarIronSigner.signature("/api/anime/", 1_700_000_000L, "01020304")
        assertEquals(
            expected,
            OscarIronSigner.signature("https://ostvapp.cam/api/anime/?page=1&limit=20", 1_700_000_000L, "01020304"),
        )
        assertEquals(
            "b435b80abdedc7ebaaa6e61372623f9478ad7d61fad03758a7e30fdce76cad6e",
            expected,
        )
    }

    @Test
    fun nonceIsFourBytesAsLowercaseHex() {
        assertEquals("00010aff", OscarIronSigner.nonceForTests(byteArrayOf(0, 1, 10, -1)))
    }
}
