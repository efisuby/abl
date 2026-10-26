package com.efisuby.abl

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.efisuby.abl.di.AppContainer

class AblApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Dark-only and Russian-only (app-shell spec). Forcing the night configuration, not just a
        // dark theme, keeps the system bar icons light under edge-to-edge.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        forceRussian()
    }

    /**
     * Library strings (Material, Media3 notification actions) follow the app locale, so it is
     * set to Russian instead of relying on `values/` being Russian. On Android 13+ the system
     * keeps it, so it is only written when it differs.
     */
    private fun forceRussian() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = getSystemService(LocaleManager::class.java)
            if (manager.applicationLocales.toLanguageTags() != LANGUAGE) {
                manager.applicationLocales = LocaleList.forLanguageTags(LANGUAGE)
            }
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(LANGUAGE))
        }
    }

    private companion object {
        const val LANGUAGE = "ru"
    }
}

/** Convenience accessor for screens and services. */
val Context.appContainer: AppContainer
    get() = (applicationContext as AblApplication).container
