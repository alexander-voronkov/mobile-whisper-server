package com.example.whisperserver.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.whisperserver.ui.theme.appColors

/** White (design) card with a hairline border and rounded corners. */
@Composable
fun CompactCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(12.dp),
    content: @Composable () -> Unit,
) {
    val c = appColors
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.card)
            .border(1.dp, c.cardBorder, RoundedCornerShape(12.dp))
            .padding(padding),
    ) { content() }
}

/** Screen header row: title on the left, optional trailing content on the right. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = appColors.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Small uppercase section caption used above grouped cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = appColors.textSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/** Hairline divider used between rows inside a card. */
@Composable
fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(appColors.divider),
    )
}

/** Compact 42×24 pill switch matching the design (green when on). */
@Composable
fun MiniToggle(checked: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    val c = appColors
    val knobOffset by animateDpAsState(if (checked) 21.dp else 3.dp, label = "knob")
    Box(
        Modifier
            .width(42.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) c.primary else c.chipOutline)
            .clickable(enabled = enabled, onClick = onToggle),
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .padding(top = 3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** The 4-destination bottom navigation bar, styled to the compact design. */
@Composable
fun CompactBottomBar(
    current: NavDest,
    onSelect: (NavDest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = appColors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.navBar)
            .heightIn(min = 62.dp),
    ) {
        NavDest.entries.forEach { dest ->
            val selected = dest == current
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable { onSelect(dest) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    dest.icon,
                    contentDescription = dest.label,
                    tint = if (selected) c.primary else c.textMuted,
                    modifier = Modifier.height(22.dp),
                )
                Text(
                    dest.label,
                    color = if (selected) c.primary else c.textMuted,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
