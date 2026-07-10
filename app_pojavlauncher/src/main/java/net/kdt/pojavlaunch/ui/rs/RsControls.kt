package net.kdt.pojavlaunch.ui.rs

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kdt.pojavlaunch.ui.theme.RsColors
import net.kdt.pojavlaunch.ui.theme.RsFontFamily

/** RS2 button: green pill (or muted brown) with layered gold/dark bevel. */
@Composable
fun RsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val gradient =
        if (muted) {
            Brush.verticalGradient(listOf(Color(0xFF5A4020), Color(0xFF3A2810), Color(0xFF1E1408)))
        } else {
            Brush.verticalGradient(listOf(RsColors.greenLight, RsColors.greenRs, RsColors.greenDark))
        }
    val outline = if (muted) RsColors.borderGold else Color(0xFF4AB828)
    val label = if (muted) RsColors.parchment else RsColors.greenText
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(RsColors.borderDark)
            .padding(1.dp)
            .background(outline)
            .padding(1.dp)
            .background(RsColors.borderDark)
            .padding(1.dp)
            .background(gradient)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = label,
            fontFamily = RsFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 1.sp,
        )
    }
}

/** RS2 toggle: inset dark track with a gold square knob; green glow when on. */
@Composable
fun RsToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val knobOffset by animateDpAsState(if (checked) 24.dp else 2.dp, label = "knob")
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(width = 44.dp, height = 22.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (checked) RsColors.greenDark else RsColors.toggleOff)
            .border(
                1.dp,
                if (checked) RsColors.greenLight else RsColors.borderGold,
                RoundedCornerShape(2.dp),
            )
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
    ) {
        Box(
            Modifier
                .offset(x = knobOffset, y = 2.dp)
                .size(16.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(
                    if (checked) {
                        Brush.radialGradient(listOf(RsColors.greenLight, RsColors.greenRs))
                    } else {
                        Brush.radialGradient(listOf(RsColors.borderGold, Color(0xFF4A3008)))
                    },
                ),
        )
    }
}

/** RS2-tinted slider (gold thumb / active track, dark inactive track). */
@Composable
fun RsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        modifier = modifier,
        colors =
            SliderDefaults.colors(
                thumbColor = RsColors.borderLight,
                activeTrackColor = RsColors.borderGold,
                inactiveTrackColor = RsColors.borderDark,
            ),
    )
}

/** Small green RS-styled back button. */
@Composable
fun RsBackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(2.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2A5010), Color(0xFF1A3008))))
            .border(1.dp, RsColors.greenDark, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            "◀ BACK",
            color = RsColors.greenLight,
            fontFamily = RsFontFamily,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

/** Centered parchment text link. */
@Composable
fun RsLink(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text.uppercase(),
        color = RsColors.parchmentDark,
        fontFamily = RsFontFamily,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .fillMaxWidth()
                .clickable(onClick = onClick, role = Role.Button)
                .padding(8.dp),
    )
}

@Preview
@Composable
private fun RsControlsPreview() {
    Box(Modifier.background(RsColors.bgDeep).padding(16.dp)) {
        RsButton("Play HD", onClick = {})
    }
}
