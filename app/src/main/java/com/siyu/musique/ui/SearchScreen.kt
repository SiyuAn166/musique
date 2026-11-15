package com.siyu.musique.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.model.Track
import com.siyu.musique.ui.theme.MusiqueIcons
import com.siyu.musique.ui.theme.MusiqueScriptFamily
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Load the next page when the last visible row is this close to the end of the list. */
private const val LOAD_MORE_THRESHOLD = 3

/** How long the add-to-playlist notice stays up. */
private const val ADD_NOTICE_DURATION_MS: Long = 2000L

/** How far the add notice floats above the bottom of the screen: clears the mini player bar. */
private const val ADD_NOTICE_BOTTOM_OFFSET_DP: Int = 88

/**
 * Search screen: the script "Musique" wordmark at the top left
 * with the three-dot icon (settings menu, with the debug log
 * inside) at the top right, a light-grey capsule search field,
 * and flat result rows with hairline dividers. Search
 * triggers on the keyboard search action and on tapping the
 * magnifier icon. Typing shows a suggestion dropdown from
 * Bilibili's suggest API under the field; tapping a suggestion
 * searches it. Clearing the field returns to the home playlists,
 * and so does system Back while a search is showing.
 * Tapping a row plays it (cid fill + fresh play URL
 * first); the plus icon on a row opens the playlist chooser, and
 * adding shows a short popup notice (a different one when the
 * track is already in the playlist).
 * Search errors show a distinct message per failure kind with a
 * retry icon button; player errors show their own message with a
 * retry icon button above the mini player bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenPlayer: () -> Unit,
    onOpenPlaylistDetail: (String) -> Unit = {},
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val resolvedQualityLabel by viewModel.resolvedQualityLabel.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var queryField by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    /** The track waiting for a playlist choice, or null when the chooser is closed. */
    var chooserTrack by remember { mutableStateOf<Track?>(null) }

    /** Short-lived notice after adding a track, or null when hidden. */
    var addNotice by remember { mutableStateOf<String?>(null) }
    var addNoticeDismissJob by remember { mutableStateOf<Job?>(null) }
    val noticeScope = rememberCoroutineScope()

    /** Whether the settings (three-dot) menu is open. Settings and the debug log live inside it. */
    var settingsMenuExpanded by remember { mutableStateOf(false) }

    /**
     * Applies a field value: suggestions follow typing, and
     * clearing the field returns to the home playlists at once,
     * with no extra tap on search. The field keeps a
     * [TextFieldValue] (not a plain String) so programmatic
     * changes can place the cursor, e.g. at the end after a
     * suggestion tap.
     */
    fun applyQueryField(newValue: TextFieldValue) {
        queryField = newValue
        viewModel.onQueryChanged(newValue.text)
        if (newValue.text.isBlank()) viewModel.search("")
    }

    // System Back returns to the home playlists while a search is
    // showing (or text is typed); a second Back on clean home
    // exits the app as usual.
    BackHandler(
        enabled = searchState != SearchUiState.Idle || queryField.text.isNotBlank(),
    ) {
        applyQueryField(TextFieldValue(""))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Musique",
                fontFamily = MusiqueScriptFamily,
                fontSize = 40.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
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
                        text = { Text("Settings") },
                        onClick = {
                            settingsMenuExpanded = false
                            onOpenSettings()
                        },
                    )
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
            value = queryField,
            onValueChange = { applyQueryField(it) },
            placeholder = { Text("Search songs") },
            leadingIcon = {
                IconButton(onClick = { viewModel.search(queryField.text) }) {
                    Icon(
                        imageVector = MusiqueIcons.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingIcon = {
                if (queryField.text.isNotEmpty()) {
                    IconButton(onClick = { applyQueryField(TextFieldValue("")) }) {
                        Icon(
                            imageVector = MusiqueIcons.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(50.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.onSurface,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { viewModel.search(queryField.text) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Suggestion dropdown: a floating card under the field.
        // Tapping a suggestion fills the field (cursor at the end)
        // and searches it; the dropdown hides on search, on clear,
        // and on blank.
        if (suggestions.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    suggestions.forEach { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    queryField = TextFieldValue(
                                        text = suggestion,
                                        selection = TextRange(suggestion.length),
                                    )
                                    viewModel.search(suggestion)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = MusiqueIcons.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        when (val state = searchState) {
            SearchUiState.Idle -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Playlists",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    newPlaylistName = ""
                                    showCreatePlaylistDialog = true
                                },
                            ) {
                                Icon(
                                    imageVector = MusiqueIcons.Add,
                                    contentDescription = "Create playlist",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                    items(playlists, key = { playlist -> playlist.id }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            onClick = { onOpenPlaylistDetail(playlist.id) },
                        )
                    }
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
                                tint = MaterialTheme.colorScheme.onSurface,
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
                                        imageVector = MusiqueIcons.Add,
                                        contentDescription = "Add to playlist",
                                        tint = MaterialTheme.colorScheme.onSurface,
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

        MiniPlayerBar(
            track = currentTrack,
            isPlaying = isPlaying,
            playerState = playerState,
            progress = progress,
            onTogglePlayPause = { viewModel.togglePlayPause() },
            onRetry = { viewModel.retryPlayback() },
            onOpenPlayer = onOpenPlayer,
            onPrevious = { viewModel.playPrevious() },
            onNext = { viewModel.playNext() },
            qualityLabel = resolvedQualityLabel,
        )
    }

    // Add notice: a small black pill floating above the mini
    // player bar for a couple of seconds after a track is added.
    // Tapping outside dismisses it early.
    val notice = addNotice
    if (notice != null) {
        val noticeOffsetPx = with(LocalDensity.current) {
            ADD_NOTICE_BOTTOM_OFFSET_DP.dp.roundToPx()
        }
        Popup(
            alignment = Alignment.BottomCenter,
            offset = IntOffset(0, -noticeOffsetPx),
            onDismissRequest = { addNotice = null },
        ) {
            Surface(
                color = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }
    }

    // Playlist chooser: tapping a playlist adds the track, shows a
    // short notice (a different one when the track is already
    // present), and closes.
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
                                val added = viewModel.addTrackToPlaylist(playlist.id, pending)
                                chooserTrack = null
                                addNoticeDismissJob?.cancel()
                                addNotice = if (added) "添加成功" else "已在歌单中"
                                addNoticeDismissJob = noticeScope.launch {
                                    delay(ADD_NOTICE_DURATION_MS)
                                    addNotice = null
                                }
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

    // Create playlist dialog, from the plus icon on the home
    // playlists header. Blank names are rejected, same as the
    // playlists screen's dialog.
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("New playlist") },
            text = {
                TextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                IconButton(
                    enabled = newPlaylistName.isNotBlank(),
                    onClick = {
                        viewModel.createPlaylist(newPlaylistName)
                        showCreatePlaylistDialog = false
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Check,
                        contentDescription = "Create",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            dismissButton = {
                IconButton(onClick = { showCreatePlaylistDialog = false }) {
                    Icon(
                        imageVector = MusiqueIcons.Clear,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        )
    }
}
