package com.efisuby.abl.data.auth

import java.io.File

/** The OAuth token in a private file, encrypted by [cipher]; never in DataStore, logs or backups. */
class TokenStore(private val file: File, private val cipher: TokenCipher) {

    @Synchronized
    fun write(token: String) {
        val part = File(file.parentFile, file.name + ".part")
        part.writeBytes(cipher.encrypt(token.toByteArray(Charsets.UTF_8)))
        if (!part.renameTo(file)) {
            part.delete()
            error("Cannot save the token")
        }
    }

    /** Null if there is no token or it cannot be decrypted (the key is gone, the file is damaged). */
    @Synchronized
    fun read(): String? {
        if (!file.isFile) return null
        return try {
            String(cipher.decrypt(file.readBytes()), Charsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
