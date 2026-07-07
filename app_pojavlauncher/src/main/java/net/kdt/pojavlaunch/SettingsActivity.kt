package net.kdt.pojavlaunch

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kdt.pojavlaunch.customcontrols.CustomControlsActivity
import net.kdt.pojavlaunch.di.PreferencesRepository
import net.kdt.pojavlaunch.ui.theme.LauncherTheme

private data class BoolPref(val key: String, val label: String, val def: Boolean)
private data class IntPref(val key: String, val label: String, val def: Int, val range: IntRange)

// Simple, safe-to-model preferences (exact SharedPreferences keys preserved).
// GL-critical / bespoke prefs (renderer, alternate_surface, defaultRuntime,
// gamepad remap, plugins, file import) stay in the legacy "Advanced" dialog.
private val VIDEO_BOOLS = listOf(
    BoolPref("ignoreNotch", "Ignore notch", false),
    BoolPref("sustainedPerformance", "Sustained performance mode", false),
    BoolPref("force_vsync", "Force VSync", false),
)
private val VIDEO_INTS = listOf(
    IntPref("resolutionRatio", "Resolution scale", 60, 25..100),
    IntPref("xinset", "Horizontal inset", 0, 0..100),
)
private val CONTROL_BOOLS = listOf(
    BoolPref("disableGestures", "Disable gestures", false),
    BoolPref("disableDoubleTap", "Disable double-tap to swap hands", false),
    BoolPref("mouse_start", "Start with virtual mouse enabled", false),
    BoolPref("buttonAllCaps", "Uppercase button labels", true),
    BoolPref("enableGyro", "Enable gyro aiming", false),
    BoolPref("gyroSmoothing", "Gyro smoothing", true),
    BoolPref("gyroInvertX", "Invert gyro X", false),
    BoolPref("gyroInvertY", "Invert gyro Y", false),
)
private val CONTROL_INTS = listOf(
    IntPref("buttonscale", "Button size", 100, 25..500),
    IntPref("mousescale", "Mouse pointer size", 100, 25..300),
    IntPref("mousespeed", "Mouse speed", 100, 25..300),
    IntPref("timeLongPressTrigger", "Long-press delay (ms)", 300, 100..1000),
    IntPref("gyroSensitivity", "Gyro sensitivity", 100, 10..300),
    IntPref("gyroSampleRate", "Gyro sample rate (ms)", 16, 5..50),
    IntPref("gamepad_deadzone_scale", "Gamepad deadzone", 100, 0..200),
)
private val JAVA_BOOLS = listOf(
    BoolPref("java_sandbox", "Java sandbox", true),
)
private val JAVA_INTS = listOf(
    IntPref("allocation", "RAM allocation (MB)", 256, 256..1024),
)
private val MISC_BOOLS = listOf(
    BoolPref("checkLibraries", "Verify library integrity", true),
    BoolPref("arc_capes", "Arc capes", false),
)
private val EXPERIMENTAL_BOOLS = listOf(
    BoolPref("dump_shaders", "Dump shaders", false),
    BoolPref("bigCoreAffinity", "Big-core affinity", false),
)

/** Compose settings for the safe majority of preferences; advanced/GL-critical ones open the legacy dialog. */
class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = PojavApplication.appContainer.preferencesRepository
        setContent {
            LauncherTheme {
                SettingsScreen(
                    repo = repo,
                    onBack = { finish() },
                    onOpenControls = { startActivity(Intent(this, CustomControlsActivity::class.java)) },
                    onOpenAdvanced = { MyDialogFragment().show(supportFragmentManager, "advanced") },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    repo: PreferencesRepository,
    onBack: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                    Text("Settings", style = MaterialTheme.typography.headlineSmall)
                }
            }
            prefSection("Video", VIDEO_BOOLS, VIDEO_INTS, repo)
            prefSection("Controls", CONTROL_BOOLS, CONTROL_INTS, repo)
            item { TextButton(onClick = onOpenControls) { Text("Edit on-screen controls…") } }
            prefSection("Java", JAVA_BOOLS, JAVA_INTS, repo)
            item { JavaArgsRow(repo) }
            prefSection("Misc", MISC_BOOLS, emptyList(), repo)
            prefSection("Experimental", EXPERIMENTAL_BOOLS, emptyList(), repo)
            item {
                Spacer(Modifier.height(16.dp))
                Text("Advanced", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Renderer, runtime, plugins, and imports")
                TextButton(onClick = onOpenAdvanced) { Text("Open advanced settings…") }
            }
        }
    }
}

private fun LazyListScope.prefSection(
    title: String,
    bools: List<BoolPref>,
    ints: List<IntPref>,
    repo: PreferencesRepository,
) {
    item {
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    items(bools) { BoolRow(it, repo) }
    items(ints) { IntRow(it, repo) }
}

@Composable
private fun BoolRow(pref: BoolPref, repo: PreferencesRepository) {
    var checked by remember { mutableStateOf(repo.getBoolean(pref.key, pref.def)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pref.label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = {
            checked = it
            repo.putBoolean(pref.key, it)
        })
    }
}

@Composable
private fun IntRow(pref: IntPref, repo: PreferencesRepository) {
    var value by remember { mutableIntStateOf(repo.getInt(pref.key, pref.def)) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("${pref.label}: $value")
        Slider(
            value = value.toFloat(),
            onValueChange = { value = it.toInt() },
            onValueChangeFinished = { repo.putInt(pref.key, value) },
            valueRange = pref.range.first.toFloat()..pref.range.last.toFloat(),
        )
    }
}

@Composable
private fun JavaArgsRow(repo: PreferencesRepository) {
    var text by remember { mutableStateOf(repo.getString("javaArgs", "") ?: "") }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Custom Java arguments")
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                repo.putString("javaArgs", it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}
