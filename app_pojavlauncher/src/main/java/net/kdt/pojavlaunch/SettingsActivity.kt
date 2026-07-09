package net.kdt.pojavlaunch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.kdt.pojavlaunch.customcontrols.CustomControlsActivity
import net.kdt.pojavlaunch.di.PreferencesRepository
import net.kdt.pojavlaunch.ui.rs.RsBackButton
import net.kdt.pojavlaunch.ui.rs.RsButton
import net.kdt.pojavlaunch.ui.rs.RsHeaderBand
import net.kdt.pojavlaunch.ui.rs.RsSectionHeader
import net.kdt.pojavlaunch.ui.rs.RsSlider
import net.kdt.pojavlaunch.ui.rs.RsToggle
import net.kdt.pojavlaunch.ui.theme.LauncherTheme
import net.kdt.pojavlaunch.ui.theme.RsColors

private data class BoolPref(val key: String, val label: String, val def: Boolean)
private data class IntPref(val key: String, val label: String, val def: Int, val range: IntRange)
private data class StringPref(
    val key: String,
    val label: String,
    val def: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
)

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
    BoolPref("singleTapRightClick", "Single-tap opens right-click menu", false),
    BoolPref("haptic", "Haptic feedback", true),
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
private const val DEFAULT_CONFIG_URL = "http://192.168.0.243:8080/config.json"

private val SERVER_STRINGS = listOf(
    StringPref("serverIp",   "Server IP address", "127.0.0.1"),
    StringPref("serverPort", "Server port",       "43595", KeyboardType.Number),
    StringPref("serverConfigUrl", "Config import URL", DEFAULT_CONFIG_URL),
)

/** Compose settings for the safe majority of preferences; advanced/GL-critical ones open the legacy dialog. */
class SettingsActivity : BaseActivity() {
    // File picker for "Load config.json from file". Registered as a field so it is
    // available before the activity is STARTED (Activity Result API requirement).
    private val pickConfigFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) applyConfigFromUri(uri)
        }

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
                    onImportConfig = { importServerConfig(repo) },
                    onPickConfigFile = {
                        pickConfigFile.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Reload the legacy static prefs bank once when leaving settings, instead
        // of on every write (which ran a full filesystem prefs reload per keystroke).
        PojavApplication.appContainer.preferencesRepository.reloadLauncherPreferences()
    }

    /**
     * Fetch a server config.json (e.g. the one hosted on the LAN web root) and
     * OVERRIDE the serverIp / serverPort preferences from it. Runs off the UI
     * thread; recreates the screen on success so the fields show the new values.
     */
    private fun importServerConfig(repo: PreferencesRepository) {
        val url = repo.getString("serverConfigUrl", DEFAULT_CONFIG_URL)?.trim().orEmpty()
        if (url.isEmpty()) {
            Toast.makeText(this, "Set a config import URL first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Importing config from $url…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val result = applyConfigJson(repo, body)
                runOnUiThread {
                    Toast.makeText(this, "Imported $result — IP/port overridden", Toast.LENGTH_LONG).show()
                    recreate()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Read a config.json picked from device storage and OVERRIDE serverIp /
     * serverPort from it. Runs on the UI thread (result callback); the read is a
     * small local file so it does not need a background thread.
     */
    private fun applyConfigFromUri(uri: Uri) {
        val repo = PojavApplication.appContainer.preferencesRepository
        try {
            val body = contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("could not read file")
            val result = applyConfigJson(repo, body)
            Toast.makeText(this, "Loaded $result — IP/port overridden", Toast.LENGTH_LONG).show()
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Parse a server config.json body and write serverIp / serverPort prefs from
     * ip_address + wl/js5/server port. Returns "ip:port"; throws on bad data.
     * Shared by both the URL import and the file-picker import.
     */
    private fun applyConfigJson(repo: PreferencesRepository, body: String): String {
        val json = org.json.JSONObject(body)
        val ip = json.optString("ip_address", json.optString("ip_management", ""))
        if (ip.isEmpty()) throw IllegalStateException("config has no ip_address")
        val port = when {
            json.has("wl_port")     -> json.getInt("wl_port")
            json.has("js5_port")    -> json.getInt("js5_port")
            json.has("server_port") -> json.getInt("server_port")
            else -> 43595
        }
        repo.putString("serverIp", ip)
        repo.putString("serverPort", port.toString())
        return "$ip:$port"
    }
}

@Composable
private fun SettingsScreen(
    repo: PreferencesRepository,
    onBack: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onImportConfig: () -> Unit,
    onPickConfigFile: () -> Unit,
) {
    Surface(color = RsColors.bgDeep, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(8.dp)
                .background(RsColors.borderDark)
                .padding(1.dp)
                .background(RsColors.borderLight)
                .padding(2.dp)
                .background(RsColors.borderDark)
                .padding(1.dp)
                .background(RsColors.bgPanel),
        ) {
            RsHeaderBand("Settings")
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RsBackButton(onClick = onBack)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                section("Server") {
                    SERVER_STRINGS.forEach { item(it.key) { StringRow(it, repo) } }
                    item { Spacer(Modifier.height(8.dp)); RsButton("Import config from URL", onClick = onImportConfig, muted = true) }
                    item { Spacer(Modifier.height(6.dp)); RsButton("Load config.json from file", onClick = onPickConfigFile, muted = true) }
                }
                prefSection("Video", VIDEO_BOOLS, VIDEO_INTS, repo)
                prefSection("Controls", CONTROL_BOOLS, CONTROL_INTS, repo)
                item { Spacer(Modifier.height(8.dp)); RsButton("Edit on-screen controls", onClick = onOpenControls, muted = true) }
                prefSection("Java", JAVA_BOOLS, JAVA_INTS, repo)
                item { JavaArgsRow(repo) }
                prefSection("Misc", MISC_BOOLS, emptyList(), repo)
                prefSection("Experimental", EXPERIMENTAL_BOOLS, emptyList(), repo)
                item {
                    RsSectionHeader("Advanced")
                    Text("Renderer, runtime, plugins, and imports", color = RsColors.textMuted)
                    Spacer(Modifier.height(8.dp))
                    RsButton("Open advanced settings", onClick = onOpenAdvanced, muted = true)
                    Spacer(Modifier.height(20.dp))
                }
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
    item { RsSectionHeader(title) }
    items(bools) { BoolRow(it, repo) }
    items(ints) { IntRow(it, repo) }
}

private fun LazyListScope.section(title: String, content: LazyListScope.() -> Unit) {
    item { RsSectionHeader(title) }
    content()
}

@Composable
private fun BoolRow(pref: BoolPref, repo: PreferencesRepository) {
    var checked by remember { mutableStateOf(repo.getBoolean(pref.key, pref.def)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pref.label, color = RsColors.textBody, modifier = Modifier.weight(1f))
        RsToggle(checked = checked, onCheckedChange = {
            checked = it
            repo.putBoolean(pref.key, it)
        })
    }
}

@Composable
private fun IntRow(pref: IntPref, repo: PreferencesRepository) {
    var value by remember { mutableIntStateOf(repo.getInt(pref.key, pref.def)) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(pref.label, color = RsColors.textBody)
            Text("$value", color = RsColors.textBright)
        }
        RsSlider(
            value = value.toFloat(),
            onValueChange = { value = it.toInt() },
            valueRange = pref.range.first.toFloat()..pref.range.last.toFloat(),
            onValueChangeFinished = { repo.putInt(pref.key, value) },
        )
    }
}

private val rsFieldColors: @Composable () -> androidx.compose.material3.TextFieldColors = {
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = RsColors.borderLight,
        unfocusedBorderColor = RsColors.borderGold,
        focusedTextColor = RsColors.textBright,
        unfocusedTextColor = RsColors.textBody,
        cursorColor = RsColors.borderLight,
    )
}

@Composable
private fun StringRow(pref: StringPref, repo: PreferencesRepository) {
    var value by remember { mutableStateOf(repo.getString(pref.key, pref.def) ?: pref.def) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(pref.label, color = RsColors.textBody)
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; repo.putString(pref.key, it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = pref.keyboardType),
            colors = rsFieldColors(),
        )
    }
}

@Composable
private fun JavaArgsRow(repo: PreferencesRepository) {
    var text by remember { mutableStateOf(repo.getString("javaArgs", "") ?: "") }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Custom Java arguments", color = RsColors.textBody)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                repo.putString("javaArgs", it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = rsFieldColors(),
        )
    }
}
