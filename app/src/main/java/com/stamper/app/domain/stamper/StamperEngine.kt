package com.stamper.app.domain.stamper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

data class ProcessedPhotoBytes(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtMillis: Long,
    val metadata: StamperMetadata
)

data class PhotoMetadataInspection(
    val parsedStamperMetadata: StamperMetadata?,
    val rawUserComment: String?,
    val imageDescription: String?,
    val dateTimeOriginal: String?,
    val xmpPacket: String?,
    val gpsLatLng: Pair<Double, Double>?,
    val dimensions: String?
)

object StamperEngine {

    private const val MAX_DECODE_DIMENSION = 4096

    private val EXIF_DATETIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    private val HUMAN_DATETIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val OFFSET_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("xxx", Locale.US)

    private val ISO_OFFSET_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private val PRESERVED_EXIF_TAGS = arrayOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP
    )

    suspend fun processFromUri(
        context: Context,
        sourceUri: Uri,
        note: String,
        projectName: String,
        source: CaptureSource,
        settings: StamperSettings,
        fallbackCapturedAtMillis: Long = System.currentTimeMillis()
    ): ProcessedPhotoBytes = withContext(Dispatchers.IO) {
        val accessibleUri = tryRequireOriginalUri(sourceUri)

        // 1. Read original EXIF from input stream before decoding pixels
        val originalExifMap = mutableMapOf<String, String>()
        var orientation = ExifInterface.ORIENTATION_NORMAL
        var gpsLatLng: DoubleArray? = null
        var exifCapturedMillis: Long? = null

        openUriWithFallback(context, accessibleUri, sourceUri)?.use { exifStream ->
            val srcExif = ExifInterface(exifStream)
            orientation = srcExif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            gpsLatLng = srcExif.latLong
            exifCapturedMillis = parseExifDateTimeMillis(
                srcExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: srcExif.getAttribute(ExifInterface.TAG_DATETIME)
            )
            for (tag in PRESERVED_EXIF_TAGS) {
                srcExif.getAttribute(tag)?.let { originalExifMap[tag] = it }
            }
        }

        val resolvedCapturedAtMillis = exifCapturedMillis ?: fallbackCapturedAtMillis

        // 2. Decode bounds first to prevent OOM on ultra-high-MP sensors
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openUriWithFallback(context, accessibleUri, sourceUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val sampleSize = calculateInSampleSize(
            boundsOpts.outWidth,
            boundsOpts.outHeight,
            MAX_DECODE_DIMENSION
        )

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = openUriWithFallback(context, accessibleUri, sourceUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: throw IllegalStateException("无法读取原始照片数据: $sourceUri")

        val uprightBitmap = applyExifOrientation(rawBitmap, orientation)
        if (uprightBitmap !== rawBitmap) {
            rawBitmap.recycle()
        }

        // 3. Apply visual corner watermark (ONLY voice note, no date, no project name)
        val trimmedNote = note.trim()
        val watermarkApplied = settings.watermarkEnabled && trimmedNote.isNotEmpty()
        val finalBitmap = if (watermarkApplied) {
            val stamped = WatermarkRenderer.renderWatermark(
                source = uprightBitmap,
                note = trimmedNote,
                corner = settings.watermarkCorner,
                scale = settings.watermarkScale
            )
            if (stamped !== uprightBitmap) {
                uprightBitmap.recycle()
            }
            stamped
        } else {
            uprightBitmap
        }

        val finalWidth = finalBitmap.width
        val finalHeight = finalBitmap.height

        // 4. Compress bitmap to JPEG
        val compressedOut = ByteArrayOutputStream(512 * 1024)
        val quality = settings.jpegQuality.coerceIn(60, 100)
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, compressedOut)
        finalBitmap.recycle()

        val zdt = Instant.ofEpochMilli(resolvedCapturedAtMillis).atZone(ZoneId.systemDefault())
        val metadata = StamperMetadata(
            project = projectName.trim().ifEmpty { "默认项目" },
            capturedAt = ISO_OFFSET_FORMATTER.format(zdt),
            note = trimmedNote,
            watermarkApplied = watermarkApplied,
            watermarkCorner = settings.watermarkCorner.name,
            source = source.wireValue,
            gpsLat = gpsLatLng?.getOrNull(0),
            gpsLng = gpsLatLng?.getOrNull(1)
        )

        val finalBytes = if (settings.exifEnabled) {
            writeExifAndXmpBytes(
                context = context,
                rawJpegBytes = compressedOut.toByteArray(),
                metadata = metadata,
                capturedAtMillis = resolvedCapturedAtMillis,
                preservedExif = originalExifMap,
                imageWidth = finalWidth,
                imageHeight = finalHeight
            )
        } else {
            compressedOut.toByteArray()
        }

        ProcessedPhotoBytes(
            jpegBytes = finalBytes,
            width = finalWidth,
            height = finalHeight,
            capturedAtMillis = resolvedCapturedAtMillis,
            metadata = metadata
        )
    }

    /**
     * Re-writes EXIF + XMP metadata on an existing JPEG when the user edits the note or moves
     * a photo between projects.
     */
    suspend fun rewriteMetadataOnly(
        context: Context,
        existingJpegBytes: ByteArray,
        updatedMetadata: StamperMetadata,
        capturedAtMillis: Long
    ): ByteArray = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("stamper_remeta_", ".jpg", context.cacheDir)
        try {
            FileOutputStream(tempFile).use { it.write(existingJpegBytes) }
            val exif = ExifInterface(tempFile.absolutePath)
            applyMetadataToExif(exif, updatedMetadata, capturedAtMillis, null, null, emptyMap())
            exif.saveAttributes()
            JpegXmpWriter.injectXmp(tempFile.readBytes(), updatedMetadata)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun inspectPhotoMetadata(context: Context, uri: Uri): PhotoMetadataInspection =
        withContext(Dispatchers.IO) {
            val targetUri = tryRequireOriginalUri(uri)
            val bytes = openUriWithFallback(context, targetUri, uri)?.use { it.readBytes() }
                ?: return@withContext PhotoMetadataInspection(
                    parsedStamperMetadata = null,
                    rawUserComment = null,
                    imageDescription = null,
                    dateTimeOriginal = null,
                    xmpPacket = null,
                    gpsLatLng = null,
                    dimensions = null
                )

            val exif = ExifInterface(bytes.inputStream())
            val rawUserComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
            val parsed = StamperMetadata.parseFromUserComment(rawUserComment)
            val latLong = exif.latLong?.let { Pair(it[0], it[1]) }
            val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            val dims = if (w > 0 && h > 0) "${w} × ${h}" else null

            PhotoMetadataInspection(
                parsedStamperMetadata = parsed,
                rawUserComment = rawUserComment,
                imageDescription = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
                dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
                xmpPacket = JpegXmpWriter.extractXmpPacket(bytes),
                gpsLatLng = latLong,
                dimensions = dims
            )
        }

    private fun writeExifAndXmpBytes(
        context: Context,
        rawJpegBytes: ByteArray,
        metadata: StamperMetadata,
        capturedAtMillis: Long,
        preservedExif: Map<String, String>,
        imageWidth: Int,
        imageHeight: Int
    ): ByteArray {
        val tempFile = File.createTempFile("stamper_exif_", ".jpg", context.cacheDir)
        return try {
            FileOutputStream(tempFile).use { it.write(rawJpegBytes) }
            val exif = ExifInterface(tempFile.absolutePath)
            applyMetadataToExif(
                exif = exif,
                metadata = metadata,
                capturedAtMillis = capturedAtMillis,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                preservedExif = preservedExif
            )
            exif.saveAttributes()
            val exifJpegBytes = tempFile.readBytes()
            JpegXmpWriter.injectXmp(exifJpegBytes, metadata)
        } finally {
            tempFile.delete()
        }
    }

    private fun applyMetadataToExif(
        exif: ExifInterface,
        metadata: StamperMetadata,
        capturedAtMillis: Long,
        imageWidth: Int?,
        imageHeight: Int?,
        preservedExif: Map<String, String>
    ) {
        for ((tag, value) in preservedExif) {
            exif.setAttribute(tag, value)
        }

        val zdt = Instant.ofEpochMilli(capturedAtMillis).atZone(ZoneId.systemDefault())
        val exifDateTime = EXIF_DATETIME_FORMATTER.format(zdt)
        val humanDateTime = HUMAN_DATETIME_FORMATTER.format(zdt)
        val tzOffset = OFFSET_FORMATTER.format(zdt)

        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        if (imageWidth != null && imageHeight != null) {
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, imageWidth.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, imageHeight.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, imageWidth.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, imageHeight.toString())
        }
        exif.setAttribute(ExifInterface.TAG_DATETIME, exifDateTime)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateTime)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDateTime)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, tzOffset)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, tzOffset)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Stamper Android 1.0")

        val description = if (metadata.note.isNotBlank()) {
            "$humanDateTime | ${metadata.note}"
        } else {
            humanDateTime
        }
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, metadata.toExifAsciiSafeJson())
    }

    private fun tryRequireOriginalUri(uri: Uri): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "content".equals(uri.scheme, ignoreCase = true)) {
            return try {
                MediaStore.setRequireOriginal(uri)
            } catch (_: Throwable) {
                uri
            }
        }
        return uri
    }

    private fun openUriWithFallback(
        context: Context,
        preferredUri: Uri,
        fallbackUri: Uri
    ) = try {
        context.contentResolver.openInputStream(preferredUri)
    } catch (_: SecurityException) {
        context.contentResolver.openInputStream(fallbackUri)
    } catch (_: Exception) {
        if (preferredUri != fallbackUri) {
            runCatching { context.contentResolver.openInputStream(fallbackUri) }.getOrNull()
        } else {
            null
        }
    }

    private fun parseExifDateTimeMillis(exifDateStr: String?): Long? {
        if (exifDateStr.isNullOrBlank()) return null
        return runCatching {
            val ldt = LocalDateTime.parse(exifDateStr.trim(), EXIF_DATETIME_FORMATTER)
            ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        val longest = max(width, height)
        if (longest > maxDimension) {
            while ((longest / inSampleSize) > maxDimension) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
