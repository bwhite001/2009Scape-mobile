package net.kdt.pojavlaunch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colors pulled from the existing launcher artwork / colors.xml.
private val ScapeGreen = Color(0xFF57CC33) // minebutton_color
private val ScapeBrown = Color(0xFF231C09) // launcher background
private val ScapeText = Color(0xFFB8A078)  // settings text

private val DarkColors = darkColorScheme(
    primary = ScapeGreen,
    background = ScapeBrown,
    surface = ScapeBrown,
    onBackground = ScapeText,
    onSurface = ScapeText,
)

private val LightColors = lightColorScheme(primary = ScapeGreen)

/** Material 3 theme for the launcher shell only. The :game / AWT activities keep AppTheme. */
@Composable
fun LauncherTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content,
    )
}
