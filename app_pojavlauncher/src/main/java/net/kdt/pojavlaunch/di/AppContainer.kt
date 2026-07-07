package net.kdt.pojavlaunch.di

import android.content.Context

/**
 * Minimal manual service-locator for the launcher shell. Built once in
 * PojavApplication.onCreate and reached via PojavApplication.appContainer.
 * No DI framework (no Hilt/KSP) by design.
 */
class AppContainer(context: Context) {
    val progressRepository: ProgressRepository = ProgressRepository()
    val preferencesRepository: PreferencesRepository = PreferencesRepository(context.applicationContext)
}
