package com.siyu.musique.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.siyu.musique.R
import com.siyu.musique.core.network.SMALL_COVER_HEIGHT
import com.siyu.musique.core.network.SMALL_COVER_WIDTH
import com.siyu.musique.core.network.sizedCoverUrl
import com.siyu.musique.model.Playlist
import com.siyu.musique.ui.theme.MusiqueIcons

/** "1 song" / "N songs", shared by the playlist rows and the detail header. Localized at composition time. */
@Composable
internal fun songCountLabel(count: Int): String =
    pluralStringResource(R.plurals.song_count, count, count)

/**
 * The name shown for a playlist. The two built-in playlists
 * show their localized names; a user playlist shows the name
 * it was given. The stored names never change: only the
 * display is localized, keyed on the playlist identity.
 */
@Composable
internal fun playlistDisplayName(playlist: Playlist): String = when {
    playlist.isDefault -> stringResource(R.string.default_playlist_name)
    playlist.isDownloads -> stringResource(R.string.downloads_playlist_name)
    else -> playlist.name
}

/**
 * Playlists screen: no title, a back icon at the top left and a
 * plus icon button at the top right to create a new playlist, and
 * flat rows with a
 * small stacked-covers thumbnail, the playlist name, the song
 * count, and a chevron. Tapping a row opens the detail screen.
 * Long-pressing a custom playlist opens a menu with rename and
 * delete; the default playlist and Downloads cannot be renamed
 * or deleted, so no menu opens for them. The default playlist
 * always exists; creating a playlist asks for its name in a
 * dialog, and blank names are rejected.
 */
@Composable
fun PlaylistsScreen(
    viewModel: SearchViewModel,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Opaque: during the predictive back gesture the
            // outgoing page slides over the incoming one, and a
            // transparent page would show both at once.
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MusiqueIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    newName = ""
                    showCreateDialog = true
                },
            ) {
                Icon(
                    imageVector = MusiqueIcons.Add,
                    contentDescription = stringResource(R.string.create_playlist),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(playlists, key = { playlist -> playlist.id }) { playlist ->
                PlaylistRowWithMenu(
                    playlist = playlist,
                    viewModel = viewModel,
                    onClick = { onOpenDetail(playlist.id) },
                )
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.new_playlist_title)) },
            text = {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text(stringResource(R.string.playlist_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                IconButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        viewModel.createPlaylist(newName)
                        showCreateDialog = false
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Check,
                        contentDescription = stringResource(R.string.create),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            dismissButton = {
                IconButton(onClick = { showCreateDialog = false }) {
                    Icon(
                        imageVector = MusiqueIcons.Clear,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        )
    }
}

/** The playlist card's outer padding, shared by the card and the menu anchor math. */
private const val PLAYLIST_ROW_HORIZONTAL_PADDING_DP = 16
private const val PLAYLIST_ROW_VERTICAL_PADDING_DP = 4

/**
 * A playlist row with its long-press menu: a custom playlist
 * offers rename and delete, the default playlist and Downloads
 * offer nothing and never show a menu. The menu opens directly
 * below the pressed card, starting at the finger's horizontal
 * position instead of the row's left edge, and deleting asks
 * first through a confirmation dialog. Shared by the home
 * playlists section and the Playlists screen.
 */
@Composable
internal fun PlaylistRowWithMenu(
    playlist: Playlist,
    viewModel: SearchViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val editable = !playlist.isDefault && !playlist.isDownloads
    Box(modifier = modifier) {
        PlaylistRow(
            playlist = playlist,
            onClick = onClick,
            onLongClick = if (editable) {
                { pressOffset ->
                    // The menu opens directly below its anchor,
                    // so the vertical offset stays zero: adding
                    // the press height would push the menu a row
                    // further down, away from the pressed card.
                    // Only the horizontal position follows the
                    // finger, with the card's outer padding
                    // added back in: the press position is
                    // relative to the card itself, the menu
                    // anchors to the surrounding box.
                    menuOffset = with(density) {
                        DpOffset(
                            pressOffset.x.toDp() +
                                PLAYLIST_ROW_HORIZONTAL_PADDING_DP.dp,
                            0.dp,
                        )
                    }
                    showMenu = true
                }
            } else {
                null
            },
        )
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = menuOffset,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                onClick = {
                    showMenu = false
                    showRenameDialog = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                    showMenu = false
                    showDeleteDialog = true
                },
            )
        }
    }
    if (showRenameDialog) {
        RenamePlaylistDialog(
            initialName = playlist.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                viewModel.renamePlaylist(playlist.id, name)
                showRenameDialog = false
            },
        )
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(36.dp),
            title = { Text(stringResource(R.string.delete_playlist)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_playlist_message,
                        playlistDisplayName(playlist),
                    ),
                )
            },
            confirmButton = {
                IconButton(
                    onClick = {
                        viewModel.deletePlaylist(playlist.id)
                        showDeleteDialog = false
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Check,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            dismissButton = {
                IconButton(onClick = { showDeleteDialog = false }) {
                    Icon(
                        imageVector = MusiqueIcons.Clear,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        )
    }
}

/**
 * The rename dialog, from the playlist list's long-press menu.
 * Mirrors the create-playlist dialog: the field starts with the
 * current name, blank names are rejected, duplicate names are
 * allowed.
 */
@Composable
internal fun RenamePlaylistDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(36.dp),
        title = { Text(stringResource(R.string.rename_playlist)) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.playlist_name_hint)) },
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
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            IconButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) },
            ) {
                Icon(
                    imageVector = MusiqueIcons.Check,
                    contentDescription = stringResource(R.string.rename),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        dismissButton = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = MusiqueIcons.Clear,
                    contentDescription = stringResource(R.string.cancel),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

/**
 * One playlist as a card: a light-grey card with a single square
 * cover, the name, and the song count. Tapping the card opens the
 * playlist. Shared by the home playlists section and the Playlists
 * screen. [onLongClick] is null for rows with no long-press
 * action; the list's menu rows pass one that opens the menu
 * below the card, aligned with the press position. The gestures run through [detectTapGestures]
 * instead of combinedClickable so the long press reports where
 * the finger landed; the press ripple and the long-press haptic
 * are wired back in by hand.
 */
@Composable
internal fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((Offset) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = PLAYLIST_ROW_HORIZONTAL_PADDING_DP.dp,
                vertical = PLAYLIST_ROW_VERTICAL_PADDING_DP.dp,
            )
            .clip(RoundedCornerShape(16.dp))
            .indication(interactionSource, LocalIndication.current)
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onPress = { pressOffset ->
                        val press = PressInteraction.Press(pressOffset)
                        interactionSource.emit(press)
                        if (tryAwaitRelease()) {
                            interactionSource.emit(PressInteraction.Release(press))
                        } else {
                            interactionSource.emit(PressInteraction.Cancel(press))
                        }
                    },
                    onTap = { onClick() },
                    onLongPress = { pressOffset ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick?.invoke(pressOffset)
                    },
                )
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCover(playlist)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlistDisplayName(playlist),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = songCountLabel(playlist.trackCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The playlist cover: the first track's cover, or a grey square
 * with a queue icon when the playlist has no covers yet.
 */
@Composable
private fun PlaylistCover(
    playlist: Playlist,
    modifier: Modifier = Modifier,
) {
    val cover = playlist.tracks.firstNotNullOfOrNull { it.cover.ifEmpty { null } }
    if (cover != null) {
        AsyncImage(
            model = sizedCoverUrl(cover, SMALL_COVER_WIDTH, SMALL_COVER_HEIGHT),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            modifier = modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MusiqueIcons.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
