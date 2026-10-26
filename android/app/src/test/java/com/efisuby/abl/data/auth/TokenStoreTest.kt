package com.efisuby.abl.data.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class TokenStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(key: SecretKey = newKey()) =
        TokenStore(folder.root.resolve("token.bin"), AesGcmCipher { key })

    @Test
    fun readsBackWhatWasWritten() {
        val store = store()
        store.write(TOKEN)
        assertEquals(TOKEN, store.read())
    }

    @Test
    fun fileHasNoPlaintextToken() {
        store().write(TOKEN)
        val bytes = folder.root.resolve("token.bin").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(TOKEN))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("y0_"))
    }

    @Test
    fun sameTokenEncryptsDifferentlyEachTime() {
        val key = newKey()
        val cipher = AesGcmCipher { key }
        val a = cipher.encrypt(TOKEN.toByteArray())
        val b = cipher.encrypt(TOKEN.toByteArray())
        assertFalse(a.contentEquals(b))
        assertArrayEquals(TOKEN.toByteArray(), cipher.decrypt(a))
    }

    @Test
    fun anotherKeyOrDamagedFileReadsAsNoToken() {
        store().write(TOKEN)
        assertNull(store().read()) // a different random key

        val key = newKey()
        val good = store(key)
        good.write(TOKEN)
        val file = folder.root.resolve("token.bin")
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertNull(good.read())
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(good.read())
    }

    @Test
    fun clearRemovesTheToken() {
        val store = store()
        store.write(TOKEN)
        store.clear()
        assertNull(store.read())
        assertFalse(folder.root.resolve("token.bin").exists())
    }

    @Test
    fun unauthorizedKeepsNoTokenAndAsksToSignInAgain() {
        val store = store()
        val auth = AuthRepository(store)
        assertEquals(AuthState.SIGNED_OUT, auth.state.value)
        auth.signIn(TOKEN)
        assertEquals(AuthState.SIGNED_IN, auth.state.value)
        assertEquals(TOKEN, auth.token())

        auth.onUnauthorized()
        assertEquals(AuthState.REAUTH_REQUIRED, auth.state.value)
        assertNull(auth.token())
        assertNull(store.read())

        auth.signIn(TOKEN)
        auth.signOut()
        assertEquals(AuthState.SIGNED_OUT, auth.state.value)
        assertNull(store.read())
    }

    @Test
    fun stateIsRestoredFromTheStore() {
        val key = newKey()
        val store = store(key)
        AuthRepository(store).signIn(TOKEN)
        assertTrue(AuthRepository(store).state.value == AuthState.SIGNED_IN)
    }

    private companion object {
        const val TOKEN = "y0_AgAAAAxxTestTokenValue1234567890"
    }
}
