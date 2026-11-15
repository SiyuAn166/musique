package com.siyu.musique.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The pinkish-red accent from the approved Apple Music style mockups. */
val MusiqueAccent: Color = Color(0xFFFA2F6A)

/** Near-black text on the white background. */
private val MusiqueOnBackground: Color = Color(0xFF1A1A1A)

/** Grey secondary text (artists, durations, counts). */
private val MusiqueSecondaryText: Color = Color(0xFF8E8E93)

/** The light-grey search field and placeholder fill. */
private val MusiqueFieldGrey: Color = Color(0xFFF2F2F7)

/** Hairline divider grey. */
private val MusiqueHairline: Color = Color(0xFFE5E5EA)

/**
 * The Musique look: forced light, always. Pure white background,
 * near-black text, grey secondary text, and the pinkish-red accent
 * for the primary color, the slider, and progress. The [darkTheme]
 * parameter is kept for signature compatibility and ignored: the
 * user ordered a light theme, and the system dark setting must not
 * change it.
 */
private val LightColors = lightColorScheme(
    primary = MusiqueAccent,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = MusiqueOnBackground,
    surface = Color.White,
    onSurface = MusiqueOnBackground,
    surfaceVariant = MusiqueFieldGrey,
    onSurfaceVariant = MusiqueSecondaryText,
    outlineVariant = MusiqueHairline,
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
