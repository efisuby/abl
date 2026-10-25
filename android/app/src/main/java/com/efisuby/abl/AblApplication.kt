package com.efisuby.abl

import android.app.Application
import android.content.Context
import com.efisuby.abl.di.AppContainer

class AblApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}

/** Convenience accessor for screens and services. */
val Context.appContainer: AppContainer
    get() = (applicationContext as AblApplication).container
