package com.stamper.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stamper.app.domain.stamper.CardHeaderColor
import com.stamper.app.domain.stamper.CardPosition
import com.stamper.app.domain.stamper.InfoCardStyle
import com.stamper.app.domain.stamper.StamperSettings
import com.stamper.app.domain.stamper.WatermarkCorner
import com.stamper.app.domain.stamper.WatermarkScale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val CARD_POSITION = stringPreferencesKey("card_position")
        val CARD_OPACITY = floatPreferencesKey("card_opacity")
        val CARD_SCALE = floatPreferencesKey("card_scale")
        val CARD_HEADER_COLOR = stringPreferencesKey("card_header_color")
        val CARD_SHOW_TIME = booleanPreferencesKey("card_show_time")
        val CARD_SHOW_CONTENT = booleanPreferencesKey("card_show_content")
        val CARD_SHOW_LOCATION = booleanPreferencesKey("card_show_location")
        val CARD_LOCATION = stringPreferencesKey("card_location")
        fun cardTitle(projectId: Long) = stringPreferencesKey("card_title_$projectId")
    }

    val settingsFlow: Flow<StamperSettings> = appContext.stamperDataStore.data.map { prefs ->
        StamperSettings(
            watermarkEnabled = prefs[Keys.WATERMARK_ENABLED] ?: false,
            exifEnabled = prefs[Keys.EXIF_ENABLED] ?: true,
            watermarkCorner = WatermarkCorner.fromNameOrDefault(prefs[Keys.WATERMARK_CORNER]),
            watermarkScale = WatermarkScale.fromNameOrDefault(prefs[Keys.WATERMARK_SCALE]),
            autoStartSpeech = prefs[Keys.AUTO_START_SPEECH] ?: true,
            jpegQuality = prefs[Keys.JPEG_QUALITY] ?: 92
        )
    }

    val infoCardStyleFlow: Flow<InfoCardStyle> = appContext.stamperDataStore.data.map { prefs ->
        val defaults = InfoCardStyle()
        InfoCardStyle(
            position = CardPosition.fromNameOrDefault(prefs[Keys.CARD_POSITION]),
            opacity = prefs[Keys.CARD_OPACITY] ?: defaults.opacity,
            scale = prefs[Keys.CARD_SCALE] ?: defaults.scale,
            headerColor = CardHeaderColor.fromNameOrDefault(prefs[Keys.CARD_HEADER_COLOR]),
            showTime = prefs[Keys.CARD_SHOW_TIME] ?: defaults.showTime,
            showContent = prefs[Keys.CARD_SHOW_CONTENT] ?: defaults.showContent,
            showLocation = prefs[Keys.CARD_SHOW_LOCATION] ?: defaults.showLocation,
            location = prefs[Keys.CARD_LOCATION] ?: defaults.location
        )
    }

    suspend fun getInfoCardTitle(projectId: Long): String? =
        appContext.stamperDataStore.data.first()[Keys.cardTitle(projectId)]

    suspend fun saveInfoCardPreferences(projectId: Long, title: String, style: InfoCardStyle) {
        appContext.stamperDataStore.edit { prefs ->
            prefs[Keys.cardTitle(projectId)] = title
            prefs[Keys.CARD_POSITION] = style.position.name
            prefs[Keys.CARD_OPACITY] = style.opacity
            prefs[Keys.CARD_SCALE] = style.scale
            prefs[Keys.CARD_HEADER_COLOR] = style.headerColor.name
            prefs[Keys.CARD_SHOW_TIME] = style.showTime
            prefs[Keys.CARD_SHOW_CONTENT] = style.showContent
            prefs[Keys.CARD_SHOW_LOCATION] = style.showLocation
            prefs[Keys.CARD_LOCATION] = style.location
        }
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

    companion object {
        const val DEFAULT_PROJECT_ID = 1L
    }
}
