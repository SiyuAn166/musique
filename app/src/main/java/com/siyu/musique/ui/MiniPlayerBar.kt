package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.siyu.musique.R
import com.siyu.musique.core.network.SMALL_COVER_HEIGHT
import com.siyu.musique.core.network.SMALL_COVER_WIDTH
import com.siyu.musique.core.network.sizedCoverUrl
import com.siyu.musique.model.Track
import com.siyu.musique.model.formatDuration
import com.siyu.musique.ui.theme.MusiqueIcons
import kotlin.time.Duration.Companion.milliseconds

/**
 * Slim floating mini player bar: a white card with
 * large rounded corners, a small square cover, title/artist,
 * previous/play-pause/next icon buttons, and a thin progress
 * hairline along the bottom edge driven by [progress]. A resolved quality label ("192K", "Hi-Res") shows as
 * a small black badge before the title when [qualityLabel] is not
 * null. Tapping the bar (not the buttons) opens the player page;
 * the error state shows a retry icon button instead of the
 * controls. The second line names the player state while loading
 * or failed; while playing or paused it shows the elapsed and
 * total time. When no duration is known yet it falls back to the
 * artist and duration.
 */
@Composable
fun MiniPlayerBar(
    track: Track?,
    isPlaying: Boolean,
    playerState: PlayerUiState,
    progress: PlaybackProgress,
    onTogglePlayPause: () -> Unit,
    onRetry: () -> Unit,
    onOpenPlayer: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    qualityLabel: String?,
    modifier: Modifier = Modifier,
) {
    val statusLine = if (track == null) {
        stringResource(R.string.tap_to_open_player)
    } else {
        when (playerState) {
            PlayerUiState.Fetching, PlayerUiState.Buffering -> stringResource(R.string.loading)
            is PlayerUiState.Error -> stringResource(R.string.playback_error)
            else -> if (progress.durationMs > 0) {
                val shownPositionMs =
                    minOf(progress.positionMs, progress.durationMs).coerceAtLeast(0L)
                "${formatDuration(shownPositionMs.milliseconds)} / " +
                    formatDuration(progress.durationMs.milliseconds)
            } else {
                "${track.artist} · ${formatDuration(track.duration)}"
            }
        }
    }
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPlayer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (track != null && track.cover.isNotEmpty()) {
                    AsyncImage(
                        model = sizedCoverUrl(track.cover, SMALL_COVER_WIDTH, SMALL_COVER_HEIGHT),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MusiqueIcons.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (qualityLabel != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    text = qualityLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.padding(
                                        horizontal = 4.dp,
                                        vertical = 2.dp,
                                    ),
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = track?.title ?: "Musique",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                if (playerState is PlayerUiState.Error) {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = MusiqueIcons.Refresh,
                            contentDescription = stringResource(R.string.retry_playback),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    val controlTint = if (track != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                    IconButton(
                        onClick = onPrevious,
                        enabled = track != null,
                    ) {
                        Icon(
                            imageVector = MusiqueIcons.SkipPrevious,
                            contentDescription = stringResource(R.string.previous),
                            tint = controlTint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    IconButton(
                        onClick = onTogglePlayPause,
                        enabled = track != null,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) {
                                MusiqueIcons.Pause
                            } else {
                                MusiqueIcons.PlayArrow
                            },
                            contentDescription = if (isPlaying) {
                                stringResource(R.string.pause)
                            } else {
                                stringResource(R.string.play)
                            },
                            tint = controlTint,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    IconButton(
                        onClick = onNext,
                        enabled = track != null,
                    ) {
                        Icon(
                            imageVector = MusiqueIcons.SkipNext,
                            contentDescription = stringResource(R.string.next),
                            tint = controlTint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
