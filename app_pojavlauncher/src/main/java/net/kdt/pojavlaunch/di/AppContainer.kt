package net.kdt.pojavlaunch.di

/**
 * Minimal manual service-locator for the launcher shell. Built once in
 * PojavApplication.onCreate and reached via PojavApplication.appContainer.
 * No DI framework (no Hilt/KSP) by design.
 */
class AppContainer {
    val progressRepository: ProgressRepository = ProgressRepository()
}
