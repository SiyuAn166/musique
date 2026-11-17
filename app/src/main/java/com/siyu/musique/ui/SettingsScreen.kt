package com.siyu.musique.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.R
import com.siyu.musique.data.LanguagePreference
import com.siyu.musique.data.UpdateFailureKind
import com.siyu.musique.data.UpdateInstaller
import com.siyu.musique.ui.theme.MusiqueIcons
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Settings tab: the language choice, the cache size (audio +
 * covers) with a clear button, the debug log entry, and the
 * manual update check showing the installed version.
 * No title, no back chevron: it is a top-level tab. The size
 * is measured on every entry; clearing re-measures so the
 * freed size shows at once. The shared mini player bar and
 * the tab bar sit below this screen, owned by the app shell.
 */
@Composable
fun SettingsScreen(
    viewModel: SearchViewModel,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val clearingCache by viewModel.clearingCache.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val languagePreference by viewModel.languagePreference.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showUpdateDialog by remember { mutableStateOf(false) }
    var needsInstallPermission by remember { mutableStateOf(false) }

    /** Whether the clear-cache confirmation dialog is open. */
    var showClearCacheDialog by remember { mutableStateOf(false) }

    /** Whether the language dropdown menu is open. */
    var languageMenuExpanded by remember { mutableStateOf(false) }
    val languageScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadCacheSize()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Opaque: during the predictive back gesture the
            // outgoing page slides over the incoming one, and a
            // transparent page would show both at once.
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Language: the row shows the current choice, and
        // tapping it opens a dropdown with the four options.
        // Picking a different one saves it, then recreates the
        // Activity so the new Activity reads the new value in
        // attachBaseContext and every string resolves in the
        // chosen language. The save suspends until it lands; a
        // failed save leaves the state unchanged and skips the
        // recreate, so the row never claims a language that
        // will not survive.
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { languageMenuExpanded = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = languageOptionLabel(languagePreference),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = MusiqueIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = languageMenuExpanded,
                onDismissRequest = { languageMenuExpanded = false },
            ) {
                LanguagePreference.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(languageOptionLabel(option)) },
                        onClick = {
                            languageMenuExpanded = false
                            if (option != languagePreference) {
                                languageScope.launch {
                                    viewModel.setLanguagePreference(option)
                                    if (viewModel.languagePreference.value == option) {
                                        (context as? Activity)?.recreate()
                                    }
                                }
                            }
                        },
                        leadingIcon = {
                            if (option == languagePreference) {
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

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_cache),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = cacheSizeBytes?.let { formatCacheSize(it) } ?: "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Never wrap or cut the number: the label and the
                // spacer absorb the slack, this text takes what it
                // needs on one line.
                softWrap = false,
            )
            IconButton(
                onClick = { showClearCacheDialog = true },
                enabled = !clearingCache && (cacheSizeBytes ?: 0L) > 0L,
            ) {
                Icon(
                    imageVector = MusiqueIcons.Delete,
                    contentDescription = stringResource(R.string.clear_cache),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenLog)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_debug_log),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = MusiqueIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    needsInstallPermission = false
                    showUpdateDialog = true
                    viewModel.checkForUpdate()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.check_for_updates),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (viewModel.installedVersionName.isNotBlank()) {
                Text(
                    text = viewModel.installedVersionName,
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

    }

    if (showUpdateDialog) {
        UpdateDialog(
            state = updateState,
            needsInstallPermission = needsInstallPermission,
            onDownload = { viewModel.downloadUpdate() },
            onRetry = { viewModel.retryUpdate() },
            onInstall = { apkFile ->
                if (UpdateInstaller.canInstall(context)) {
                    viewModel.installUpdate(apkFile)
                } else {
                    needsInstallPermission = true
                    UpdateInstaller.openInstallPermissionSettings(context)
                }
            },
            onDismiss = {
                showUpdateDialog = false
                viewModel.dismissUpdateState()
            },
        )
    }

    // Clearing the cache asks first: the cached audio and covers
    // are freed at once, and the size re-measures after.
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(36.dp),
            title = { Text(stringResource(R.string.clear_cache)) },
            text = { Text(stringResource(R.string.clear_cache_message)) },
            confirmButton = {
                IconButton(
                    onClick = {
                        showClearCacheDialog = false
                        viewModel.clearCache()
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Check,
                        contentDescription = stringResource(R.string.clear_cache),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            dismissButton = {
                IconButton(onClick = { showClearCacheDialog = false }) {
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
 * The update dialog: one small dialog for every check state, icon
 * buttons only, no title. Dismissing it while a check or download
 * runs leaves the work running; reopening the row shows the
 * running state again.
 */
@Composable
private fun UpdateDialog(
    state: UpdateUiState,
    needsInstallPermission: Boolean,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            when (state) {
                UpdateUiState.Idle,
                UpdateUiState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.checking_for_updates),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                UpdateUiState.UpToDate -> {
                    Text(
                        text = stringResource(R.string.up_to_date),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is UpdateUiState.Available -> {
                    Column {
                        Text(
                            text = stringResource(R.string.update_available, state.tag),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.update_size, formatCacheSize(state.apkSize)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is UpdateUiState.Downloading -> {
                    Column {
                        Text(
                            text = state.fraction?.let { fraction ->
                                stringResource(R.string.downloading_percent, (fraction * 100).toInt())
                            } ?: stringResource(R.string.downloading),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        val fraction = state.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onSurface,
                                trackColor = MaterialTheme.colorScheme.outlineVariant,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onSurface,
                                trackColor = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
                is UpdateUiState.ReadyToInstall -> {
                    Column {
                        Text(
                            text = stringResource(R.string.download_complete),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (needsInstallPermission) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.allow_unknown_apps),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is UpdateUiState.Failed -> {
                    Text(
                        text = updateFailureMessage(state.kind),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                UpdateUiState.UpToDate -> {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = MusiqueIcons.Check,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                is UpdateUiState.Available -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            imageVector = MusiqueIcons.Download,
                            contentDescription = stringResource(R.string.download_update),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                is UpdateUiState.ReadyToInstall -> {
                    IconButton(onClick = { onInstall(state.apkFile) }) {
                        Icon(
                            imageVector = MusiqueIcons.Check,
                            contentDescription = stringResource(R.string.install_update),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                is UpdateUiState.Failed -> {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = MusiqueIcons.Refresh,
                            contentDescription = stringResource(R.string.retry),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                else -> {
                    // Checking, downloading, and idle have no
                    // action: the dismiss button closes the dialog
                    // and the work keeps running.
                    Box(modifier = Modifier.size(0.dp))
                }
            }
        },
        dismissButton = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = MusiqueIcons.Clear,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

/** The dialog line for an update failure kind, localized at composition time. */
@Composable
private fun updateFailureMessage(kind: UpdateFailureKind): String = when (kind) {
    UpdateFailureKind.NETWORK -> stringResource(R.string.update_error_network)
    UpdateFailureKind.RATE_LIMITED -> stringResource(R.string.update_error_rate_limited)
    UpdateFailureKind.PARSE -> stringResource(R.string.update_error_parse)
    UpdateFailureKind.MISSING_ASSET -> stringResource(R.string.update_error_missing_asset)
    UpdateFailureKind.DOWNLOAD_FAILED -> stringResource(R.string.update_error_download_failed)
    UpdateFailureKind.INSTALL_FAILED -> stringResource(R.string.update_error_install_failed)
}

/**
 * The label for a language option, in its own language: the
 * fixed languages name themselves, and the follow-system
 * option is localized like any other string.
 */
@Composable
private fun languageOptionLabel(preference: LanguagePreference): String = when (preference) {
    LanguagePreference.SYSTEM -> stringResource(R.string.language_system)
    LanguagePreference.ENGLISH -> "English"
    LanguagePreference.CHINESE -> "简体中文"
    LanguagePreference.FRENCH -> "Français"
}

/** Human cache size: bytes, KB, MB, or GB with one decimal. The GB tier keeps large caches short: without it a multi-gigabyte cache printed a long MB number that ran out of room on the row. */
private fun formatCacheSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
