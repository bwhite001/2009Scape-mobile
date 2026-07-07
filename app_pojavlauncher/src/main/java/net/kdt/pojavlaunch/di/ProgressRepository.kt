package net.kdt.pojavlaunch.di

import com.kdt.mcgui.ProgressLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.progresskeeper.ProgressListener
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener

/** UI-facing progress snapshot mirrored from the global ProgressKeeper. */
data class ProgressUiState(
    val taskCount: Int = 0,
    val progress: Int = 0,
    val messageResId: Int = 0,
    val args: List<Any?> = emptyList(),
) {
    val isBusy: Boolean get() = taskCount > 0
}

/**
 * Bridges the legacy static ProgressKeeper observer API to a StateFlow so
 * Compose can observe unpack/download progress. Non-invasive: ProgressKeeper
 * and ProgressLayout are unchanged and keep working during the migration.
 * Callbacks arrive on arbitrary threads; MutableStateFlow is thread-safe.
 */
class ProgressRepository {
    private val _state = MutableStateFlow(ProgressUiState())
    val state: StateFlow<ProgressUiState> = _state.asStateFlow()

    private val observedKeys = listOf(
        ProgressLayout.UNPACK_RUNTIME,
        ProgressLayout.EXTRACT_COMPONENTS,
        ProgressLayout.EXTRACT_SINGLE_FILES,
        ProgressLayout.INSTALL_MODPACK,
    )

    // Held to keep strong references for the process lifetime.
    private val taskCountListener = TaskCountListener { count ->
        _state.value = _state.value.copy(taskCount = count)
    }

    private val progressListener = object : ProgressListener {
        override fun onProgressStarted() {}
        override fun onProgressUpdated(progress: Int, resid: Int, vararg va: Any?) {
            _state.value = _state.value.copy(
                progress = progress,
                messageResId = resid,
                args = va.toList(),
            )
        }
        override fun onProgressEnded() {
            _state.value = _state.value.copy(progress = 0, messageResId = 0, args = emptyList())
        }
    }

    init {
        observedKeys.forEach { ProgressKeeper.addListener(it, progressListener) }
        ProgressKeeper.addTaskCountListener(taskCountListener, true)
    }
}
