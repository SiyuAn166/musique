package com.siyu.musique.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Step 0 theme placeholder: default Material 3 colors.
// The real Musique look (brand colors, typography) is UI polish,
// deliberately out of scope for v0.1 Step 0.

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun MusiqueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
