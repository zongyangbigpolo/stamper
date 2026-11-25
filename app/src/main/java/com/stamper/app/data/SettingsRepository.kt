package com.stamper.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stamper.app.domain.stamper.StamperSettings
import com.stamper.app.domain.stamper.WatermarkCorner
import com.stamper.app.domain.stamper.WatermarkScale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.stamperDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "stamper_settings"
)

class SettingsRepository(private val appContext: Context) {

    private object Keys {
        val ACTIVE_PROJECT_ID = longPreferencesKey("active_project_id")
        val WATERMARK_ENABLED = booleanPreferencesKey("watermark_enabled")
        val EXIF_ENABLED = booleanPreferencesKey("exif_enabled")
        val WATERMARK_CORNER = stringPreferencesKey("watermark_corner")
        val WATERMARK_SCALE = stringPreferencesKey("watermark_scale")
        val AUTO_START_SPEECH = booleanPreferencesKey("auto_start_speech")
        val JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        val CAMERA_WATCH_ENABLED = booleanPreferencesKey("camera_watch_enabled")
    }

    val settingsFlow: Flow<StamperSettings> = appContext.stamperDataStore.data.map { prefs ->
        StamperSettings(
            watermarkEnabled = prefs[Keys.WATERMARK_ENABLED] ?: true,
            exifEnabled = prefs[Keys.EXIF_ENABLED] ?: true,
            watermarkCorner = WatermarkCorner.fromNameOrDefault(prefs[Keys.WATERMARK_CORNER]),
            watermarkScale = WatermarkScale.fromNameOrDefault(prefs[Keys.WATERMARK_SCALE]),
            autoStartSpeech = prefs[Keys.AUTO_START_SPEECH] ?: true,
            jpegQuality = prefs[Keys.JPEG_QUALITY] ?: 92,
            cameraWatchEnabled = prefs[Keys.CAMERA_WATCH_ENABLED] ?: false
        )
    }

    val activeProjectIdFlow: Flow<Long> = appContext.stamperDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_PROJECT_ID] ?: DEFAULT_PROJECT_ID
    }

    suspend fun setActiveProjectId(projectId: Long) {
        appContext.stamperDataStore.edit { it[Keys.ACTIVE_PROJECT_ID] = projectId }
    }

    suspend fun setWatermarkEnabled(enabled: Boolean) {
        appContext.stamperDataStore.edit { it[Keys.WATERMARK_ENABLED] = enabled }
    }

    suspend fun setExifEnabled(enabled: Boolean) {
        appContext.stamperDataStore.edit { it[Keys.EXIF_ENABLED] = enabled }
    }

    suspend fun setWatermarkCorner(corner: WatermarkCorner) {
        appContext.stamperDataStore.edit { it[Keys.WATERMARK_CORNER] = corner.name }
    }

    suspend fun setWatermarkScale(scale: WatermarkScale) {
        appContext.stamperDataStore.edit { it[Keys.WATERMARK_SCALE] = scale.name }
    }

    suspend fun setAutoStartSpeech(enabled: Boolean) {
        appContext.stamperDataStore.edit { it[Keys.AUTO_START_SPEECH] = enabled }
    }

    suspend fun setJpegQuality(quality: Int) {
        appContext.stamperDataStore.edit { it[Keys.JPEG_QUALITY] = quality.coerceIn(70, 100) }
    }

    suspend fun setCameraWatchEnabled(enabled: Boolean) {
        appContext.stamperDataStore.edit { it[Keys.CAMERA_WATCH_ENABLED] = enabled }
    }

    companion object {
        const val DEFAULT_PROJECT_ID = 1L
    }
}
