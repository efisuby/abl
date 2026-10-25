package com.efisuby.abl.data.repository

import android.content.Context
import java.util.UUID

/**
 * Stable per-install identifier used to keep each device's synchronization files apart.
 * Not a secret, so plain SharedPreferences is enough; DataStore is reserved for user settings.
 */
class DeviceIdProvider(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)

    @Synchronized
    fun deviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
