package com.siyu.musique.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.model.Track
import com.siyu.musique.ui.theme.MusiqueIcons
import kotlinx.coroutines.flow.distinctUntilChanged

/** Load the next page when the last visible row is this close to the end of the list. */
private const val LOAD_MORE_THRESHOLD = 3

/**
 * Search screen: no title, just the list icon (playlists) and
 * three-dot icon (settings menu, with the debug log inside) at
 * the top right, a light-grey rounded
 * search field, and flat result rows with hairline dividers. Search
 * triggers on the keyboard search action and on tapping the
 * magnifier icon. Tapping a row plays it (cid fill + fresh play URL
 * first); the plus icon on a row opens the playlist chooser.
 * Search errors show a distinct message per failure kind with a
 * retry icon button; player errors show their own message with a
 * retry icon button above the mini player bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenPlayer: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    /** The track waiting for a playlist choice, or null when the chooser is closed. */
    var chooserTrack by remember { mutableStateOf<Track?>(null) }

    /** Whether the settings (three-dot) menu is open. The debug log lives inside it. */
    var settingsMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenPlaylists) {
                Icon(
                    imageVector = MusiqueIcons.ListBulleted,
                    contentDescription = "Playlists",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Box {
                IconButton(onClick = { settingsMenuExpanded = true }) {
                    Icon(
                        imageVector = MusiqueIcons.MoreHoriz,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(
                    expanded = settingsMenuExpanded,
                    onDismissRequest = { settingsMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Debug log") },
                        onClick = {
                            settingsMenuExpanded = false
                            onOpenLog()
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search songs") },
            leadingIcon = {
                IconButton(onClick = { viewModel.search(query) }) {
                    Icon(
                        imageVector = MusiqueIcons.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = MusiqueIcons.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { viewModel.search(query) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))

        when (val state = searchState) {
            SearchUiState.Idle -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Type a keyword and search.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SearchUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is SearchUiState.Empty -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No songs found for \"${state.query}\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is SearchUiState.Error -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message)
                        Spacer(modifier = Modifier.height(16.dp))
                        IconButton(onClick = { viewModel.retry() }) {
                            Icon(
                                imageVector = MusiqueIcons.Refresh,
                                contentDescription = "Retry search",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            is SearchUiState.Success -> {
                val listState = rememberLazyListState()
                // Load the next page when the user scrolls near the
                // end. distinctUntilChanged keeps sitting at the
                // bottom to one load; scrolling away and back
                // re-arms the trigger. The ViewModel guards the
                // rest (no more pages, already loading).
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val layout = listState.layoutInfo
                        val lastVisible =
                            layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                        lastVisible >= 0 &&
                            lastVisible >= layout.totalItemsCount - LOAD_MORE_THRESHOLD
                    }
                        .distinctUntilChanged()
                        .collect { nearEnd ->
                            if (nearEnd) viewModel.loadMore()
                        }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(state.tracks, key = { _, track -> track.key }) { index, track ->
                        SongRow(
                            track = track,
                            // Rows are videos: stay highlighted while any
                            // part of the video plays, not just part one.
                            isCurrent = track.id == currentTrack?.id,
                            onClick = { viewModel.play(track) },
                            trailing = {
                                IconButton(onClick = { chooserTrack = track }) {
                                    Icon(
                                        imageVector = MusiqueIcons.AddCircleOutline,
                                        contentDescription = "Add to playlist",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                        )
                        if (index < state.tracks.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(
                                    start = SONG_ROW_DIVIDER_START_DP.dp,
                                ),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                    if (state.loadingMore) {
                        item(key = "loading-more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Player error row: a failed cid fill, URL resolve, or
        // playback shows its message here with a retry icon button.
        val errorState = playerState as? PlayerUiState.Error
        if (errorState != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = errorState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { viewModel.retryPlayback() }) {
                    Icon(
                        imageVector = MusiqueIcons.Refresh,
                        contentDescription = "Retry playback",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        val track = currentTrack
        if (track != null) {
            MiniPlayerBar(
                track = track,
                isPlaying = isPlaying,
                playerState = playerState,
                progress = progress,
                onTogglePlayPause = { viewModel.togglePlayPause() },
                onRetry = { viewModel.retryPlayback() },
                onOpenPlayer = onOpenPlayer,
            )
        }
    }

    // Playlist chooser: tapping a playlist adds the track (adding a
    // track already present is a no-op in the ViewModel) and closes.
    val pending = chooserTrack
    if (pending != null) {
        ModalBottomSheet(
            onDismissRequest = { chooserTrack = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            ) {
                Text(
                    text = "Add to playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addTrackToPlaylist(playlist.id, pending)
                                chooserTrack = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                            Text(
                                text = songCountLabel(playlist.trackCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = MusiqueIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
