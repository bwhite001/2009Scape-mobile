package net.kdt.pojavlaunch.di

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import net.kdt.pojavlaunch.prefs.LauncherPreferences

/**
 * Compose-facing wrapper over the app's default SharedPreferences — the SAME
 * store LauncherPreferences reads. Preserves exact keys/semantics; the :game
 * process keeps reading via LauncherPreferences static fields. Writes go here,
 * and reloadLauncherPreferences() refreshes those statics so the launch path
 * sees the new values. DataStore is intentionally NOT used (cross-process reads).
 */
class PreferencesRepository(private val context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    fun getString(key: String, def: String?): String? = prefs.getString(key, def)

    fun putBoolean(key: String, value: Boolean) = commit { putBoolean(key, value) }
    fun putInt(key: String, value: Int) = commit { putInt(key, value) }
    fun putString(key: String, value: String?) = commit { putString(key, value) }

    private inline fun commit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    /** Keep the legacy static fields (read by the launch path) in sync after a write. */
    fun reloadLauncherPreferences() {
        LauncherPreferences.loadPreferences(context)
    }

    /** Emits the changed preference key whenever any preference changes. */
    val changes: Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) trySendBlocking(key)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
