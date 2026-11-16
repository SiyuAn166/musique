package com.siyu.musique.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Near-black: text, icons, and the primary color. The theme has no accent color. */
private val MusiqueInk: Color = Color(0xFF1A1A1A)

/** Grey secondary text (artists, durations, counts). */
private val MusiqueSecondaryText: Color = Color(0xFF8E8E93)

/** The light-grey search field and placeholder fill. */
private val MusiqueFieldGrey: Color = Color(0xFFF2F2F7)

/** Hairline divider grey. */
private val MusiqueHairline: Color = Color(0xFFE5E5EA)

/** Neutral container greys for dialogs, sheets, and menus. */
private val MusiqueContainerLow: Color = Color(0xFFF7F7F7)
private val MusiqueContainerHigh: Color = Color(0xFFE9E9EE)

/**
 * The Musique look: forced light, always. Pure white background,
 * near-black text and controls, grey secondary text, and neutral
 * container greys: every Material role is pinned to this palette,
 * so no default purple or accent tint can leak in through dialogs,
 * sheets, menus, progress indicators, or elevated cards. The only
 * color left is the error red. The [darkTheme] parameter is kept
 * for signature compatibility and ignored: the user ordered a
 * light theme, and the system dark setting must not change it.
 */
private val LightColors = lightColorScheme(
    primary = MusiqueInk,
    onPrimary = Color.White,
    primaryContainer = MusiqueHairline,
    onPrimaryContainer = MusiqueInk,
    secondary = MusiqueSecondaryText,
    onSecondary = Color.White,
    secondaryContainer = MusiqueHairline,
    onSecondaryContainer = MusiqueInk,
    background = Color.White,
    onBackground = MusiqueInk,
    surface = Color.White,
    onSurface = MusiqueInk,
    surfaceVariant = MusiqueFieldGrey,
    onSurfaceVariant = MusiqueSecondaryText,
    surfaceTint = MusiqueInk,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = MusiqueContainerLow,
    surfaceContainer = MusiqueFieldGrey,
    surfaceContainerHigh = MusiqueContainerHigh,
    surfaceContainerHighest = MusiqueHairline,
    outline = MusiqueSecondaryText,
    outlineVariant = MusiqueHairline,
    inverseSurface = MusiqueInk,
    inverseOnSurface = Color.White,
    inversePrimary = MusiqueSecondaryText,
    error = Color(0xFFFF3B30),
    onError = Color.White,
)

@Composable
fun MusiqueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
