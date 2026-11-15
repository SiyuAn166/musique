package com.siyu.musique.data

import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * One install session: the staged APK's bytes, written once and
 * then committed. The Android implementation wraps a
 * PackageInstaller session; tests fake this interface.
 * [abandon] must never throw: it runs on the failure path,
 * where a second failure must not hide the first.
 */
interface UpdateInstallSession {
    /** Streams [apkFile] into the session. Throws when the bytes cannot be written. */
    fun writeApk(apkFile: File)

    /** Hands the staged session to the system installer. Throws when the session cannot commit. */
    fun commit()

    /** Destroys the session and its staged bytes. Never throws. */
    fun abandon()
}

/** Opens a fresh install session for [apkFile]. Throws when no session can be created. */
interface UpdateInstallSessionFactory {
    fun openSession(apkFile: File): UpdateInstallSession
}

/**
 * Installs a downloaded update APK through an install session.
 * Returns true once the session is committed: the install then
 * proceeds from the session's copy, and the staged file is
 * deleted. Returns false on any failure: the session is
 * abandoned and the staged file is kept, so a retry reuses the
 * download. Never throws. Blocking file work: the production
 * implementation runs it off the main thread.
 */
interface UpdateApkInstaller {
    suspend fun install(apkFile: File): Boolean
}

/** An installer that always fails: the ViewModel's default, so unwired runs never pretend an install happened. */
object NoOpUpdateApkInstaller : UpdateApkInstaller {
    override suspend fun install(apkFile: File): Boolean = false
}

/**
 * The session installer: open a session, stream the APK into
 * it, commit, then delete the staged file, in that order. The
 * delete runs only after a successful commit, because from
 * that point the install proceeds from the session's copy and
 * the file is no longer needed, so nothing is left on disk.
 * Any failure before the commit abandons the session and keeps
 * the file, so a retry reuses the download. A delete that
 * reports failure is ignored: the session already committed,
 * and the next download overwrites the same file. Cancellation
 * still abandons the session, then propagates.
 */
class SessionApkInstaller(
    private val sessions: UpdateInstallSessionFactory,
    private val deleteFile: (File) -> Boolean = { file -> file.delete() },
) : UpdateApkInstaller {

    override suspend fun install(apkFile: File): Boolean {
        val session = try {
            sessions.openSession(apkFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        return try {
            session.writeApk(apkFile)
            session.commit()
            deleteFile(apkFile)
            true
        } catch (e: CancellationException) {
            runCatching { session.abandon() }
            throw e
        } catch (e: Exception) {
            runCatching { session.abandon() }
            false
        }
    }
}

/**
 * The install result the status receiver reports back to the
 * live ViewModel. Only failure needs handling: a successful
 * install replaces the process, so no UI work follows it.
 */
fun interface UpdateInstallStatusHandler {
    fun onInstallFailed()
}

/**
 * The process-wide slot for the live [UpdateInstallStatusHandler].
 * The ViewModel registers itself on creation and unregisters on
 * clear; the manifest-declared status receiver reads the slot
 * when the system reports an install failure. Null when no
 * ViewModel is alive: the failure is then only logged.
 */
object UpdateInstallStatusRegistry {
    var handler: UpdateInstallStatusHandler? = null
}
