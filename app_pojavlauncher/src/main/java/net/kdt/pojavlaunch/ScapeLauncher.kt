package net.kdt.pojavlaunch

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kdt.pojavlaunch.di.ProgressUiState
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.services.ProgressServiceKeeper
import net.kdt.pojavlaunch.ui.theme.LauncherTheme

/**
 * Launcher home screen (Compose). Play HD -> MainActivity (GL), Play SD ->
 * JavaGUILauncherActivity (AWT) — launch intents are unchanged. Progress is
 * observed from ProgressRepository (StateFlow mirror of ProgressKeeper).
 */
class ScapeLauncher : BaseActivity() {
    private var progressServiceKeeper: ProgressServiceKeeper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val keeper = ProgressServiceKeeper(this)
        progressServiceKeeper = keeper
        ProgressKeeper.addTaskCountListener(keeper)

        val progressRepo = PojavApplication.appContainer.progressRepository
        setContent {
            val state by progressRepo.state.collectAsStateWithLifecycle()
            LauncherTheme {
                HomeScreen(
                    progress = state,
                    onPlayHd = { launchIfReady(state) { startActivity(Intent(this, MainActivity::class.java)) } },
                    onPlaySd = { launchIfReady(state) { startActivity(Intent(this, JavaGUILauncherActivity::class.java)) } },
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }

    private inline fun launchIfReady(state: ProgressUiState, action: () -> Unit) {
        if (state.isBusy) {
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show()
        } else {
            action()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressServiceKeeper?.let { ProgressKeeper.removeTaskCountListener(it) }
    }
}

@Composable
private fun HomeScreen(
    progress: ProgressUiState,
    onPlayHd: () -> Unit,
    onPlaySd: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "2009Scape", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPlayHd, modifier = Modifier.width(220.dp)) { Text("Play HD") }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onPlaySd, modifier = Modifier.width(220.dp)) { Text("Play SD") }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onSettings) { Text("Settings") }
            if (progress.isBusy) {
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(modifier = Modifier.width(220.dp))
                if (progress.messageResId != 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = stringResource(progress.messageResId))
                }
            }
        }
    }
}
