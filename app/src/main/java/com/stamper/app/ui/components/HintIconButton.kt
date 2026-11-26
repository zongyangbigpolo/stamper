package com.stamper.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Wraps [content] so a short text hint pops up on long-press (the only place labels appear). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithHint(hint: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(hint) } },
        state = rememberTooltipState()
    ) {
        Box { content() }
    }
}

/** Icon-only button; [hint] doubles as the accessibility label and the long-press tooltip. */
@Composable
fun HintIconButton(
    icon: ImageVector,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    iconSize: Dp = 24.dp
) {
    WithHint(hint) {
        IconButton(onClick = onClick, enabled = enabled, colors = colors, modifier = modifier) {
            Icon(icon, contentDescription = hint, modifier = Modifier.size(iconSize))
        }
    }
}
