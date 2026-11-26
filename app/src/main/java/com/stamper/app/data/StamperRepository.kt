package com.stamper.app.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import com.stamper.app.domain.stamper.CaptureSource
import com.stamper.app.domain.stamper.FileNameSanitizer
import com.stamper.app.domain.stamper.InfoCard
import com.stamper.app.domain.stamper.InfoCardStyle
import com.stamper.app.domain.stamper.PhotoMetadataInspection
import com.stamper.app.domain.stamper.StamperEngine
import com.stamper.app.domain.stamper.StamperMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BatchWatermarkResult(
    val succeeded: Int,
    val failed: Int,
    val folderDisplayPath: String
)

data class LocationSuggestion(val text: String, val coordinatesOnly: Boolean)

class StamperRepository(
    private val appContext: Context,
    private val database: StamperDatabase,
    val settingsRepository: SettingsRepository
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _projects = MutableStateFlow<List<ProjectItem>>(emptyList())
    val projects: StateFlow<List<ProjectItem>> = _projects.asStateFlow()

    private val _dbChangeVersion = MutableStateFlow(0L)

    init {
        repoScope.launch {
            refreshProjects()
        }
    }

    fun observePhotosForProject(projectId: Long): Flow<List<PhotoRecord>> =
        _dbChangeVersion.map {
            withContext(Dispatchers.IO) {
                database.getPhotosForProject(projectId)
            }
        }

    suspend fun refreshProjects() = withContext(Dispatchers.IO) {
        _projects.value = database.getAllProjectsWithSummary()
        _dbChangeVersion.value = _dbChangeVersion.value + 1L
    }

    suspend fun getActiveProject(): ProjectItem = withContext(Dispatchers.IO) {
        val activeId = settingsRepository.activeProjectIdFlow.first()
        database.getProjectById(activeId)
            ?: database.getAllProjectsWithSummary().first()
    }

    suspend fun createAndSelectProject(name: String): ProjectItem = withContext(Dispatchers.IO) {
        val created = database.createProject(name)
        settingsRepository.setActiveProjectId(created.id)
        refreshProjects()
        created
    }

    suspend fun renameProject(projectId: Long, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = newName.trim()
            require(clean.isNotEmpty()) { "项目名称不能为空" }
            val updated = database.renameProject(projectId, clean)
            if (!updated) error("已存在同名项目“$clean”")
            val targetProject = database.getProjectById(projectId) ?: return@runCatching
            val photos = database.getPhotosForProject(projectId)
            for (record in photos) {
                relocateSinglePhotoRecord(record, targetProject)
            }
            refreshProjects()
        }
    }

    suspend fun deleteProject(projectId: Long, deleteMediaFiles: Boolean) = withContext(Dispatchers.IO) {
        if (projectId == SettingsRepository.DEFAULT_PROJECT_ID) return@withContext
        if (deleteMediaFiles) {
            val photos = database.getPhotosForProject(projectId)
            for (photo in photos) {
                PhotoStorageManager.deleteMediaUri(appContext, photo.mediaUri)
            }
        }
        database.deleteProjectRecordOnly(projectId)
        val currentActive = settingsRepository.activeProjectIdFlow.first()
        if (currentActive == projectId) {
            settingsRepository.setActiveProjectId(SettingsRepository.DEFAULT_PROJECT_ID)
        }
        refreshProjects()
    }

    /**
     * End-to-end stamp + EXIF/XMP + MediaStore save into `Pictures/Stamper/<projectFolderName>/<note>_<yyyyMMdd>.jpg`.
     */
    suspend fun stampAndSavePhoto(
        sourceUri: Uri,
        note: String,
        projectId: Long? = null,
        source: CaptureSource,
        fallbackCapturedAtMillis: Long = System.currentTimeMillis()
    ): PhotoRecord = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settingsFlow.first()
        val targetProject = if (projectId != null) {
            database.getProjectById(projectId) ?: getActiveProject()
        } else {
            getActiveProject()
        }

        val processed = StamperEngine.processFromUri(
            context = appContext,
            sourceUri = sourceUri,
            note = note,
            projectName = targetProject.name,
            source = source,
            settings = settings,
            fallbackCapturedAtMillis = fallbackCapturedAtMillis
        )

        val existingNames = database.getExistingFileNamesInProject(targetProject.id) +
            PhotoStorageManager.queryExistingMediaNames(appContext, targetProject.folderName)

        val fileName = FileNameSanitizer.buildPhotoFileName(
            note = note,
            capturedAtMillis = processed.capturedAtMillis,
            existingNames = existingNames
        )

        val savedMedia = PhotoStorageManager.saveStampedJpeg(
            context = appContext,
            projectFolderName = targetProject.folderName,
            fileName = fileName,
            jpegBytes = processed.jpegBytes,
            capturedAtMillis = processed.capturedAtMillis
        )

        val recordId = database.insertPhotoRecord(
            projectId = targetProject.id,
            fileName = fileName,
            mediaUri = savedMedia.uri.toString(),
            relativePath = savedMedia.relativePath,
            note = note.trim(),
            capturedAt = processed.capturedAtMillis,
            width = processed.width,
            height = processed.height,
            watermarkApplied = processed.metadata.watermarkApplied,
            exifApplied = settings.exifEnabled,
            source = source.wireValue
        )

        refreshProjects()
        database.getPhotoById(recordId)!!
    }

    /**
     * Updates a saved photo's EXIF/XMP note & MediaStore file name (`<newNote>_<yyyyMMdd>.jpg`).
     */
    suspend fun updatePhotoNoteMetadata(photoId: Long, newNote: String): Result<PhotoRecord> =
        withContext(Dispatchers.IO) {
            runCatching {
                val record = database.getPhotoById(photoId)
                    ?: error("找不到该照片记录")
                val project = database.getProjectById(record.projectId)
                    ?: error("找不到所属项目")
                val oldUri = Uri.parse(record.mediaUri)
                val oldBytes = appContext.contentResolver.openInputStream(oldUri)?.use { it.readBytes() }
                    ?: error("原照片文件已被系统相册移除或无法读取")

                val zdt = Instant.ofEpochMilli(record.capturedAt).atZone(ZoneId.systemDefault())
                val updatedMeta = StamperMetadata(
                    project = project.name,
                    capturedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt),
                    note = newNote.trim(),
                    watermarkApplied = record.watermarkApplied,
                    watermarkCorner = "KEEP",
                    source = record.source
                )
                val updatedBytes = StamperEngine.rewriteMetadataOnly(
                    context = appContext,
                    existingJpegBytes = oldBytes,
                    updatedMetadata = updatedMeta,
                    capturedAtMillis = record.capturedAt
                )

                val existingNames = (database.getExistingFileNamesInProject(project.id) +
                    PhotoStorageManager.queryExistingMediaNames(appContext, project.folderName)) -
                    record.fileName

                val newFileName = FileNameSanitizer.buildPhotoFileName(
                    note = newNote,
                    capturedAtMillis = record.capturedAt,
                    existingNames = existingNames
                )

                val savedNew = PhotoStorageManager.saveStampedJpeg(
                    context = appContext,
                    projectFolderName = project.folderName,
                    fileName = newFileName,
                    jpegBytes = updatedBytes,
                    capturedAtMillis = record.capturedAt
                )
                if (savedNew.uri.toString() != record.mediaUri) {
                    PhotoStorageManager.deleteMediaUri(appContext, record.mediaUri)
                }

                database.updatePhotoNoteAndFileName(
                    photoId = photoId,
                    newNote = newNote.trim(),
                    newFileName = newFileName,
                    newMediaUri = savedNew.uri.toString()
                )
                refreshProjects()
                database.getPhotoById(photoId)!!
            }
        }

    /**
     * Moves one or more photos to another project group, updating:
     * 1. Physical MediaStore folder (`Pictures/Stamper/<targetProject.folderName>/<fileName>`)
     * 2. Embedded EXIF UserComment & UTF-8 XMP `stamper:project` metadata
     * 3. SQLite `photo_records.project_id`, `file_name`, `media_uri`, and `relative_path`
     */
    suspend fun movePhotosToProject(
        photoIds: Collection<Long>,
        targetProjectId: Long
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val targetProject = database.getProjectById(targetProjectId)
                ?: error("目标项目不存在")
            var movedCount = 0
            for (photoId in photoIds) {
                val record = database.getPhotoById(photoId) ?: continue
                if (record.projectId == targetProject.id) continue
                relocateSinglePhotoRecord(record, targetProject)
                movedCount++
            }
            refreshProjects()
            movedCount
        }
    }

    /**
     * Creates (or selects) a project by name and moves the specified photos into it.
     */
    suspend fun createProjectAndMovePhotos(
        newProjectName: String,
        photoIds: Collection<Long>
    ): Result<Pair<ProjectItem, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val created = database.createProject(newProjectName)
            var movedCount = 0
            for (photoId in photoIds) {
                val record = database.getPhotoById(photoId) ?: continue
                if (record.projectId == created.id) continue
                relocateSinglePhotoRecord(record, created)
                movedCount++
            }
            refreshProjects()
            created to movedCount
        }
    }

    private suspend fun relocateSinglePhotoRecord(
        record: PhotoRecord,
        targetProject: ProjectItem
    ) {
        val oldUri = runCatching { Uri.parse(record.mediaUri) }.getOrNull()
        val oldBytes = oldUri?.let { uri ->
            runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
        }

        if (oldBytes != null) {
            val zdt = Instant.ofEpochMilli(record.capturedAt).atZone(ZoneId.systemDefault())
            val updatedMeta = StamperMetadata(
                project = targetProject.name,
                capturedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt),
                note = record.note,
                watermarkApplied = record.watermarkApplied,
                watermarkCorner = "KEEP",
                source = record.source
            )
            val updatedBytes = runCatching {
                StamperEngine.rewriteMetadataOnly(
                    context = appContext,
                    existingJpegBytes = oldBytes,
                    updatedMetadata = updatedMeta,
                    capturedAtMillis = record.capturedAt
                )
            }.getOrDefault(oldBytes)

            val existingNames = (database.getExistingFileNamesInProject(targetProject.id) +
                PhotoStorageManager.queryExistingMediaNames(appContext, targetProject.folderName)) -
                if (record.projectId == targetProject.id) setOf(record.fileName) else emptySet()

            val newFileName = FileNameSanitizer.buildPhotoFileName(
                note = record.note,
                capturedAtMillis = record.capturedAt,
                existingNames = existingNames
            )

            val savedNew = PhotoStorageManager.saveStampedJpeg(
                context = appContext,
                projectFolderName = targetProject.folderName,
                fileName = newFileName,
                jpegBytes = updatedBytes,
                capturedAtMillis = record.capturedAt
            )
            if (savedNew.uri.toString() != record.mediaUri) {
                PhotoStorageManager.deleteMediaUri(appContext, record.mediaUri)
            }

            database.updatePhotoProjectAndMedia(
                photoId = record.id,
                targetProjectId = targetProject.id,
                newFileName = newFileName,
                newMediaUri = savedNew.uri.toString(),
                newRelativePath = savedNew.relativePath
            )
        } else {
            // Fallback if media file was externally deleted: still move DB record cleanly
            database.updatePhotoProjectAndMedia(
                photoId = record.id,
                targetProjectId = targetProject.id,
                newFileName = record.fileName,
                newMediaUri = record.mediaUri,
                newRelativePath = PhotoStorageManager.buildRelativePath(targetProject.folderName)
            )
        }
    }

    suspend fun deletePhoto(photoId: Long) = withContext(Dispatchers.IO) {
        val record = database.getPhotoById(photoId) ?: return@withContext
        PhotoStorageManager.deleteMediaUri(appContext, record.mediaUri)
        database.deletePhotoRecord(photoId)
        refreshProjects()
    }

    suspend fun deletePhotos(photoIds: Collection<Long>) = withContext(Dispatchers.IO) {
        for (photoId in photoIds) {
            val record = database.getPhotoById(photoId) ?: continue
            PhotoStorageManager.deleteMediaUri(appContext, record.mediaUri)
            database.deletePhotoRecord(photoId)
        }
        refreshProjects()
    }

    /**
     * Burns the project info card into copies of the given photos, saved under
     * `Pictures/Stamper/<项目>/水印版/` with the original file names. Originals are untouched.
     */
    suspend fun renderInfoCardCopies(
        projectId: Long,
        photoIds: List<Long>,
        title: String,
        style: InfoCardStyle,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<BatchWatermarkResult> = withContext(Dispatchers.IO) {
        runCatching {
            val project = database.getProjectById(projectId) ?: error("项目不存在")
            settingsRepository.saveInfoCardPreferences(projectId, title, style)
            val quality = settingsRepository.settingsFlow.first().jpegQuality
            val folderPath = "${project.folderName}/${PhotoStorageManager.WATERMARK_SUBFOLDER}"
            var succeeded = 0
            var failed = 0
            photoIds.forEachIndexed { index, photoId ->
                val record = database.getPhotoById(photoId)
                val ok = record != null && runCatching {
                    val content = InfoCard.buildContent(
                        title = title,
                        capturedAtMillis = record.capturedAt,
                        note = record.note,
                        fileName = record.fileName,
                        style = style
                    )
                    val zdt = Instant.ofEpochMilli(record.capturedAt).atZone(ZoneId.systemDefault())
                    val bytes = StamperEngine.renderInfoCardJpeg(
                        context = appContext,
                        sourceUri = Uri.parse(record.mediaUri),
                        content = content,
                        style = style,
                        fallbackMetadata = StamperMetadata(
                            project = project.name,
                            capturedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt),
                            note = record.note,
                            watermarkApplied = record.watermarkApplied,
                            watermarkCorner = "KEEP",
                            source = record.source
                        ),
                        capturedAtMillis = record.capturedAt,
                        jpegQuality = quality
                    )
                    PhotoStorageManager.saveOrReplaceJpeg(
                        context = appContext,
                        folderPath = folderPath,
                        fileName = record.fileName,
                        jpegBytes = bytes,
                        capturedAtMillis = record.capturedAt
                    )
                }.isSuccess
                if (ok) succeeded++ else failed++
                onProgress(index + 1, photoIds.size)
            }
            BatchWatermarkResult(
                succeeded = succeeded,
                failed = failed,
                folderDisplayPath = "Pictures/${PhotoStorageManager.ROOT_FOLDER}/$folderPath"
            )
        }
    }

    /**
     * Suggests a 地点 value from the first selected photo that carries GPS EXIF. Uses the
     * platform Geocoder when the phone provides one, otherwise falls back to raw coordinates.
     */
    suspend fun suggestLocationFromPhotos(photoIds: List<Long>): LocationSuggestion? =
        withContext(Dispatchers.IO) {
            var latLng: Pair<Double, Double>? = null
            for (photoId in photoIds) {
                val record = database.getPhotoById(photoId) ?: continue
                latLng = runCatching {
                    StamperEngine.inspectPhotoMetadata(appContext, Uri.parse(record.mediaUri)).gpsLatLng
                }.getOrNull()
                if (latLng != null) break
            }
            if (latLng == null) return@withContext null

            val address = if (Geocoder.isPresent()) {
                runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(appContext, Locale.SIMPLIFIED_CHINESE)
                        .getFromLocation(latLng.first, latLng.second, 1)
                        ?.firstOrNull()
                }.getOrNull()
            } else {
                null
            }
            val readable = address?.let { formatAddress(it) }
            if (!readable.isNullOrBlank()) {
                LocationSuggestion(readable, coordinatesOnly = false)
            } else {
                LocationSuggestion(
                    String.format(Locale.US, "%.5f°%s, %.5f°%s",
                        kotlin.math.abs(latLng.first), if (latLng.first >= 0) "N" else "S",
                        kotlin.math.abs(latLng.second), if (latLng.second >= 0) "E" else "W"),
                    coordinatesOnly = true
                )
            }
        }

    private fun formatAddress(address: Address): String {
        val region = listOfNotNull(address.locality ?: address.adminArea, address.subLocality)
            .distinct()
            .joinToString("")
        val place = address.featureName?.takeUnless { it.isBlank() || it.all { c -> c.isDigit() } }
            ?: address.thoroughfare
        return listOfNotNull(region.ifBlank { null }, place).joinToString("·")
    }

    suspend fun inspectMetadata(photoRecord: PhotoRecord): PhotoMetadataInspection =
        StamperEngine.inspectPhotoMetadata(appContext, Uri.parse(photoRecord.mediaUri))

    suspend fun exportProjectZip(projectId: Long): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val project = database.getProjectById(projectId) ?: error("项目不存在")
            val photos = database.getPhotosForProject(projectId)
            if (photos.isEmpty()) error("当前项目还没有归档照片")
            PhotoStorageManager.exportProjectToZip(appContext, project, photos)
        }
    }
}
