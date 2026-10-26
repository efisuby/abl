package com.efisuby.abl

import android.app.Application
import android.content.Context
import android.os.Build
import com.efisuby.abl.di.AppContainer
import com.efisuby.abl.ui.common.Appearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AblApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // The first screen must already be inflated in the chosen theme, so the stored value is read
        // before it (a small DataStore file); later changes are applied as they come.
        val themes = container.settingsRepository.settings.map { it.themeMode }.distinctUntilChanged()
        val stored = runBlocking { container.settingsRepository.settings.first() }
        Appearance.apply(stored.themeMode)
        // Android 13+ keeps the language itself (and its system screen may change it); older ones need it set.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Appearance.apply(this, stored.language)
        container.applicationScope.launch(Dispatchers.Main.immediate) { themes.collect(Appearance::apply) }
    }
}

/** Convenience accessor for screens and services. */
val Context.appContainer: AppContainer
    get() = (applicationContext as AblApplication).container
