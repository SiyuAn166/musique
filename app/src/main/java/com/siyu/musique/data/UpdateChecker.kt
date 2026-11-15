package com.siyu.musique.data

import java.io.File
import java.io.IOException

/**
 * The installed build's version: the code the update check
 * compares against the release, and the name the settings row
 * shows. Read from the package manager by the wiring layer, so
 * no generated BuildConfig class is needed anywhere.
 */
data class InstalledVersion(
    val versionCode: Int,
    val versionName: String,
)

/** Why an update check or download failed, so the settings dialog can say something useful. */
enum class UpdateFailureKind {
    /** No network, or GitHub answered with an unexpected status. */
    NETWORK,

    /** GitHub refused the anonymous call (403/429): the hourly rate limit, which resets on its own. */
    RATE_LIMITED,

    /** The release or version payload did not parse. */
    PARSE,

    /** The latest release carries no version.json or no APK asset, so there is nothing to compare or download. */
    MISSING_ASSET,

    /** The APK download failed, or the downloaded size disagreed with the release. */
    DOWNLOAD_FAILED,
}

/** The outcome of one update check. */
sealed interface UpdateCheckResult {
    /** The installed build is as new as the latest release. */
    data object UpToDate : UpdateCheckResult

    /** A newer release exists: its tag, its versionCode, and the APK asset to download. */
    data class Available(
        val tag: String,
        val versionCode: Int,
        val apkSize: Long,
        val apkUrl: String,
    ) : UpdateCheckResult

    /** The check could not complete. Retrying is always safe. */
    data class Failed(val kind: UpdateFailureKind) : UpdateCheckResult
}

/**
 * The update check seam: ask GitHub for the latest release and,
 * when it is newer, download its APK. Production uses
 * [GitHubUpdateChecker]; tests fake this interface.
 * [checkForUpdate] never throws: every failure comes back as
 * [UpdateCheckResult.Failed]. [downloadUpdate] throws
 * [IOException] on transport or HTTP failure and when the
 * downloaded size disagrees with the release, deleting the
 * partial file first.
 */
interface UpdateChecker {
    suspend fun checkForUpdate(): UpdateCheckResult

    suspend fun downloadUpdate(
        update: UpdateCheckResult.Available,
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): File
}

/**
 * The [UpdateChecker] against the public GitHub releases API, with
 * no auth token anywhere. One check is two GETs: the latest
 * release (for the asset list), then its version.json asset (for
 * the release's versionCode). The versionCode decides: the tag
 * alone cannot, because a tag can be moved onto an older build.
 * A download is verified against the asset size the API
 * reported, so a truncated file never reaches the installer.
 */
class GitHubUpdateChecker(
    private val gateway: UpdateGateway,
    private val installedVersionCode: Int,
) : UpdateChecker {

    override suspend fun checkForUpdate(): UpdateCheckResult {
        val response = try {
            gateway.get(LATEST_RELEASE_URL, API_HEADERS)
        } catch (e: IOException) {
            return UpdateCheckResult.Failed(UpdateFailureKind.NETWORK)
        }
        val releaseFailure = failureForStatus(response.status)
        if (releaseFailure != null) return releaseFailure
        val release = try {
            GitHubReleaseJson.parseLatestRelease(response.body)
        } catch (e: IllegalArgumentException) {
            return UpdateCheckResult.Failed(UpdateFailureKind.PARSE)
        }
        val versionAsset = release.assetNamed(VERSION_JSON_ASSET)
        val apkAsset = release.assetNamed(APK_ASSET)
        if (versionAsset == null || apkAsset == null) {
            return UpdateCheckResult.Failed(UpdateFailureKind.MISSING_ASSET)
        }
        val versionResponse = try {
            gateway.get(versionAsset.downloadUrl, DOWNLOAD_HEADERS)
        } catch (e: IOException) {
            return UpdateCheckResult.Failed(UpdateFailureKind.NETWORK)
        }
        val versionFailure = failureForStatus(versionResponse.status)
        if (versionFailure != null) return versionFailure
        val versionInfo = try {
            GitHubReleaseJson.parseVersionInfo(versionResponse.body)
        } catch (e: IllegalArgumentException) {
            return UpdateCheckResult.Failed(UpdateFailureKind.PARSE)
        }
        return if (isUpdateAvailable(versionInfo.versionCode, installedVersionCode)) {
            UpdateCheckResult.Available(
                tag = versionInfo.tag,
                versionCode = versionInfo.versionCode,
                apkSize = apkAsset.size,
                apkUrl = apkAsset.downloadUrl,
            )
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    override suspend fun downloadUpdate(
        update: UpdateCheckResult.Available,
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): File {
        try {
            gateway.download(update.apkUrl, target, onProgress)
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        val downloadedSize = target.length()
        if (update.apkSize > 0 && downloadedSize != update.apkSize) {
            target.delete()
            throw IOException(
                "Downloaded size $downloadedSize does not match release size ${update.apkSize}",
            )
        }
        return target
    }

    private fun failureForStatus(status: Int): UpdateCheckResult.Failed? = when (status) {
        200 -> null
        403, 429 -> UpdateCheckResult.Failed(UpdateFailureKind.RATE_LIMITED)
        else -> UpdateCheckResult.Failed(UpdateFailureKind.NETWORK)
    }

    companion object {
        /** The latest release of the public repo, readable without any auth. */
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/SiyuAn166/musique/releases/latest"

        /** The version asset CI attaches to every versioned release. */
        const val VERSION_JSON_ASSET = "version.json"

        /** The APK asset CI attaches to every versioned release. */
        const val APK_ASSET = "musique.apk"

        private val API_HEADERS = mapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to OkHttpUpdateGateway.USER_AGENT,
        )
        private val DOWNLOAD_HEADERS = mapOf(
            "User-Agent" to OkHttpUpdateGateway.USER_AGENT,
        )
    }
}

/**
 * A checker that always fails loudly: the ViewModel's default, so
 * tests and runs without a wired checker never pretend a check
 * happened. Production always wires [GitHubUpdateChecker].
 */
object NoOpUpdateChecker : UpdateChecker {
    override suspend fun checkForUpdate(): UpdateCheckResult =
        throw IllegalStateException("No update checker wired")

    override suspend fun downloadUpdate(
        update: UpdateCheckResult.Available,
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): File = throw IllegalStateException("No update checker wired")
}

/** The default updates dir for tests and unwired runs: a temp dir, never the app's real cache. */
internal fun defaultUpdatesDir(): File = File(
    System.getProperty("java.io.tmpdir") ?: ".",
    "musique-updates",
)

/** The updates dir name under cacheDir, where the downloaded APK waits for the installer. */
const val UPDATES_DIR_NAME = "updates"
