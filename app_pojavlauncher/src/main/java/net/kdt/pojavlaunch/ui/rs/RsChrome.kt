package net.kdt.pojavlaunch.ui.rs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kdt.pojavlaunch.ui.theme.RsColors
import net.kdt.pojavlaunch.ui.theme.RsDisplayFamily
import net.kdt.pojavlaunch.ui.theme.RsFontFamily

/**
 * The signature RS2 panel: nested dark/gold/dark border rings around a mahogany
 * fill, with a gold rivet at each corner and an optional gold title band.
 */
@Composable
fun RsPanel(
    modifier: Modifier = Modifier,
    header: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(RsColors.borderDark)
                .padding(1.dp)
                .background(RsColors.borderLight)
                .padding(2.dp)
                .background(RsColors.borderDark)
                .padding(1.dp)
                .background(RsColors.bgPanel),
        ) {
            if (header != null) RsHeaderBand(header)
            Column(Modifier.padding(16.dp), content = content)
        }
        Rivet(Alignment.TopStart)
        Rivet(Alignment.TopEnd)
        Rivet(Alignment.BottomStart)
        Rivet(Alignment.BottomEnd)
    }
}

@Composable
private fun BoxScope.Rivet(alignment: Alignment) {
    Box(
        Modifier
            .clearAndSetSemantics {}
            .align(alignment)
            .padding(1.dp)
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(
                Brush.radialGradient(
                    listOf(RsColors.borderLight, RsColors.borderGold, RsColors.borderDark),
                ),
            ),
    )
}

/** Gold gradient title band used at the top of a panel. */
@Composable
fun RsHeaderBand(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(RsColors.borderGold, Color(0xFF4A3008), RsColors.borderDark),
                ),
            )
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.uppercase(),
            color = RsColors.textBright,
            fontFamily = RsDisplayFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

/** Small-caps parchment section label with a gold hairline under it. */
@Composable
fun RsSectionHeader(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
        Text(
            text.uppercase(),
            color = RsColors.parchment,
            fontFamily = RsFontFamily,
            fontSize = 12.sp,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(RsColors.borderGold.copy(alpha = 0.4f)),
        )
    }
}

@Preview
@Composable
private fun RsPanelPreview() {
    Box(Modifier.background(RsColors.bgDeep).padding(16.dp)) {
        RsPanel(header = "Settings") {
            RsSectionHeader("Video")
            Text("Panel body", color = RsColors.textBody)
        }
    }
}
