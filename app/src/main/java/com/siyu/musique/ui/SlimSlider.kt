package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The app's slider: a thin 4dp capsule track, light grey for the
 * remainder, near black for the filled part, with one small 10dp
 * black dot for the thumb. This is the approved player redesign:
 * the default Material slider's chunky thumb looked cheap next to
 * the rest of the page. Drag, tap-to-position, and accessibility
 * behavior all come from [Slider]; only the thumb and track are
 * custom. Used for both the progress bar and the volume slider.
 */
@Composable
fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.onSurface, CircleShape),
            )
        },
        track = { sliderState ->
            val range = sliderState.valueRange
            val span = range.endInclusive - range.start
            val fraction = if (span <= 0f) {
                0f
            } else {
                ((sliderState.value - range.start) / span).coerceIn(0f, 1f)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onSurface),
                )
            }
        },
    )
}
