package com.efisuby.abl.di

import android.content.Context
import com.efisuby.abl.data.db.AblDatabase
import com.efisuby.abl.data.repository.DeviceIdProvider
import com.efisuby.abl.data.repository.RoomLibraryRepository
import com.efisuby.abl.data.repository.RoomProgressRepository
import com.efisuby.abl.data.settings.SettingsRepository
import com.efisuby.abl.data.settings.settingsDataStore
import com.efisuby.abl.domain.repository.LibraryRepository
import com.efisuby.abl.domain.repository.ProgressRepository

/**
 * Manual dependency container. Created lazily by [com.efisuby.abl.AblApplication] so that the
 * first screen decides when the database is opened.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AblDatabase by lazy { AblDatabase.create(appContext) }

    val deviceIdProvider: DeviceIdProvider = DeviceIdProvider(appContext)

    val libraryRepository: LibraryRepository by lazy { RoomLibraryRepository(database) }

    val progressRepository: ProgressRepository by lazy {
        RoomProgressRepository(database, deviceIdProvider)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext.settingsDataStore)
    }
}
