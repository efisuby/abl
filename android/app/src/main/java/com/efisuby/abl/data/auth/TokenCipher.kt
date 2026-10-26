package com.efisuby.abl.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts small secrets; the output of [encrypt] is self-contained. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** Throws if the data is corrupt or was produced with another key. */
    fun decrypt(encrypted: ByteArray): ByteArray
}

/** AES/GCM with a random IV prepended to the ciphertext. The key comes from [keyProvider]. */
class AesGcmCipher(private val keyProvider: () -> SecretKey) : TokenCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > IV_SIZE) { "Encrypted data is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider(),
            GCMParameterSpec(TAG_BITS, encrypted, 0, IV_SIZE),
        )
        return cipher.doFinal(encrypted, IV_SIZE, encrypted.size - IV_SIZE)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

/** An AES key that lives in the Android Keystore and is created on first use. */
fun keystoreCipher(alias: String): TokenCipher = AesGcmCipher { keystoreKey(alias) }

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

@Synchronized
private fun keystoreKey(alias: String): SecretKey {
    val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build(),
    )
    return generator.generateKey()
}
