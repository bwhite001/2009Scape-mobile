package net.kdt.pojavlaunch.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal manual service-locator for the launcher shell. Built once in
 * PojavApplication.onCreate and reached via PojavApplication.appContainer.
 * No DI framework (no Hilt/KSP) by design.
 */
class AppContainer(context: Context) {
    /**
     * Application-lifetime coroutine scope for shell background work.
     *
     * Note (2e): AsyncAssetManager retains its ThreadPoolExecutor deliberately.
     * It is the load-bearing first-run unpack path and already feeds reactive
     * progress via ProgressKeeper -> ProgressRepository, so the UI is already
     * coroutine/StateFlow-driven. Converting the executor itself is deferred to
     * avoid risking the unpack flow without on-device verification.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val progressRepository: ProgressRepository = ProgressRepository()
    val preferencesRepository: PreferencesRepository = PreferencesRepository(context.applicationContext)
}
