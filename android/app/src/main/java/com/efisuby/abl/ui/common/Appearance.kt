package com.efisuby.abl.ui.common

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.efisuby.abl.data.settings.AppLanguage
import com.efisuby.abl.data.settings.ThemeMode

/** Applies the two device-local look settings: theme and interface language. */
object Appearance {

    fun apply(mode: ThemeMode) {
        val night = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        // Setting the same mode again would recreate the open screens for nothing.
        if (AppCompatDelegate.getDefaultNightMode() != night) AppCompatDelegate.setDefaultNightMode(night)
    }

    /**
     * The language held by the system (Android 13+, where its per-app language screen can change it
     * too) or by AppCompat (older versions, set from the stored choice at start).
     */
    fun currentLanguage(context: Context): AppLanguage {
        val first = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)
        } else {
            AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)
        }
        return AppLanguage.entries.firstOrNull { it.tag != null && it.tag == first?.language } ?: AppLanguage.SYSTEM
    }

    /**
     * On Android 13+ the system's own manager is used: AppCompat forwards to it only while an
     * activity is alive, and the language must also be settable at process start and in tests.
     */
    fun apply(context: Context, language: AppLanguage) {
        if (currentLanguage(context) == language) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        } else {
            AppCompatDelegate.setApplicationLocales(
                language.tag?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList(),
            )
        }
    }
}
