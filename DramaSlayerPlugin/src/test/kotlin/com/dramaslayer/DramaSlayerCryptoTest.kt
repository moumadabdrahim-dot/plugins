package com.dramaslayer

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class DramaSlayerCryptoTest {
    @Test
    fun decryptsKnownRncryptorV3FixtureAndVerifiesHmac() {
        val encrypted = "AwEBAgMEBQYHCBESExQVFhcYISIjJCUmJygpKissLS4vMFYfUJCVPaOmnWBxL5wsqEwLSbARFOCoLRsCEAXGryYMuYESzzbaB3ZnH1QxSQOEAXGNh35ru4ROMr/0wVodwVA="
        assertEquals(
            "{\"ok\":true,\"message\":\"fixture\"}",
            DramaSlayerCrypto.decrypt(RawResponse(encrypted), "fixture-password"),
        )
    }

    @Test
    fun rejectsWrongPasswordBeforeDecrypting() {
        val encrypted = "AwEBAgMEBQYHCBESExQVFhcYISIjJCUmJygpKissLS4vMFYfUJCVPaOmnWBxL5wsqEwLSbARFOCoLRsCEAXGryYMuYESzzbaB3ZnH1QxSQOEAXGNh35ru4ROMr/0wVodwVA="
        assertFailsWith<IllegalArgumentException> {
            DramaSlayerCrypto.decrypt(encrypted, "wrong-password")
        }
    }
}
