package com.efisuby.abl.data.auth

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class KeystoreCipherTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "token-test")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun tokenSurvivesRoundTripThroughKeystoreAndIsNotPlaintext() {
        dir.mkdirs()
        val file = File(dir, "token.bin")
        val token = "y0_AgAAAAxxTestTokenValue1234567890"
        TokenStore(file, keystoreCipher("abl-test-token-key")).write(token)

        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(token))
        // A new cipher instance finds the same Keystore key.
        assertEquals(token, TokenStore(file, keystoreCipher("abl-test-token-key")).read())
    }
}
