package net.kdt.pojavlaunch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import net.kdt.pojavlaunch.R

// ── RuneScape interface fonts (genuine RS TTFs, bundled in res/font) ──
val RsFontFamily: FontFamily = FontFamily(
    Font(R.font.runescape, FontWeight.Normal),
    Font(R.font.runescape_bold, FontWeight.Bold),
)
val RsDisplayFamily: FontFamily = FontFamily(Font(R.font.runescape_bold, FontWeight.Bold))

/** Material 3 typography with the RuneScape font applied to the styles we use. */
val RsTypography: Typography = with(Typography()) {
    copy(
        displaySmall = displaySmall.copy(fontFamily = RsDisplayFamily),
        headlineSmall = headlineSmall.copy(fontFamily = RsDisplayFamily),
        titleLarge = titleLarge.copy(fontFamily = RsFontFamily),
        titleMedium = titleMedium.copy(fontFamily = RsFontFamily),
        titleSmall = titleSmall.copy(fontFamily = RsFontFamily),
        bodyLarge = bodyLarge.copy(fontFamily = RsFontFamily),
        bodyMedium = bodyMedium.copy(fontFamily = RsFontFamily),
        bodySmall = bodySmall.copy(fontFamily = RsFontFamily),
        labelLarge = labelLarge.copy(fontFamily = RsFontFamily),
        labelMedium = labelMedium.copy(fontFamily = RsFontFamily),
        labelSmall = labelSmall.copy(fontFamily = RsFontFamily),
    )
}

private val DarkColors = darkColorScheme(
    primary = RsColors.greenRs,
    onPrimary = RsColors.greenText,
    secondary = RsColors.borderGold,
    background = RsColors.bgDeep,
    surface = RsColors.bgPanel,
    onBackground = RsColors.textBody,
    onSurface = RsColors.textBody,
)

private val LightColors = lightColorScheme(primary = RsColors.greenRs)

/** Material 3 theme for the launcher shell only. The :game / AWT activities keep AppTheme. */
@Composable
fun LauncherTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        typography = RsTypography,
        content = content,
    )
}
