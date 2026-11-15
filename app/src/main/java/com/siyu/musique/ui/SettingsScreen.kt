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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.data.UpdateFailureKind
import com.siyu.musique.data.UpdateInstaller
import com.siyu.musique.ui.theme.MusiqueIcons
import java.io.File
import java.util.Locale

/**
 * Settings tab: the cache size (audio + covers) with a clear
 * button, the debug log entry, and the manual update check
 * showing the installed version. No title, no back chevron:
 * it is a top-level tab. The size is measured on every entry;
 * clearing re-measures so the freed size shows at once. The
 * shared mini player bar and the tab bar sit below this screen,
 * owned by the app shell.
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
    val context = LocalContext.current

    var showUpdateDialog by remember { mutableStateOf(false) }
    var needsInstallPermission by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadCacheSize()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cache",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = cacheSizeBytes?.let { formatCacheSize(it) } ?: "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { viewModel.clearCache() },
                enabled = !clearingCache && (cacheSizeBytes ?: 0L) > 0L,
            ) {
                Icon(
                    imageVector = MusiqueIcons.Delete,
                    contentDescription = "Clear cache",
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
                text = "Debug log",
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
                text = "检查更新",
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
                            text = "正在检查更新",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                UpdateUiState.UpToDate -> {
                    Text(
                        text = "已经是最新版本",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is UpdateUiState.Available -> {
                    Column {
                        Text(
                            text = "发现新版本 ${state.tag}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "大小 ${formatCacheSize(state.apkSize)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is UpdateUiState.Downloading -> {
                    Column {
                        Text(
                            text = "下载中" +
                                (state.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""),
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
                            text = "下载完成",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (needsInstallPermission) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "需要先允许安装未知来源应用",
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
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                is UpdateUiState.Available -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            imageVector = MusiqueIcons.Download,
                            contentDescription = "Download update",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                is UpdateUiState.ReadyToInstall -> {
                    IconButton(onClick = { onInstall(state.apkFile) }) {
                        Icon(
                            imageVector = MusiqueIcons.Check,
                            contentDescription = "Install update",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                is UpdateUiState.Failed -> {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = MusiqueIcons.Refresh,
                            contentDescription = "Retry",
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
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

/** The dialog line for an update failure kind. */
private fun updateFailureMessage(kind: UpdateFailureKind): String = when (kind) {
    UpdateFailureKind.NETWORK -> "检查失败，请检查网络后重试"
    UpdateFailureKind.RATE_LIMITED -> "请求太频繁，请稍后再试"
    UpdateFailureKind.PARSE -> "版本信息无法识别，请稍后再试"
    UpdateFailureKind.MISSING_ASSET -> "最新发布中没有找到安装包"
    UpdateFailureKind.DOWNLOAD_FAILED -> "下载失败，请重试"
    UpdateFailureKind.INSTALL_FAILED -> "安装失败，请重试"
}

/** Human cache size: bytes, KB, or MB with one decimal. */
private fun formatCacheSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
