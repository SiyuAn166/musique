package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The app's slider: a thin 4dp capsule track, light grey for the
 * remainder, near black for the filled part, with one small 10dp
 * black dot for the thumb, centered exactly on the track. This is
 * drawn and dragged by hand instead of using Material3's Slider:
 * the custom thumb/track overload is experimental and, on device,
 * placed the dot above the track instead of on it.
 *
 * Pressing jumps straight to the touched position; dragging
 * follows the finger; releasing calls [onValueChangeFinished].
 * [onValueChange] and [onValueChangeFinished] are read through
 * [rememberUpdatedState] so a drag in flight always calls the
 * latest lambdas even though the gesture block outlives any one
 * composition. Used for both the progress bar and the volume
 * slider.
 */
@Composable
fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val dotDiameterPx = with(LocalDensity.current) { 10.dp.toPx() }

    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span <= 0f) {
        0f
    } else {
        ((value - valueRange.start) / span).coerceIn(0f, 1f)
    }

    fun xToValue(x: Float): Float {
        val width = widthPx
        if (width <= 0 || span <= 0f) return value
        val pressed = (x / width).coerceIn(0f, 1f)
        return valueRange.start + pressed * span
    }

    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(valueRange) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        latestOnValueChange(xToValue(down.position.x))
                        val slopReached = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            latestOnValueChange(xToValue(change.position.x))
                            change.consume()
                        }
                        if (slopReached != null) {
                            horizontalDrag(down.id) { change ->
                                latestOnValueChange(xToValue(change.position.x))
                                change.consume()
                            }
                        }
                        latestOnValueChangeFinished?.invoke()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
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
        Box(
            modifier = Modifier
                .offset { IntOffset(((widthPx - dotDiameterPx) * fraction).roundToInt(), 0) }
                .size(10.dp)
                .background(MaterialTheme.colorScheme.onSurface, CircleShape),
        )
    }
}
