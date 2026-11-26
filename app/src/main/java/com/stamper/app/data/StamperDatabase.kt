package com.stamper.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.stamper.app.domain.stamper.FileNameSanitizer

data class ProjectItem(
    val id: Long,
    val name: String,
    val folderName: String,
    val createdAt: Long,
    val photoCount: Int = 0,
    val latestCoverUri: String? = null,
    /** Up to four most recent photo URIs, newest first, for the home mosaic. */
    val coverUris: List<String> = emptyList()
)

data class PhotoRecord(
    val id: Long,
    val projectId: Long,
    val fileName: String,
    val mediaUri: String,
    val relativePath: String,
    val note: String,
    val capturedAt: Long,
    val width: Int,
    val height: Int,
    val watermarkApplied: Boolean,
    val exifApplied: Boolean,
    val source: String
)

class StamperDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                folder_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE photo_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                file_name TEXT NOT NULL,
                media_uri TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                note TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                watermark_applied INTEGER NOT NULL,
                exif_applied INTEGER NOT NULL,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX idx_photo_records_project ON photo_records(project_id, captured_at DESC)"
        )

        db.execSQL(
            """
            CREATE TABLE seen_system_media (
                media_id INTEGER PRIMARY KEY,
                seen_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Seed default project with id = 1
        val values = ContentValues().apply {
            put("id", SettingsRepository.DEFAULT_PROJECT_ID)
            put("name", DEFAULT_PROJECT_NAME)
            put("folder_name", DEFAULT_PROJECT_NAME)
            put("created_at", System.currentTimeMillis())
        }
        db.insert("projects", null, values)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 initial schema
    }

    fun getAllProjectsWithSummary(): List<ProjectItem> {
        val db = readableDatabase
        val sql = """
            SELECT p.id, p.name, p.folder_name, p.created_at,
                   COUNT(r.id) AS photo_count,
                   (SELECT r2.media_uri FROM photo_records r2
                    WHERE r2.project_id = p.id
                    ORDER BY r2.captured_at DESC, r2.id DESC LIMIT 1) AS cover_uri
            FROM projects p
            LEFT JOIN photo_records r ON r.project_id = p.id
            GROUP BY p.id
            ORDER BY CASE WHEN p.id = 1 THEN 0 ELSE 1 END, p.created_at DESC
        """.trimIndent()

        val list = mutableListOf<ProjectItem>()
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    ProjectItem(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        folderName = cursor.getString(2),
                        createdAt = cursor.getLong(3),
                        photoCount = cursor.getInt(4),
                        latestCoverUri = if (cursor.isNull(5)) null else cursor.getString(5)
                    )
                )
            }
        }
        return list.map { it.copy(coverUris = recentMediaUris(db, it.id, 4)) }
    }

    private fun recentMediaUris(db: SQLiteDatabase, projectId: Long, limit: Int): List<String> {
        val uris = mutableListOf<String>()
        db.rawQuery(
            "SELECT media_uri FROM photo_records WHERE project_id = ? ORDER BY captured_at DESC, id DESC LIMIT $limit",
            arrayOf(projectId.toString())
        ).use { c ->
            while (c.moveToNext()) uris.add(c.getString(0))
        }
        return uris
    }

    /** Photos across all projects whose note or file name contains [query], newest first. */
    fun searchPhotos(query: String, limit: Int = 300): List<PhotoRecord> {
        val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val list = mutableListOf<PhotoRecord>()
        readableDatabase.rawQuery(
            "SELECT id, project_id, file_name, media_uri, relative_path, note, captured_at, width, height, " +
                "watermark_applied, exif_applied, source FROM photo_records WHERE note LIKE ? ESCAPE '\\' OR file_name LIKE ? ESCAPE '\\' " +
                "ORDER BY captured_at DESC, id DESC LIMIT $limit",
            arrayOf(like, like)
        ).use { c ->
            while (c.moveToNext()) list.add(readPhotoRecord(c))
        }
        return list
    }

    fun getProjectById(projectId: Long): ProjectItem? {
        val all = getAllProjectsWithSummary()
        return all.firstOrNull { it.id == projectId } ?: all.firstOrNull()
    }

    fun createProject(rawName: String): ProjectItem {
        val cleanName = rawName.trim().ifEmpty { "新建项目" }
        val folderName = FileNameSanitizer.sanitizeFolderName(cleanName)
        val now = System.currentTimeMillis()
        val db = writableDatabase

        // If already exists, return existing
        db.rawQuery(
            "SELECT id, name, folder_name, created_at FROM projects WHERE name = ? LIMIT 1",
            arrayOf(cleanName)
        ).use { c ->
            if (c.moveToFirst()) {
                return ProjectItem(
                    id = c.getLong(0),
                    name = c.getString(1),
                    folderName = c.getString(2),
                    createdAt = c.getLong(3)
                )
            }
        }

        val values = ContentValues().apply {
            put("name", cleanName)
            put("folder_name", folderName)
            put("created_at", now)
        }
        val id = db.insertOrThrow("projects", null, values)
        return ProjectItem(
            id = id,
            name = cleanName,
            folderName = folderName,
            createdAt = now
        )
    }

    fun renameProject(projectId: Long, newRawName: String): Boolean {
        val cleanName = newRawName.trim()
        if (cleanName.isEmpty()) return false
        readableDatabase.rawQuery(
            "SELECT id FROM projects WHERE name = ? AND id != ? LIMIT 1",
            arrayOf(cleanName, projectId.toString())
        ).use { c ->
            if (c.moveToFirst()) return false
        }
        val values = ContentValues().apply {
            put("name", cleanName)
            put("folder_name", FileNameSanitizer.sanitizeFolderName(cleanName))
        }
        writableDatabase.update("projects", values, "id = ?", arrayOf(projectId.toString()))
        return true
    }

    fun deleteProjectRecordOnly(projectId: Long) {
        if (projectId == SettingsRepository.DEFAULT_PROJECT_ID) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("photo_records", "project_id = ?", arrayOf(projectId.toString()))
            db.delete("projects", "id = ?", arrayOf(projectId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getPhotosForProject(projectId: Long): List<PhotoRecord> {
        val list = mutableListOf<PhotoRecord>()
        readableDatabase.rawQuery(
            """
            SELECT id, project_id, file_name, media_uri, relative_path, note,
                   captured_at, width, height, watermark_applied, exif_applied, source
            FROM photo_records
            WHERE project_id = ?
            ORDER BY captured_at DESC, id DESC
            """.trimIndent(),
            arrayOf(projectId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(readPhotoRecord(c))
            }
        }
        return list
    }

    fun getPhotoById(photoId: Long): PhotoRecord? {
        readableDatabase.rawQuery(
            """
            SELECT id, project_id, file_name, media_uri, relative_path, note,
                   captured_at, width, height, watermark_applied, exif_applied, source
            FROM photo_records
            WHERE id = ? LIMIT 1
            """.trimIndent(),
            arrayOf(photoId.toString())
        ).use { c ->
            return if (c.moveToFirst()) readPhotoRecord(c) else null
        }
    }

    fun getExistingFileNamesInProject(projectId: Long): Set<String> {
        val names = mutableSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT file_name FROM photo_records WHERE project_id = ?",
            arrayOf(projectId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                names.add(c.getString(0))
            }
        }
        return names
    }

    fun insertPhotoRecord(
        projectId: Long,
        fileName: String,
        mediaUri: String,
        relativePath: String,
        note: String,
        capturedAt: Long,
        width: Int,
        height: Int,
        watermarkApplied: Boolean,
        exifApplied: Boolean,
        source: String
    ): Long {
        val values = ContentValues().apply {
            put("project_id", projectId)
            put("file_name", fileName)
            put("media_uri", mediaUri)
            put("relative_path", relativePath)
            put("note", note)
            put("captured_at", capturedAt)
            put("width", width)
            put("height", height)
            put("watermark_applied", if (watermarkApplied) 1 else 0)
            put("exif_applied", if (exifApplied) 1 else 0)
            put("source", source)
        }
        return writableDatabase.insertOrThrow("photo_records", null, values)
    }

    fun updatePhotoNoteAndFileName(photoId: Long, newNote: String, newFileName: String, newMediaUri: String) {
        val values = ContentValues().apply {
            put("note", newNote)
            put("file_name", newFileName)
            put("media_uri", newMediaUri)
        }
        writableDatabase.update("photo_records", values, "id = ?", arrayOf(photoId.toString()))
    }

    fun updatePhotoProjectAndMedia(
        photoId: Long,
        targetProjectId: Long,
        newFileName: String,
        newMediaUri: String,
        newRelativePath: String
    ) {
        val values = ContentValues().apply {
            put("project_id", targetProjectId)
            put("file_name", newFileName)
            put("media_uri", newMediaUri)
            put("relative_path", newRelativePath)
        }
        writableDatabase.update("photo_records", values, "id = ?", arrayOf(photoId.toString()))
    }

    fun deletePhotoRecord(photoId: Long) {
        writableDatabase.delete("photo_records", "id = ?", arrayOf(photoId.toString()))
    }

    fun isSystemMediaSeen(mediaId: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM seen_system_media WHERE media_id = ? LIMIT 1",
            arrayOf(mediaId.toString())
        ).use { c ->
            return c.moveToFirst()
        }
    }

    fun markSystemMediaSeen(mediaId: Long) {
        val values = ContentValues().apply {
            put("media_id", mediaId)
            put("seen_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "seen_system_media",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        // Prune very old entries (> 2000 rows) to keep DB tiny
        writableDatabase.execSQL(
            """
            DELETE FROM seen_system_media
            WHERE media_id NOT IN (
                SELECT media_id FROM seen_system_media ORDER BY seen_at DESC LIMIT 1000
            )
            """.trimIndent()
        )
    }

    private fun readPhotoRecord(c: android.database.Cursor): PhotoRecord = PhotoRecord(
        id = c.getLong(0),
        projectId = c.getLong(1),
        fileName = c.getString(2),
        mediaUri = c.getString(3),
        relativePath = c.getString(4),
        note = c.getString(5),
        capturedAt = c.getLong(6),
        width = c.getInt(7),
        height = c.getInt(8),
        watermarkApplied = c.getInt(9) != 0,
        exifApplied = c.getInt(10) != 0,
        source = c.getString(11)
    )

    companion object {
        private const val DB_NAME = "stamper.db"
        private const val DB_VERSION = 1
        const val DEFAULT_PROJECT_NAME = "默认项目"
    }
}
