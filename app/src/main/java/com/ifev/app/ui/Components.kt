package com.ifev.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPleosColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .padding(horizontal = 32.dp, vertical = 28.dp),
        content = content,
    )
}

@Composable
fun Label(text: String, color: Color = LocalPleosColors.current.textTertiary, style: TextStyle = PleosType.label, modifier: Modifier = Modifier) {
    Text(text, style = style, color = color, modifier = modifier)
}

/** A small rounded tag. */
@Composable
fun Badge(text: String, color: Color, background: Color) {
    Text(
        text,
        style = PleosType.labelStrong,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Pleos segmented tabs: one rounded track, the selected segment raised. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPleosColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceHigh)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) c.surface else Color.Transparent)
                    .clickable { onSelect(i) }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = if (on) PleosType.labelStrong else PleosType.label, color = if (on) c.textPrimary else c.textTertiary)
            }
        }
    }
}

/** Pleos button: filled for the main action, basic otherwise. At least 80 dp tall for in-car touch. */
@Composable
fun PleosButton(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPleosColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (filled) c.buttonFilled else c.buttonBasic)
            .clickable(onClick = onClick)
            .heightIn(min = 80.dp)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PleosType.bodySmallStrong, color = if (filled) c.onButtonFilled else c.textPrimary, textAlign = TextAlign.Center)
    }
}

/** A horizontal level bar, 0..1. */
@Composable
fun LevelBar(level: Double, color: Color, modifier: Modifier = Modifier, height: Int = 24) {
    val c = LocalPleosColors.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape((height / 2).dp))
            .background(c.surfaceHigh),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.toFloat().coerceIn(0f, 1f))
                .clip(RoundedCornerShape((height / 2).dp))
                .background(color),
        )
    }
}

@Composable
fun StatusDot(label: String, ok: Boolean) {
    val c = LocalPleosColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) c.active else c.textQuaternary))
        Spacer(Modifier.width(8.dp))
        Text(label, style = PleosType.labelSmall, color = c.textTertiary)
    }
}

/** One figure with its caption. */
@Composable
fun RowScope.Stat(title: String, value: String, caption: String?, color: Color = LocalPleosColors.current.textPrimary) {
    Card(Modifier.weight(1f)) {
        Label(title)
        Spacer(Modifier.height(8.dp))
        Text(value, style = PleosType.title, color = color)
        if (caption != null) {
            Spacer(Modifier.height(4.dp))
            Label(caption, style = PleosType.labelSmall)
        }
    }
}

@Composable
fun Bullets(lines: List<String>, style: TextStyle = PleosType.bodySmall, color: Color = LocalPleosColors.current.textSecondary) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lines.forEach { line ->
            Row {
                Text("•", style = style, color = color, modifier = Modifier.padding(end = 12.dp))
                Text(line, style = style, color = color)
            }
        }
    }
}
