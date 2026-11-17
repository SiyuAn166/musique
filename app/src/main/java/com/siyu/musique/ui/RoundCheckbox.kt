package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.siyu.musique.ui.theme.MusiqueIcons

/**
 * A circular checkbox, the one checkbox style for every
 * multi-select scenario in the app. Unchecked it is an empty
 * circle outlined in the variant tint; checked it fills with
 * the on-surface color and shows a check in the surface color.
 * With [onCheckedChange] null it is display only, for rows
 * whose own tap handles the toggle; otherwise tapping it
 * toggles. The row tap toggles too, so this is a 24dp target,
 * not a 48dp one.
 */
@Composable
fun RoundCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (checked) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .border(
                width = 2.dp,
                color = if (checked) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = CircleShape,
            )
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = MusiqueIcons.Check,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
