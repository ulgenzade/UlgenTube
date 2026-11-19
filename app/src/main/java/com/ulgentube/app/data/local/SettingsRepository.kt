package com.ulgentube.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
                embedThumbnail = prefs[Keys.EMBED_THUMBNAIL] ?: true
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

    suspend fun setUseAria2c(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.USE_ARIA2C] = enabled }
    }

    suspend fun setAutoDetectClipboard(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_DETECT_CLIPBOARD] = enabled }
    }

    suspend fun setEmbedThumbnail(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.EMBED_THUMBNAIL] = enabled }
    }
}
