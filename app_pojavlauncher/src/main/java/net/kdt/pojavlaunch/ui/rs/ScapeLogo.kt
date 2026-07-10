package net.kdt.pojavlaunch.ui.rs

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kdt.pojavlaunch.ui.theme.RsColors
import net.kdt.pojavlaunch.ui.theme.RsDisplayFamily
import net.kdt.pojavlaunch.ui.theme.RsFontFamily

/** The "2009Scape" logo with a subtle, animated rune glow behind the title. */
@Composable
fun ScapeLogo(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "glow")
    val glow by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(3000), RepeatMode.Reverse),
        label = "glowAlpha",
    )
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "2009Scape",
            color = RsColors.textBright,
            fontFamily = RsDisplayFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            letterSpacing = 2.sp,
            style =
                TextStyle(
                    shadow =
                        Shadow(
                            color = RsColors.borderLight.copy(alpha = glow),
                            offset = Offset(0f, 0f),
                            blurRadius = 26f,
                        ),
                ),
        )
        Text(
            "THE WORLD AS IT WAS",
            color = RsColors.parchmentDark,
            fontFamily = RsFontFamily,
            fontSize = 10.sp,
            letterSpacing = 4.sp,
        )
    }
}

@Preview
@Composable
private fun ScapeLogoPreview() {
    Column(Modifier.background(RsColors.bgDeep).padding(24.dp)) {
        ScapeLogo()
    }
}
