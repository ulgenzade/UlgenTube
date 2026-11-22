package com.ulgentube.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ulgentube.app.domain.model.AppSettings
import com.ulgentube.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ulgentube_settings"
)

/**
 * Uygulama ayarlarını Jetpack DataStore Preferences üzerinde kalıcı olarak yönetir.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DEFAULT_VIDEO_QUALITY = stringPreferencesKey("default_video_quality")
        val DEFAULT_AUDIO_FORMAT = stringPreferencesKey("default_audio_format")
        val USE_ARIA2C = booleanPreferencesKey("use_aria2c")
        val AUTO_DETECT_CLIPBOARD = booleanPreferencesKey("auto_detect_clipboard")
        val EMBED_THUMBNAIL = booleanPreferencesKey("embed_thumbnail")
        val SPONSOR_BLOCK_ENABLED = booleanPreferencesKey("sponsor_block_enabled")
        val EMBED_CHAPTERS = booleanPreferencesKey("embed_chapters")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val ZERO_FRICTION_SHARE_MODE = booleanPreferencesKey("zero_friction_share_mode")
        val PLATFORM_SUBFOLDERS = booleanPreferencesKey("platform_subfolders")
        val FILE_NAME_TEMPLATE = stringPreferencesKey("file_name_template")
        val COOKIES_ENABLED = booleanPreferencesKey("cookies_enabled")
        val PARALLEL_DOWNLOAD_ENABLED = booleanPreferencesKey("parallel_download_enabled")
        val PARALLEL_CONNECTION_COUNT = intPreferencesKey("parallel_connection_count")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val MAX_DOWNLOAD_SPEED_KBPS = intPreferencesKey("max_download_speed_kbps")
        val SCHEDULED_DOWNLOAD_ENABLED = booleanPreferencesKey("scheduled_download_enabled")
        val SCHEDULE_START_HOUR = intPreferencesKey("schedule_start_hour")
        val SCHEDULE_END_HOUR = intPreferencesKey("schedule_end_hour")
        val PO_TOKEN_ENABLED = booleanPreferencesKey("po_token_enabled")
        val CACHED_PO_TOKEN = stringPreferencesKey("cached_po_token")
        val CACHED_VISITOR_DATA = stringPreferencesKey("cached_visitor_data")
        val AUTO_FETCH_LYRICS = booleanPreferencesKey("auto_fetch_lyrics")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val EQUALIZER_PRESET = stringPreferencesKey("equalizer_preset")
        val PREFERRED_DUB_LANG = stringPreferencesKey("preferred_dub_lang")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            AppSettings(
                themeMode = runCatching {
                    ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name)
                }.getOrDefault(ThemeMode.SYSTEM),
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
                defaultVideoQuality = prefs[Keys.DEFAULT_VIDEO_QUALITY] ?: "1080p",
                defaultAudioFormat = prefs[Keys.DEFAULT_AUDIO_FORMAT] ?: "mp3",
                useAria2c = prefs[Keys.USE_ARIA2C] ?: true,
                autoDetectClipboard = prefs[Keys.AUTO_DETECT_CLIPBOARD] ?: true,
                embedThumbnail = prefs[Keys.EMBED_THUMBNAIL] ?: true,
                sponsorBlockEnabled = prefs[Keys.SPONSOR_BLOCK_ENABLED] ?: false,
                embedChapters = prefs[Keys.EMBED_CHAPTERS] ?: true,
                wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
                zeroFrictionShareMode = prefs[Keys.ZERO_FRICTION_SHARE_MODE] ?: false,
                platformSubfolders = prefs[Keys.PLATFORM_SUBFOLDERS] ?: false,
                fileNameTemplate = prefs[Keys.FILE_NAME_TEMPLATE] ?: "{title}",
                cookiesEnabled = prefs[Keys.COOKIES_ENABLED] ?: false,
                parallelDownloadEnabled = prefs[Keys.PARALLEL_DOWNLOAD_ENABLED] ?: true,
                parallelConnectionCount = (prefs[Keys.PARALLEL_CONNECTION_COUNT] ?: 4).coerceIn(1, 16),
                maxConcurrentDownloads = (prefs[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 3).coerceIn(1, 4),
                maxDownloadSpeedKbps = (prefs[Keys.MAX_DOWNLOAD_SPEED_KBPS] ?: 0).coerceAtLeast(0),
                scheduledDownloadEnabled = prefs[Keys.SCHEDULED_DOWNLOAD_ENABLED] ?: false,
                scheduleStartHour = (prefs[Keys.SCHEDULE_START_HOUR] ?: 1).coerceIn(0, 23),
                scheduleEndHour = (prefs[Keys.SCHEDULE_END_HOUR] ?: 7).coerceIn(0, 23),
                poTokenEnabled = prefs[Keys.PO_TOKEN_ENABLED] ?: true,
                cachedPoToken = prefs[Keys.CACHED_PO_TOKEN]?.takeIf { it.isNotBlank() },
                cachedVisitorData = prefs[Keys.CACHED_VISITOR_DATA]?.takeIf { it.isNotBlank() },
                autoFetchLyrics = prefs[Keys.AUTO_FETCH_LYRICS] ?: true,
                appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
                equalizerPreset = runCatching {
                    com.ulgentube.app.domain.model.EqualizerPreset.valueOf(
                        prefs[Keys.EQUALIZER_PRESET] ?: com.ulgentube.app.domain.model.EqualizerPreset.FLAT.name
                    )
                }.getOrDefault(com.ulgentube.app.domain.model.EqualizerPreset.FLAT),
                preferredDubLang = prefs[Keys.PREFERRED_DUB_LANG] ?: ""
            )
        }

    suspend fun getCurrentSettings(): AppSettings = settingsFlow.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setDefaultVideoQuality(quality: String) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_VIDEO_QUALITY] = quality }
    }

    suspend fun setDefaultAudioFormat(format: String) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_AUDIO_FORMAT] = format }
    }

    suspend fun setPreferredDubLang(lang: String) {
        context.settingsDataStore.edit { it[Keys.PREFERRED_DUB_LANG] = lang.trim().lowercase() }
    }

    suspend fun setUseAria2c(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.USE_ARIA2C] = enabled }
    }

    suspend fun setAutoDetectClipboard(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_DETECT_CLIPBOARD] = enabled }
    }

    suspend fun setEmbedThumbnail(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.EMBED_THUMBNAIL] = enabled }
    }

    suspend fun setSponsorBlockEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SPONSOR_BLOCK_ENABLED] = enabled }
    }

    suspend fun setEmbedChapters(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.EMBED_CHAPTERS] = enabled }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setZeroFrictionShareMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ZERO_FRICTION_SHARE_MODE] = enabled }
    }

    suspend fun setPlatformSubfolders(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.PLATFORM_SUBFOLDERS] = enabled }
    }

    suspend fun setFileNameTemplate(template: String) {
        context.settingsDataStore.edit {
            it[Keys.FILE_NAME_TEMPLATE] = template.trim().ifBlank { "{title}" }
        }
    }

    suspend fun setCookiesEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.COOKIES_ENABLED] = enabled }
    }

    suspend fun setParallelDownloadEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.PARALLEL_DOWNLOAD_ENABLED] = enabled }
    }

    suspend fun setParallelConnectionCount(count: Int) {
        context.settingsDataStore.edit { it[Keys.PARALLEL_CONNECTION_COUNT] = count.coerceIn(1, 16) }
    }

    suspend fun setMaxConcurrentDownloads(count: Int) {
        context.settingsDataStore.edit { it[Keys.MAX_CONCURRENT_DOWNLOADS] = count.coerceIn(1, 4) }
    }

    suspend fun setMaxDownloadSpeedKbps(kbps: Int) {
        context.settingsDataStore.edit { it[Keys.MAX_DOWNLOAD_SPEED_KBPS] = kbps.coerceAtLeast(0) }
    }

    suspend fun setScheduledDownloadEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SCHEDULED_DOWNLOAD_ENABLED] = enabled }
    }

    suspend fun setScheduleWindowHours(startHour: Int, endHour: Int) {
        context.settingsDataStore.edit {
            it[Keys.SCHEDULE_START_HOUR] = startHour.coerceIn(0, 23)
            it[Keys.SCHEDULE_END_HOUR] = endHour.coerceIn(0, 23)
        }
    }

    suspend fun setPoTokenEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.PO_TOKEN_ENABLED] = enabled }
    }

    suspend fun setCachedPoToken(poToken: String?, visitorData: String?) {
        context.settingsDataStore.edit { prefs ->
            if (poToken.isNullOrBlank()) prefs.remove(Keys.CACHED_PO_TOKEN) else prefs[Keys.CACHED_PO_TOKEN] = poToken
            if (visitorData.isNullOrBlank()) prefs.remove(Keys.CACHED_VISITOR_DATA) else prefs[Keys.CACHED_VISITOR_DATA] = visitorData
        }
    }

    suspend fun setAutoFetchLyrics(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_FETCH_LYRICS] = enabled }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setEqualizerPreset(preset: com.ulgentube.app.domain.model.EqualizerPreset) {
        context.settingsDataStore.edit { it[Keys.EQUALIZER_PRESET] = preset.name }
    }

    suspend fun restoreFromSettings(settings: com.ulgentube.app.domain.model.AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.MAX_DOWNLOAD_SPEED_KBPS] = settings.maxDownloadSpeedKbps.coerceAtLeast(0)
            prefs[Keys.SCHEDULED_DOWNLOAD_ENABLED] = settings.scheduledDownloadEnabled
            prefs[Keys.SCHEDULE_START_HOUR] = settings.scheduleStartHour.coerceIn(0, 23)
            prefs[Keys.SCHEDULE_END_HOUR] = settings.scheduleEndHour.coerceIn(0, 23)
            prefs[Keys.PO_TOKEN_ENABLED] = settings.poTokenEnabled
            prefs[Keys.AUTO_FETCH_LYRICS] = settings.autoFetchLyrics
            prefs[Keys.EQUALIZER_PRESET] = settings.equalizerPreset.name
            prefs[Keys.PARALLEL_DOWNLOAD_ENABLED] = settings.parallelDownloadEnabled
            prefs[Keys.PARALLEL_CONNECTION_COUNT] = settings.parallelConnectionCount.coerceIn(1, 16)
            prefs[Keys.MAX_CONCURRENT_DOWNLOADS] = settings.maxConcurrentDownloads.coerceIn(1, 4)
        }
    }
}
