package net.kdt.pojavlaunch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

private data class BoolPref(
    val key: String,
    @StringRes val labelRes: Int,
    val def: Boolean,
)

private data class IntPref(
    val key: String,
    @StringRes val labelRes: Int,
    val def: Int,
    val range: IntRange,
)

private data class StringPref(
    val key: String,
    @StringRes val labelRes: Int,
    val def: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
)

// Simple, safe-to-model preferences (exact SharedPreferences keys preserved).
// GL-critical / bespoke prefs (renderer, alternate_surface, defaultRuntime,
// gamepad remap, plugins, file import) stay in the legacy "Advanced" dialog.
private val VIDEO_BOOLS =
    listOf(
        BoolPref("ignoreNotch", R.string.pref_ignore_notch, false),
        BoolPref("sustainedPerformance", R.string.pref_sustained_performance, false),
        BoolPref("force_vsync", R.string.pref_force_vsync, false),
    )
private val VIDEO_INTS =
    listOf(
        IntPref("resolutionRatio", R.string.pref_resolution_scale, 60, 25..100),
        IntPref("xinset", R.string.pref_horizontal_inset, 0, 0..100),
    )
private val CONTROL_BOOLS =
    listOf(
        BoolPref("disableGestures", R.string.pref_disable_gestures, false),
        BoolPref("disableDoubleTap", R.string.pref_disable_double_tap, false),
        BoolPref("singleTapRightClick", R.string.pref_single_tap_right_click, false),
        BoolPref("haptic", R.string.pref_haptic, true),
        BoolPref("mouse_start", R.string.pref_mouse_start, false),
        BoolPref("buttonAllCaps", R.string.pref_button_all_caps, true),
        BoolPref("enableGyro", R.string.pref_enable_gyro, false),
        BoolPref("gyroSmoothing", R.string.pref_gyro_smoothing, true),
        BoolPref("gyroInvertX", R.string.pref_gyro_invert_x, false),
        BoolPref("gyroInvertY", R.string.pref_gyro_invert_y, false),
    )
private val CONTROL_INTS =
    listOf(
        IntPref("buttonscale", R.string.pref_button_size, 100, 25..500),
        IntPref("mousescale", R.string.pref_mouse_scale, 100, 25..300),
        IntPref("mousespeed", R.string.pref_mouse_speed, 100, 25..300),
        IntPref("timeLongPressTrigger", R.string.pref_long_press_delay, 300, 100..1000),
        IntPref("gyroSensitivity", R.string.pref_gyro_sensitivity, 100, 10..300),
        IntPref("gyroSampleRate", R.string.pref_gyro_sample_rate, 16, 5..50),
        IntPref("gamepad_deadzone_scale", R.string.pref_gamepad_deadzone, 100, 0..200),
    )
private val JAVA_BOOLS =
    listOf(
        BoolPref("java_sandbox", R.string.pref_java_sandbox, true),
    )
private val JAVA_INTS =
    listOf(
        IntPref("allocation", R.string.pref_ram_allocation, 256, 256..1024),
    )
private val MISC_BOOLS =
    listOf(
        BoolPref("checkLibraries", R.string.pref_check_libraries, true),
        BoolPref("arc_capes", R.string.pref_arc_capes, false),
    )
private val EXPERIMENTAL_BOOLS =
    listOf(
        BoolPref("dump_shaders", R.string.pref_dump_shaders, false),
        BoolPref("bigCoreAffinity", R.string.pref_big_core_affinity, false),
    )
private const val DEFAULT_CONFIG_URL = ""

/**
 * Hard ceiling on the size of a downloaded config.json body. A real config is
 * a handful of fields (well under a KB); this just bounds a malicious or
 * misbehaving server from streaming an unbounded response into memory.
 */
private const val MAX_CONFIG_BYTES = 512 * 1024

private val SERVER_STRINGS =
    listOf(
        StringPref("serverIp", R.string.pref_server_ip, "127.0.0.1"),
        StringPref("serverPort", R.string.pref_server_port, "43595", KeyboardType.Number),
        StringPref("serverConfigUrl", R.string.pref_server_config_url, DEFAULT_CONFIG_URL),
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
            Toast.makeText(this, getString(R.string.settings_import_need_url), Toast.LENGTH_SHORT).show()
            return
        }
        val importMessage =
            if (url.startsWith("http://", ignoreCase = true)) {
                getString(R.string.settings_import_http_warn, url)
            } else {
                getString(R.string.settings_import_start, url)
            }
        Toast.makeText(this, importMessage, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val conn =
                    (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        requestMethod = "GET"
                    }
                val body =
                    conn.inputStream.use { input ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        var total = 0
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            total += read
                            if (total > MAX_CONFIG_BYTES) {
                                throw IllegalStateException(
                                    "response too large (max $MAX_CONFIG_BYTES bytes)",
                                )
                            }
                            buffer.write(chunk, 0, read)
                        }
                        String(buffer.toByteArray(), Charsets.UTF_8)
                    }
                conn.disconnect()
                val result = applyConfigJson(repo, body)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.settings_import_ok, result), Toast.LENGTH_LONG).show()
                    recreate()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.settings_import_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
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
            val body =
                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    var total = 0L
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        if (!ImportGuard.isWithinSizeLimit(total)) {
                            throw IllegalStateException(
                                "file too large (max ${ImportGuard.MAX_IMPORT_BYTES} bytes)",
                            )
                        }
                        buffer.write(chunk, 0, read)
                    }
                    String(buffer.toByteArray(), Charsets.UTF_8)
                } ?: throw IllegalStateException("could not read file")
            val result = applyConfigJson(repo, body)
            Toast.makeText(this, getString(R.string.settings_load_ok, result), Toast.LENGTH_LONG).show()
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_load_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Parse a server config.json body and write serverIp / serverPort prefs from
     * ip_address + wl/js5/server port. Returns "ip:port"; throws on bad data.
     * Shared by both the URL import and the file-picker import.
     */
    private fun applyConfigJson(
        repo: PreferencesRepository,
        body: String,
    ): String {
        val json = org.json.JSONObject(body)
        val ipAddress = if (json.has("ip_address")) json.getString("ip_address") else null
        val ipManagement = if (json.has("ip_management")) json.getString("ip_management") else null
        // Faithful to the old `json.optString("ip_address", json.optString("ip_management", ""))`:
        // fall back to ip_management ONLY when ip_address is entirely absent, not merely blank.
        // An `ip_address: ""` key that IS present must NOT fall back and must still throw below.
        val ip = ServerConfig.resolvePresentIp(ipAddress, ipManagement)
        if (ip.isEmpty()) {
            throw IllegalStateException("config has no ip_address")
        }
        val serverPort = if (json.has("server_port")) json.getInt("server_port").toString() else null
        val wlPort = if (json.has("wl_port")) json.getInt("wl_port").toString() else null
        val js5Port = if (json.has("js5_port")) json.getInt("js5_port").toString() else null

        val resolved = ServerConfig.normalize(ip, ip, serverPort, wlPort, js5Port)
        repo.putString("serverIp", resolved.ip)
        repo.putString("serverPort", resolved.port.toString())
        return "${resolved.ip}:${resolved.port}"
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
            RsHeaderBand(stringResource(R.string.settings_title))
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RsBackButton(onClick = onBack)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                section(R.string.settings_section_server) {
                    SERVER_STRINGS.forEach { item(it.key) { StringRow(it, repo) } }
                    item {
                        Spacer(Modifier.height(8.dp))
                        RsButton(stringResource(R.string.settings_import_url), onClick = onImportConfig, muted = true)
                    }
                    item {
                        Spacer(Modifier.height(6.dp))
                        RsButton(stringResource(R.string.settings_load_file), onClick = onPickConfigFile, muted = true)
                    }
                }
                prefSection(R.string.settings_section_video, VIDEO_BOOLS, VIDEO_INTS, repo)
                prefSection(R.string.settings_section_controls, CONTROL_BOOLS, CONTROL_INTS, repo)
                item {
                    Spacer(Modifier.height(8.dp))
                    RsButton(stringResource(R.string.settings_edit_controls), onClick = onOpenControls, muted = true)
                }
                prefSection(R.string.settings_section_java, JAVA_BOOLS, JAVA_INTS, repo)
                item { JavaArgsRow(repo) }
                prefSection(R.string.settings_section_misc, MISC_BOOLS, emptyList(), repo)
                prefSection(R.string.settings_section_experimental, EXPERIMENTAL_BOOLS, emptyList(), repo)
                item {
                    RsSectionHeader(stringResource(R.string.settings_section_advanced))
                    Text(stringResource(R.string.settings_advanced_desc), color = RsColors.textMuted)
                    Spacer(Modifier.height(8.dp))
                    RsButton(stringResource(R.string.settings_open_advanced), onClick = onOpenAdvanced, muted = true)
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

private fun LazyListScope.prefSection(
    @StringRes titleRes: Int,
    bools: List<BoolPref>,
    ints: List<IntPref>,
    repo: PreferencesRepository,
) {
    item { RsSectionHeader(stringResource(titleRes)) }
    items(bools) { BoolRow(it, repo) }
    items(ints) { IntRow(it, repo) }
}

private fun LazyListScope.section(
    @StringRes titleRes: Int,
    content: LazyListScope.() -> Unit,
) {
    item { RsSectionHeader(stringResource(titleRes)) }
    content()
}

@Composable
private fun BoolRow(
    pref: BoolPref,
    repo: PreferencesRepository,
) {
    var checked by remember { mutableStateOf(repo.getBoolean(pref.key, pref.def)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(pref.labelRes), color = RsColors.textBody, modifier = Modifier.weight(1f))
        RsToggle(checked = checked, onCheckedChange = {
            checked = it
            repo.putBoolean(pref.key, it)
        })
    }
}

@Composable
private fun IntRow(
    pref: IntPref,
    repo: PreferencesRepository,
) {
    var value by remember { mutableIntStateOf(repo.getInt(pref.key, pref.def)) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(pref.labelRes), color = RsColors.textBody)
            Text("$value", color = RsColors.textBright)
        }
        RsSlider(
            value = value.toFloat(),
            onValueChange = { value = it.toInt() },
            valueRange = pref.range.first.toFloat()..pref.range.last.toFloat(),
            onValueChangeFinished = { repo.putInt(pref.key, value) },
            modifier = Modifier.semantics { stateDescription = value.toString() },
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
private fun StringRow(
    pref: StringPref,
    repo: PreferencesRepository,
) {
    var value by remember { mutableStateOf(repo.getString(pref.key, pref.def) ?: pref.def) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(pref.labelRes), color = RsColors.textBody)
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                repo.putString(pref.key, it)
            },
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
        Text(stringResource(R.string.pref_java_args), color = RsColors.textBody)
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
