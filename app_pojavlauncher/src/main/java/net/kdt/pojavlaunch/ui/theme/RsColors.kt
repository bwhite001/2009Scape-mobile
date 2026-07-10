package net.kdt.pojavlaunch.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * RuneScape 2 / 2009-era interface palette. Pulled from the RS2 login screen and
 * interface chrome: deep mahogany void, gold panel borders, parchment text, and
 * the green interface button.
 */
object RsColors {
    val bgDeep = Color(0xFF0D0800) // screen void behind the panel
    val bgPanel = Color(0xFF1C1008) // stone panel fill
    val borderGold = Color(0xFF8C6914) // panel border (mid)
    val borderLight = Color(0xFFC8A040) // panel border highlight
    val borderDark = Color(0xFF3A2408) // panel border shadow
    val parchment = Color(0xFFC8A96E) // section labels
    val parchmentDark = Color(0xFF9A7A42)
    val textBright = Color(0xFFFFDD88) // titles / values
    val textBody = Color(0xFFE8D89A) // body labels
    val textMuted = Color(0xFF9A8860) // descriptions
    val greenRs = Color(0xFF3A9A20) // button base
    val greenLight = Color(0xFF5DC435) // button highlight
    val greenDark = Color(0xFF1E5C0E) // button shadow
    val greenText = Color(0xFF1A3A0A) // button label
    val toggleOff = Color(0xFF120C00) // toggle track (off)
}
