package com.siyu.musique.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The update install path: the downloaded APK's bytes are
 * streamed into a PackageInstaller session and committed, the
 * way open source installers do it. The system shows its usual
 * install confirmation; the user taps once, and the update
 * installs over the old build with all app data kept, because
 * the package and the signing key never change. Once the
 * session commits, the install proceeds from the session's
 * copy, so the staged file is deleted and nothing is left on
 * disk. Android-only: excluded from the kotlinc unit suite,
 * covered by the full-compile check. The session ordering
 * (write, commit, delete, abandon on failure) lives in
 * [SessionApkInstaller] and is unit tested behind a fake.
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

    /**
     * The session installer wired to the real PackageInstaller.
     * The file copy runs on Dispatchers.IO: the session write
     * streams the whole APK, which must never block the main
     * thread.
     */
    fun createApkInstaller(context: Context): UpdateApkInstaller {
        val staged = SessionApkInstaller(
            PackageInstallerSessionFactory(context.applicationContext),
        )
        return object : UpdateApkInstaller {
            override suspend fun install(apkFile: File): Boolean =
                withContext(Dispatchers.IO) { staged.install(apkFile) }
        }
    }
}

/** Opens PackageInstaller sessions for the app's own package, sized to the staged APK. */
private class PackageInstallerSessionFactory(
    private val context: Context,
) : UpdateInstallSessionFactory {

    override fun openSession(apkFile: File): UpdateInstallSession {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            setSize(apkFile.length())
        }
        val sessionId = packageInstaller.createSession(params)
        return PackageInstallerSession(
            packageInstaller,
            sessionId,
            statusIntentSender(context),
        )
    }

    /**
     * The status receiver the system fills with the install
     * result: mutable on API 31 and later, where the system
     * writes its status extras into the PendingIntent. The
     * receiver is manifest-declared and exported=false.
     */
    private fun statusIntentSender(context: Context): IntentSender {
        val intent = Intent(context, UpdateInstallStatusReceiver::class.java).apply {
            action = UpdateInstallStatusReceiver.ACTION_INSTALL_STATUS
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, STATUS_REQUEST_CODE, intent, flags).intentSender
    }

    private companion object {
        const val STATUS_REQUEST_CODE = 0
    }
}

/**
 * One PackageInstaller session: the APK streams in through
 * openWrite, then commit hands it to the installer. The session
 * is reopened for the commit: staging survives closing, and
 * reopening keeps the write and the commit on separate calls.
 */
private class PackageInstallerSession(
    private val packageInstaller: PackageInstaller,
    private val sessionId: Int,
    private val statusReceiver: IntentSender,
) : UpdateInstallSession {

    override fun writeApk(apkFile: File) {
        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite(SESSION_APK_NAME, 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
        }
    }

    override fun commit() {
        packageInstaller.openSession(sessionId).use { session ->
            session.commit(statusReceiver)
        }
    }

    override fun abandon() {
        runCatching { packageInstaller.abandonSession(sessionId) }
    }

    private companion object {
        const val SESSION_APK_NAME = "musique.apk"
    }
}
