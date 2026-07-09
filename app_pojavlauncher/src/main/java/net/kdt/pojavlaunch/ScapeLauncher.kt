package net.kdt.pojavlaunch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import net.kdt.pojavlaunch.ui.rs.RsButton
import net.kdt.pojavlaunch.ui.rs.RsLink
import net.kdt.pojavlaunch.ui.rs.RsPanel
import net.kdt.pojavlaunch.ui.rs.ScapeLogo
import net.kdt.pojavlaunch.ui.theme.LauncherTheme
import net.kdt.pojavlaunch.ui.theme.RsColors

/**
 * Launcher home screen (Compose). Play HD -> MainActivity (GL), Play SD ->
 * JavaGUILauncherActivity (AWT) — launch intents are unchanged. Progress is
 * observed from ProgressRepository (StateFlow mirror of ProgressKeeper).
 */
class ScapeLauncher : BaseActivity() {
    private var progressServiceKeeper: ProgressServiceKeeper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // API 33+ won't show the ProgressService notification without this runtime grant.
        // Fire-and-forget: the foreground service still runs if the user declines.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_POST_NOTIFICATIONS)
        }

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

    companion object {
        private const val RC_POST_NOTIFICATIONS = 1002
    }
}

@Composable
private fun HomeScreen(
    progress: ProgressUiState,
    onPlayHd: () -> Unit,
    onPlaySd: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = RsColors.bgDeep, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RsPanel(modifier = Modifier.widthIn(max = 420.dp)) {
                ScapeLogo()
                Spacer(Modifier.height(20.dp))
                RsButton("Play HD", onClick = onPlayHd)
                Spacer(Modifier.height(8.dp))
                RsButton("Play SD", onClick = onPlaySd)
                Spacer(Modifier.height(10.dp))
                RsLink("Settings", onClick = onSettings)
                if (progress.isBusy) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = RsColors.borderLight,
                        trackColor = RsColors.borderDark,
                    )
                    if (progress.messageResId != 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(text = stringResource(progress.messageResId), color = RsColors.textMuted)
                    }
                }
            }
        }
    }
}
