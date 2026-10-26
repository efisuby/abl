package com.efisuby.abl.data.settings

import kotlin.math.roundToInt

/** How the app picks light or dark; device-local, never synchronized. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The interface language; [SYSTEM] follows the device (Russian unless it is English). */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    RUSSIAN("ru"),
    ENGLISH("en"),
}

data class AppSettings(
    val playbackSpeed: Float = DEFAULT_SPEED,
    val wifiOnly: Boolean = true,
    val lastSyncAt: Long? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
) {
    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        const val DEFAULT_SPEED = 1.0f
        const val SPEED_STEP = 0.1f

        /** Clamps to [MIN_SPEED]..[MAX_SPEED] and rounds to [SPEED_STEP] (playback spec). */
        fun normalizeSpeed(speed: Float): Float {
            if (speed.isNaN()) return DEFAULT_SPEED
            val steps = (speed.coerceIn(MIN_SPEED, MAX_SPEED) / SPEED_STEP).roundToInt()
            return steps / 10f
        }
    }
}

/**
 * Keys that may travel to the cloud. Everything else stays on the device: "Wi-Fi only" and
 * storage-related preferences must not be imposed on another device by synchronization.
 */
object SyncableSettings {

    const val KEY_PLAYBACK_SPEED = "playback_speed"

    val keys: Set<String> = setOf(KEY_PLAYBACK_SPEED)

    fun syncableOnly(values: Map<String, Any?>): Map<String, Any?> =
        values.filterKeys { it in keys }
}
