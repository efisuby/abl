package com.efisuby.abl.data.settings

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: String? = null,
    val playbackSpeed: Float = DEFAULT_SPEED,
    val wifiOnly: Boolean = true,
    val lastSyncAt: Long? = null,
) {
    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        const val DEFAULT_SPEED = 1.0f
    }
}

/**
 * Keys that may travel to the cloud. Everything else stays on the device: "Wi-Fi only" and
 * storage-related preferences must not be imposed on another device by synchronization.
 */
object SyncableSettings {

    const val KEY_THEME = "theme"
    const val KEY_LANGUAGE = "language"
    const val KEY_PLAYBACK_SPEED = "playback_speed"

    val keys: Set<String> = setOf(KEY_THEME, KEY_LANGUAGE, KEY_PLAYBACK_SPEED)

    fun syncableOnly(values: Map<String, Any?>): Map<String, Any?> =
        values.filterKeys { it in keys }
}
