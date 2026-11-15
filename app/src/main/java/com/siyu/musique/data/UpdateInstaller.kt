package com.siyu.musique.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Fires the system installer for a downloaded APK, and the
 * "install unknown apps" settings page when Musique may not
 * install packages yet. Android-only: excluded from the kotlinc
 * unit suite, covered by the full-compile check. The APK lives
 * under cacheDir/updates and reaches the installer through the
 * FileProvider declared in the manifest (res/xml/file_paths.xml).
 * The system shows one install confirmation; the user taps once,
 * and the update installs over the old build with all app data
 * kept, because the package and the signing key never change.
 */
object UpdateInstaller {

    /** True when the system lets this app request package installs (the user allowed "install unknown apps"). */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Opens the system settings page where the user allows this app to install unknown apps. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Fires the system install prompt for [apkFile]. */
    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.$FILE_PROVIDER_AUTHORITY_SUFFIX",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = "fileprovider"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
