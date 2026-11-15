package com.siyu.musique.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.siyu.musique.core.logging.MusiqueLog

/**
 * The install session's status callback, manifest-declared and
 * exported=false. The system reports three kinds of result
 * here: a pending user action, which carries the confirmation
 * intent the session needs before it can proceed; success,
 * which needs nothing, because the install replaces the
 * process; and any failure, which goes back to the live
 * ViewModel through [UpdateInstallStatusRegistry] so the
 * settings dialog shows its failure line plus retry. Never
 * throws: a bad broadcast only logs.
 */
class UpdateInstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        runCatching { handleStatus(context, intent) }
            .onFailure {
                MusiqueLog.w(TAG, "status broadcast failed (${it::class.simpleName})")
            }
    }

    private fun handleStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val userAction = pendingUserAction(intent)
                val launched = userAction != null && runCatching {
                    userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(userAction)
                }.isSuccess
                if (!launched) {
                    MusiqueLog.w(TAG, "install confirmation intent would not launch")
                    UpdateInstallStatusRegistry.handler?.onInstallFailed()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                MusiqueLog.i(TAG, "install succeeded")
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                MusiqueLog.w(TAG, "install failed (status=$status): $message")
                UpdateInstallStatusRegistry.handler?.onInstallFailed()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pendingUserAction(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        }

    companion object {
        /** The action on the status PendingIntent, so stray broadcasts never reach the receiver by accident. */
        const val ACTION_INSTALL_STATUS = "com.siyu.musique.UPDATE_INSTALL_STATUS"
        private const val TAG = "Update"
    }
}
