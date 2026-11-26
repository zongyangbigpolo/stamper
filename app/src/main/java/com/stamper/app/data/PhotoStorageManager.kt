package com.stamper.app.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.stamper.app.domain.stamper.StamperMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

data class SavedMediaResult(
    val uri: Uri,
    val relativePath: String
)

object PhotoStorageManager {

    const val ROOT_FOLDER = "Stamper"
    const val WATERMARK_SUBFOLDER = "水印版"

    fun buildRelativePath(projectFolderName: String): String =
        "${Environment.DIRECTORY_PICTURES}/$ROOT_FOLDER/$projectFolderName"

    /**
     * Queries MediaStore for already-existing filenames in `Pictures/Stamper/<projectFolderName>`
     * so we never overwrite or fail on unique DISPLAY_NAME constraints.
     */
    fun queryExistingMediaNames(context: Context, projectFolderName: String): Set<String> {
        val names = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relPath = buildRelativePath(projectFolderName)
            val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("$relPath%")
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        cursor.getString(idx)?.let { names.add(it) }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "$ROOT_FOLDER/$projectFolderName"
            )
            dir.list()?.forEach { names.add(it) }
        }
        return names
    }

    suspend fun saveStampedJpeg(
        context: Context,
        projectFolderName: String,
        fileName: String,
        jpegBytes: ByteArray,
        capturedAtMillis: Long
    ): SavedMediaResult = withContext(Dispatchers.IO) {
        val relativePath = buildRelativePath(projectFolderName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.DATE_TAKEN, capturedAtMillis)
                put(MediaStore.Images.Media.DATE_ADDED, capturedAtMillis / 1000L)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values)
                ?: throw IllegalStateException("无法在系统媒体库创建照片文件: $fileName")

            try {
                resolver.openOutputStream(itemUri, "w")?.use { out ->
                    out.write(jpegBytes)
                    out.flush()
                } ?: throw IllegalStateException("无法打开照片输出流: $itemUri")

                val publishValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishValues, null, null)
                SavedMediaResult(uri = itemUri, relativePath = relativePath)
            } catch (t: Throwable) {
                runCatching { resolver.delete(itemUri, null, null) }
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val projectDir = File(picturesDir, "$ROOT_FOLDER/$projectFolderName")
            if (!projectDir.exists() && !projectDir.mkdirs()) {
                throw IllegalStateException("无法创建归档文件夹: ${projectDir.absolutePath}")
            }
            val outFile = File(projectDir, fileName)
            FileOutputStream(outFile).use { out ->
                out.write(jpegBytes)
                out.flush()
            }
            val scannedUri = scanFileForMediaUri(context, outFile)
                ?: Uri.fromFile(outFile)
            SavedMediaResult(uri = scannedUri, relativePath = relativePath)
        }
    }

    /** Like [saveStampedJpeg] but first removes an existing file with the same name in that folder. */
    suspend fun saveOrReplaceJpeg(
        context: Context,
        folderPath: String,
        fileName: String,
        jpegBytes: ByteArray,
        capturedAtMillis: Long
    ): SavedMediaResult = withContext(Dispatchers.IO) {
        deleteExistingByName(context, folderPath, fileName)
        saveStampedJpeg(context, folderPath, fileName, jpegBytes, capturedAtMillis)
    }

    private fun deleteExistingByName(context: Context, folderPath: String, fileName: String) {
        val relPath = buildRelativePath(folderPath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.Images.Media.RELATIVE_PATH} IN (?, ?)",
                    arrayOf(fileName, relPath, "$relPath/"),
                    null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(idIdx))
                        runCatching { resolver.delete(uri, null, null) }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            runCatching { File(picturesDir, "$ROOT_FOLDER/$folderPath/$fileName").delete() }
        }
    }

    suspend fun deleteMediaUri(context: Context, uriString: String) = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext
        runCatching {
            if ("file".equals(uri.scheme, ignoreCase = true)) {
                uri.path?.let { File(it).delete() }
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    /**
     * Packages all photos in a project along with `manifest.json` into a `.zip` file inside
     * `cacheDir/exports/` and returns a shareable `FileProvider` URI.
     */
    suspend fun exportProjectToZip(
        context: Context,
        project: ProjectItem,
        photos: List<PhotoRecord>
    ): Uri = withContext(Dispatchers.IO) {
        val exportsDir = File(context.cacheDir, "exports").apply {
            mkdirs()
            // Clean up stale exports older than 6 hours
            val cutoff = System.currentTimeMillis() - 6 * 3600_000L
            listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }

        val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm", Locale.US)
            .format(Instant.now().atZone(ZoneId.systemDefault()))
        val zipFileName = "${project.folderName}_${ts}.zip"
        val zipFile = File(exportsDir, zipFileName)

        val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val zone = ZoneId.systemDefault()
        val exportedEntries = mutableListOf<String>()
        val usedZipNames = mutableSetOf<String>()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            for (photo in photos) {
                val uri = runCatching { Uri.parse(photo.mediaUri) }.getOrNull() ?: continue
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: continue

                var entryFileName = photo.fileName
                var idx = 2
                while (!usedZipNames.add(entryFileName.lowercase(Locale.ROOT))) {
                    val stem = photo.fileName.removeSuffix(".jpg")
                    entryFileName = "${stem}_${idx}.jpg"
                    idx++
                }

                val entryPath = "${project.folderName}/$entryFileName"
                zos.putNextEntry(ZipEntry(entryPath))
                zos.write(bytes)
                zos.closeEntry()

                val capturedIso = isoFormatter.format(Instant.ofEpochMilli(photo.capturedAt).atZone(zone))
                exportedEntries.add(
                    """    {
      "fileName": "${StamperMetadata.escapeJsonString(entryFileName, false)}",
      "note": "${StamperMetadata.escapeJsonString(photo.note, false)}",
      "capturedAt": "${StamperMetadata.escapeJsonString(capturedIso, false)}",
      "project": "${StamperMetadata.escapeJsonString(project.name, false)}",
      "width": ${photo.width},
      "height": ${photo.height},
      "watermarkApplied": ${photo.watermarkApplied},
      "exifApplied": ${photo.exifApplied},
      "source": "${StamperMetadata.escapeJsonString(photo.source, false)}"
    }"""
                )
            }

            val manifestJson = buildString {
                appendLine("{")
                appendLine("  \"schema\": \"stamper.manifest.v1\",")
                appendLine("  \"project\": \"${StamperMetadata.escapeJsonString(project.name, false)}\",")
                appendLine("  \"photoCount\": ${exportedEntries.size},")
                appendLine("  \"photos\": [")
                append(exportedEntries.joinToString(",\n"))
                if (exportedEntries.isNotEmpty()) appendLine()
                appendLine("  ]")
                appendLine("}")
            }

            zos.putNextEntry(ZipEntry("${project.folderName}/manifest.json"))
            zos.write(manifestJson.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )
    }

    private suspend fun scanFileForMediaUri(context: Context, file: File): Uri? =
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg")
            ) { _, uri ->
                if (cont.isActive) {
                    cont.resume(uri)
                }
            }
        }
}
