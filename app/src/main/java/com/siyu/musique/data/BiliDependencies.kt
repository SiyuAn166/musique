package com.siyu.musique.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.siyu.musique.core.network.ApiService
import com.siyu.musique.core.network.BiliBootstrap
import com.siyu.musique.core.network.BiliClient
import com.siyu.musique.core.network.CookieJar
import com.siyu.musique.core.network.WbiKeyStore
import com.siyu.musique.player.ExoPlayerEngine
import com.siyu.musique.player.PlayerEngine
import com.siyu.musique.player.PlayerHolder

/**
 * Manual wiring for the Bilibili stack and the player. No DI
 * framework in v0.1.
 * The WBI key fetcher is assigned after [ApiService] exists, which
 * breaks the client <-> service construction cycle: the fetcher uses
 * an unsigned nav request, so it never re-enters the key store.
 * Statically reviewed only: it needs an Android Context for DataStore
 * and for the ExoPlayer cache.
 */
object BiliDependencies {

    /** How long attachBaseContext waits for the stored language before falling back to the system locale. */
    private const val LOCALE_READ_TIMEOUT_MS: Long = 1000L

    /** The shared audio cache, from the holder that also owns the player. Hand the same instance to the engine and to the app cache, so the settings size is what playback stored. */
    fun createAudioCache(context: Context): SimpleCache =
        PlayerHolder.audioCache(context.applicationContext)

    /**
     * The ExoPlayer engine, around the holder's shared player.
     * Build once, on the main thread, and keep it for the
     * Activity's life. [audioCache] must be the holder's
     * instance (see [createAudioCache]), so the engine's cache
     * diagnostic reads the cache the player writes.
     */
    fun createPlayerEngine(context: Context, audioCache: SimpleCache): PlayerEngine =
        ExoPlayerEngine(
            context.applicationContext,
            PlayerHolder.player(context.applicationContext),
            audioCache,
        )

    /** The app caches (audio + covers), for the settings screen's size and clear button. The audio cache is resolved from the holder on every call, so it survives the playback service releasing the shared cache in onDestroy; a captured instance would throw once released. */
    fun createAppCache(context: Context): AppCache =
        AndroidAppCache(context.applicationContext) {
            PlayerHolder.audioCache(context.applicationContext)
        }

    /** The persisted audio quality preference, read by the ViewModel at startup. */
    fun createQualityPreferenceStore(context: Context): QualityPreferenceStore =
        DataStoreQualityPreferenceStore(context.applicationContext)

    /** The persisted language preference, read by the ViewModel at startup and by the Activity before it attaches. */
    fun createLanguagePreferenceStore(context: Context): LanguagePreferenceStore =
        DataStoreLanguagePreferenceStore(context.applicationContext)

    /**
     * The context with the stored language applied, for the
     * Activity's attachBaseContext: the Activity's resources
     * resolve in the chosen language. The stored preference is
     * read synchronously (DataStore's first read); any failure,
     * a timeout, or a missing value falls back to the system
     * locale, returning the base context unchanged. A fixed
     * language wraps the context with a configuration carrying
     * that locale; follow-system returns the context as-is.
     */
    fun wrapWithLanguagePreference(context: Context): Context {
        val store = createLanguagePreferenceStore(context)
        val preference = runBlocking {
            withTimeoutOrNull(LOCALE_READ_TIMEOUT_MS) {
                runCatching { store.load() }.getOrNull()
            }
        } ?: LanguagePreference.SYSTEM
        val locale = localeForPreference(preference) ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** The persisted playlist list, read by the ViewModel at startup. */
    fun createPlaylistStore(context: Context): PlaylistStore =
        DataStorePlaylistStore(context.applicationContext)

    /** The persisted repeat/shuffle mode, read by the ViewModel at startup. */
    fun createPlaybackModeStore(context: Context): PlaybackModeStore =
        DataStorePlaybackModeStore(context.applicationContext)

    /** The persisted playback state (queue, track, position), restored by the ViewModel at startup. */
    fun createPlaybackStateStore(context: Context): PlaybackStateStore =
        DataStorePlaybackStateStore(context.applicationContext)

    /** The persisted download records, read by the ViewModel at startup. */
    fun createDownloadStore(context: Context): DownloadStore =
        DataStoreDownloadStore(context.applicationContext)

    /** The audio file downloader, streaming CDN bytes to files in the downloads dir. */
    fun createAudioFileDownloader(): AudioFileDownloader =
        OkHttpAudioFileDownloader(OkHttpGateway.defaultClient())

    /** The app-private downloads dir, under filesDir: uninstall deletes it with everything else. */
    fun downloadsDir(context: Context): File =
        File(context.applicationContext.filesDir, DOWNLOADS_DIR_NAME)

    /**
     * The installed build's version, read from the package
     * manager: the versionCode the update check compares against
     * the release, and the versionName the settings row shows.
     * No generated BuildConfig class is involved, so the
     * kotlinc full-compile check needs no extra stub.
     */
    fun installedVersion(context: Context): InstalledVersion {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(appContext.packageName, 0)
        }
        return InstalledVersion(
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo).toInt(),
            versionName = packageInfo.versionName ?: "",
        )
    }

    /** The update check, against the public GitHub releases API with no auth. Manual only; nothing checks in the background. */
    fun createUpdateChecker(installedVersionCode: Int): UpdateChecker =
        GitHubUpdateChecker(OkHttpUpdateGateway(), installedVersionCode)

    /** The update installer: the downloaded APK streams into a PackageInstaller session and commits; the staged file is deleted once committed. */
    fun createUpdateApkInstaller(context: Context): UpdateApkInstaller =
        UpdateInstaller.createApkInstaller(context.applicationContext)

    /** The updates dir, under cacheDir: the downloaded APK lives here until the install session streams it in. */
    fun updatesDir(context: Context): File =
        File(context.applicationContext.cacheDir, UPDATES_DIR_NAME)

    fun createSearchRepository(context: Context): SearchRepository {
        val appContext = context.applicationContext
        val cookieJar = CookieJar()
        val keyStore = WbiKeyStore()
        val client = BiliClient(
            gateway = OkHttpGateway(),
            cookieJar = cookieJar,
            wbiKeys = keyStore,
        )
        val apiService = ApiService(client)
        keyStore.fetcher = { apiService.fetchNavKeys() }
        val bootstrap = BiliBootstrap(
            client = client,
            cookieJar = cookieJar,
            store = DataStoreBootstrapStore(appContext),
            wbiKeys = keyStore,
        )
        return BiliSearchRepository(apiService, bootstrap)
    }
}
