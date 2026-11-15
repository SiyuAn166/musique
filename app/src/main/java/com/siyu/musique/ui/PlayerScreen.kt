package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.siyu.musique.core.network.AudioQuality
import com.siyu.musique.model.RepeatMode
import com.siyu.musique.model.formatDuration
import com.siyu.musique.model.formatRemaining
import com.siyu.musique.ui.theme.MusiqueIcons
import kotlin.time.Duration.Companion.milliseconds

/**
 * Player page, approved redesign: a centered chevron-down back
 * icon, a large square cover with a soft shadow, a large bold
 * title + grey artist, a slim black capsule progress bar with
 * elapsed time left and remaining (negative) time right, black
 * skip icons around one large black pause circle, a full-width
 * slim volume slider with speaker icons at both ends, and the
 * quality chip at the bottom right, with the queue button at
 * the bottom left: it opens a sheet listing the playing queue,
 * the current track highlighted, tapping a row jumps to it.
 * The controls row is shuffle, skip-previous, the pause circle,
 * skip-next, repeat: the shuffle and repeat buttons are grey
 * when off and black when on, and the repeat button cycles
 * off, repeat all, repeat one. Sections are spread out with
 * generous gaps instead of crammed together. Dragging
 * the progress bar shows the dragged position and seeks on
 * release; the polled progress keeps running underneath.
 * Dragging the page down past the threshold goes back, like the
 * chevron button. Player errors show their message with a retry
 * icon button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val qualityPreference by viewModel.qualityPreference.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val currentParts by viewModel.currentParts.collectAsStateWithLifecycle()

    /** The dragged position while the user holds the slider, or null when not dragging. */
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }

    /** Whether the quality dropdown menu is open. */
    var qualityMenuExpanded by remember { mutableStateOf(false) }

    /** Whether the playing queue sheet is open. */
    var queueSheetOpen by remember { mutableStateOf(false) }

    // Opening the sheet learns the current video's parts when
    // they are still unknown (a track played with its cid
    // already filled never ran the details call).
    LaunchedEffect(queueSheetOpen) {
        if (queueSheetOpen) viewModel.loadPartsForCurrent()
    }

    /** Downward drag past this distance goes back. */
    val dismissThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }

    val track = currentTrack

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(onBack, dismissThresholdPx) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged > dismissThresholdPx) onBack()
                    },
                    onDragCancel = { dragged = 0f },
                    onVerticalDrag = { _, dragAmount -> dragged += dragAmount },
                )
            }
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MusiqueIcons.ChevronDown,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        if (track == null) {
            Text(
                text = "Nothing playing yet. Go back and tap a track.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (track.cover.isNotEmpty()) {
                AsyncImage(
                    model = track.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .shadow(12.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .shadow(12.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(16.dp))

            val durationMs = progress.durationMs.coerceAtLeast(1L)
            val shownPositionMs = (dragPositionMs ?: progress.positionMs)
                .coerceIn(0L, durationMs)
            SlimSlider(
                value = shownPositionMs.toFloat(),
                onValueChange = { dragPositionMs = it.toLong() },
                valueRange = 0f..durationMs.toFloat(),
                onValueChangeFinished = {
                    dragPositionMs?.let { viewModel.seekTo(it) }
                    dragPositionMs = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatDuration(shownPositionMs.milliseconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatRemaining(durationMs.milliseconds, shownPositionMs.milliseconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val errorState = playerState as? PlayerUiState.Error
            if (errorState != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = errorState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.retryPlayback() }) {
                        Icon(
                            imageVector = MusiqueIcons.Refresh,
                            contentDescription = "Retry playback",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeButton(
                    icon = MusiqueIcons.Shuffle,
                    contentDescription = if (shuffleEnabled) "Shuffle on" else "Shuffle off",
                    active = shuffleEnabled,
                    onClick = { viewModel.toggleShuffle() },
                )
                IconButton(onClick = { viewModel.playPrevious() }) {
                    Icon(
                        imageVector = MusiqueIcons.SkipPrevious,
                        contentDescription = "Previous",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(44.dp),
                    )
                }
                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface,
                            CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = if (isPlaying) {
                            MusiqueIcons.Pause
                        } else {
                            MusiqueIcons.PlayArrow
                        },
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = { viewModel.playNext() }) {
                    Icon(
                        imageVector = MusiqueIcons.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(44.dp),
                    )
                }
                ModeButton(
                    icon = if (repeatMode == RepeatMode.ONE) {
                        MusiqueIcons.RepeatOne
                    } else {
                        MusiqueIcons.Repeat
                    },
                    contentDescription = when (repeatMode) {
                        RepeatMode.OFF -> "Repeat off"
                        RepeatMode.ALL -> "Repeat all"
                        RepeatMode.ONE -> "Repeat one"
                    },
                    active = repeatMode != RepeatMode.OFF,
                    onClick = { viewModel.cycleRepeatMode() },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The end icons are buttons: tap for mute / full volume.
                IconButton(
                    onClick = { viewModel.setVolume(0f) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = MusiqueIcons.VolumeDown,
                        contentDescription = "Mute",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                SlimSlider(
                    value = volume,
                    onValueChange = { viewModel.setVolume(it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { viewModel.setVolume(1f) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = MusiqueIcons.VolumeUp,
                        contentDescription = "Maximum volume",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { queueSheetOpen = true }) {
                    Icon(
                        imageVector = MusiqueIcons.QueueMusic,
                        contentDescription = "Queue",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Box {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        // Flat light grey, like the mockup: tonal
                        // elevation tints the chip pink instead.
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { qualityMenuExpanded = true },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = qualityPreference.label,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Icon(
                                imageVector = MusiqueIcons.ChevronDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = qualityMenuExpanded,
                        onDismissRequest = { qualityMenuExpanded = false },
                    ) {
                        AudioQuality.entries.forEach { quality ->
                            DropdownMenuItem(
                                text = { Text(quality.label) },
                                onClick = {
                                    viewModel.setQualityPreference(quality)
                                    qualityMenuExpanded = false
                                },
                                leadingIcon = {
                                    if (quality == qualityPreference) {
                                        Icon(
                                            imageVector = MusiqueIcons.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Playing queue: the whole queue in order, the current
        // track highlighted, tapping a row jumps to it (tapping
        // the loaded track does nothing, same as everywhere).
        if (queueSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { queueSheetOpen = false },
                sheetState = rememberModalBottomSheetState(),
            ) {
                LazyColumn(
                    modifier = Modifier.padding(bottom = 32.dp),
                ) {
                    if (currentParts.size > 1) {
                        item {
                            Text(
                                text = "Parts",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp,
                                    bottom = 8.dp,
                                ),
                            )
                        }
                        itemsIndexed(currentParts, key = { _, part -> part.key }) { index, part ->
                            SongRow(
                                track = part,
                                isCurrent = part.key == track?.key,
                                onClick = {
                                    viewModel.playPart(part)
                                    queueSheetOpen = false
                                },
                                trailing = {},
                            )
                            if (index < currentParts.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(
                                        start = SONG_ROW_DIVIDER_START_DP.dp,
                                    ),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                        item {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    item {
                        Text(
                            text = "Queue",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 8.dp,
                            ),
                        )
                    }
                    itemsIndexed(queue, key = { _, row -> row.key }) { index, row ->
                        SongRow(
                            track = row,
                            // Parts share one video id, so match by
                            // key; a row whose cid is still empty
                            // falls back to the video id.
                            isCurrent = row.key == track?.key ||
                                (row.cid.isEmpty() && row.id == track?.id),
                            onClick = {
                                viewModel.playFromQueue(row)
                                queueSheetOpen = false
                            },
                            trailing = {},
                        )
                        if (index < queue.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(
                                    start = SONG_ROW_DIVIDER_START_DP.dp,
                                ),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A small mode button in the controls row (shuffle, repeat): a
 * grey icon when off, black when on. The tint change alone marks
 * the state; no dot underneath.
 */
@Composable
private fun ModeButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(28.dp),
        )
    }
}
