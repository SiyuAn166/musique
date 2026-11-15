package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.siyu.musique.model.Track
import com.siyu.musique.model.formatDuration
import com.siyu.musique.model.formatRemaining
import com.siyu.musique.ui.theme.MusiqueIcons
import kotlin.time.Duration.Companion.milliseconds

/**
 * Player page, approved redesign: a centered chevron-down back
 * icon, a large square cover with a soft shadow, a large bold
 * title + grey artist, a slim black capsule progress bar with
 * elapsed time left and remaining (negative) time right, black
 * skip icons around one large plain play/pause icon, a full-width
 * slim volume slider with speaker icons at both ends, and the
 * quality chip at the bottom right, with the queue button and
 * the download button at the bottom left: the queue button opens
 * a sheet listing the playing queue, the current track
 * highlighted, tapping a row jumps to it; the download button
 * downloads the current track at the current quality (a spinner
 * while downloading, a check once it is downloaded).
 * The controls row is shuffle, skip-previous, the play/pause
 * icon, skip-next, repeat: the shuffle and repeat buttons are grey
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
    val downloadRecords by viewModel.downloadRecords.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val resolvedQualityId by viewModel.resolvedQualityId.collectAsStateWithLifecycle()

    /** The dragged position while the user holds the slider, or null when not dragging. */
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }

    /** Whether the quality dropdown menu is open. */
    var qualityMenuExpanded by remember { mutableStateOf(false) }

    /** Whether the playing queue sheet is open. */
    var queueSheetOpen by remember { mutableStateOf(false) }

    // Opening the sheet learns the current video's parts when
    // they are still unknown (a no-op once the background learn
    // after the hand-off has landed).
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
        if (track != null && track.cover.isNotEmpty()) {
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
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MusiqueIcons.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = track?.title ?: "Musique",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = track?.artist ?: "Select a song from search or playlists",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(16.dp))

        val durationMs = if (track != null) progress.durationMs.coerceAtLeast(1L) else 1L
        val shownPositionMs = if (track != null) {
            (dragPositionMs ?: progress.positionMs).coerceIn(0L, durationMs)
        } else {
            0L
        }
        SlimSlider(
            value = shownPositionMs.toFloat(),
            onValueChange = { if (track != null) dragPositionMs = it.toLong() },
            valueRange = 0f..durationMs.toFloat(),
            onValueChangeFinished = {
                if (track != null) {
                    dragPositionMs?.let { viewModel.seekTo(it) }
                    dragPositionMs = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (track != null) formatDuration(shownPositionMs.milliseconds) else "0:00",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (track != null) formatRemaining(durationMs.milliseconds, shownPositionMs.milliseconds) else "0:00",
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
            IconButton(
                onClick = { viewModel.playPrevious() },
                enabled = track != null,
            ) {
                Icon(
                    imageVector = MusiqueIcons.SkipPrevious,
                    contentDescription = "Previous",
                    tint = if (track != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.size(44.dp),
                )
            }
            // Plain icon, same flat style as the skip icons, only
            // larger: no filled circle, no background shape.
            IconButton(
                onClick = { viewModel.togglePlayPause() },
                enabled = track != null || queue.isNotEmpty(),
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) {
                        MusiqueIcons.Pause
                    } else {
                        MusiqueIcons.PlayArrow
                    },
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (track != null || queue.isNotEmpty()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.size(56.dp),
                )
            }
            IconButton(
                onClick = { viewModel.playNext() },
                enabled = track != null,
            ) {
                Icon(
                    imageVector = MusiqueIcons.SkipNext,
                    contentDescription = "Next",
                    tint = if (track != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { queueSheetOpen = true }) {
                    Icon(
                        imageVector = MusiqueIcons.QueueMusic,
                        contentDescription = "Queue",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                // Download: a spinner while this track downloads, a
                // check when the current quality is downloaded
                // (tapping it does nothing), the download icon
                // otherwise (tapping it downloads, or retries
                // after a failure). Progress entries are keyed by
                // track key while resolving, then by track key
                // plus quality id, so both shapes belong here.
                val trackKey = track?.key
                val downloadingEntry = if (trackKey != null) {
                    downloadProgress.entries.firstOrNull { (key, value) ->
                        !value.failed &&
                            (key == trackKey || key.startsWith("$trackKey:"))
                    }?.value
                } else {
                    null
                }
                val isDownloaded = track != null && downloadRecords.any { record ->
                    record.track.key == track.key &&
                        (resolvedQualityId == null || record.qualityId == resolvedQualityId)
                }
                when {
                    track == null -> {
                        IconButton(onClick = {}, enabled = false) {
                            Icon(
                                imageVector = MusiqueIcons.Download,
                                contentDescription = "Download",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.4f,
                                ),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    isDownloaded -> {
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = MusiqueIcons.Check,
                                contentDescription = "Downloaded",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    downloadingEntry != null -> {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val fraction = downloadingEntry.fraction
                            if (fraction != null) {
                                CircularProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.size(24.dp),
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                    else -> {
                        IconButton(onClick = { viewModel.downloadTrack(track) }) {
                            Icon(
                                imageVector = MusiqueIcons.Download,
                                contentDescription = "Download",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
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
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        // Playing queue as a tree: the queue in order, with the
        // playing video's parts as indented compact child rows
        // beneath it (no covers), the current track highlighted.
        // Tapping a video row with known parts only expands or
        // collapses its parts, never plays; only tapping a
        // specific part row plays it. Rows without known parts
        // play on tap as usual. The parts start expanded whenever
        // the sheet opens and whenever the video changes.
        if (queueSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { queueSheetOpen = false },
                sheetState = rememberModalBottomSheetState(),
            ) {
                var partsExpanded by remember(track?.id) { mutableStateOf(true) }
                // One flattened list: each queue row, followed by
                // its parts when it is the playing video and the
                // parts are expanded.
                val sheetRows = remember(queue, currentParts, track, partsExpanded) {
                    buildList {
                        for (row in queue) {
                            add(QueueSheetRow.Video(row))
                            if (row.id == track?.id &&
                                currentParts.size > 1 &&
                                partsExpanded
                            ) {
                                for (part in currentParts) {
                                    add(QueueSheetRow.Part(part))
                                }
                            }
                        }
                    }
                }
                val queueListState = rememberLazyListState()
                // Opening the sheet jumps straight to the playing
                // track instead of starting at the top of the
                // queue: the exact part row when it is known,
                // else the playing video's row. Only on open:
                // switching tracks while the sheet stays open
                // must not yank the list out from under the user.
                LaunchedEffect(Unit) {
                    val partIndex = sheetRows.indexOfFirst { row ->
                        row is QueueSheetRow.Part && row.track.key == track?.key
                    }
                    val currentIndex = if (partIndex >= 0) {
                        partIndex
                    } else {
                        sheetRows.indexOfFirst { row ->
                            row is QueueSheetRow.Video && row.track.id == track?.id
                        }
                    }
                    if (currentIndex >= 0) {
                        // +1: the "Queue" header is the first item.
                        queueListState.scrollToItem(currentIndex + 1)
                    }
                }
                LazyColumn(
                    state = queueListState,
                    modifier = Modifier.padding(bottom = 32.dp),
                ) {
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
                    itemsIndexed(
                        sheetRows,
                        key = { _, sheetRow -> sheetRow.key },
                    ) { index, sheetRow ->
                        when (sheetRow) {
                            is QueueSheetRow.Video -> {
                                // Only the playing video has known
                                // parts; its row toggles them and
                                // never plays, with a chevron
                                // showing the state. Every other
                                // row plays on tap.
                                val hasParts = sheetRow.track.id == track?.id &&
                                    currentParts.size > 1
                                SongRow(
                                    track = sheetRow.track,
                                    // Rows are videos: match by video
                                    // id, so the row stays highlighted
                                    // while any of its parts plays.
                                    isCurrent = sheetRow.track.id == track?.id,
                                    onClick = {
                                        if (hasParts) {
                                            partsExpanded = !partsExpanded
                                        } else {
                                            // The sheet stays open:
                                            // switching songs from the
                                            // queue must not drop back
                                            // to the player page.
                                            viewModel.playFromQueue(sheetRow.track)
                                        }
                                    },
                                    trailing = {
                                        if (hasParts) {
                                            Icon(
                                                imageVector = if (partsExpanded) {
                                                    MusiqueIcons.ChevronDown
                                                } else {
                                                    MusiqueIcons.ChevronRight
                                                },
                                                contentDescription = if (partsExpanded) {
                                                    "Collapse parts"
                                                } else {
                                                    "Expand parts"
                                                },
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                )
                            }

                            is QueueSheetRow.Part -> SongRow(
                                track = sheetRow.track,
                                isCurrent = sheetRow.track.key == track?.key,
                                onClick = {
                                    // The sheet stays open, same as
                                    // the video rows above.
                                    viewModel.playPart(sheetRow.track)
                                },
                                trailing = {},
                                modifier = Modifier.padding(
                                    start = PART_ROW_INDENT_DP.dp,
                                ),
                                compact = true,
                            )
                        }
                        if (index < sheetRows.lastIndex) {
                            // A part row has no cover, so its
                            // divider starts at its text: the
                            // indent plus the row's own start
                            // padding. A video row keeps the
                            // cover-aligned inset.
                            val dividerStartDp = if (sheetRow is QueueSheetRow.Part) {
                                PART_ROW_DIVIDER_START_DP
                            } else {
                                SONG_ROW_DIVIDER_START_DP
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = dividerStartDp.dp),
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

/**
 * One row of the queue sheet's flattened tree: a queue video, or
 * one of the playing video's parts. Keys are prefixed so a part
 * row never collides with its video row when both share a key.
 */
private sealed interface QueueSheetRow {
    val track: Track
    val key: String

    data class Video(override val track: Track) : QueueSheetRow {
        override val key: String get() = "video:${track.key}"
    }

    data class Part(override val track: Track) : QueueSheetRow {
        override val key: String get() = "part:${track.key}"
    }
}
