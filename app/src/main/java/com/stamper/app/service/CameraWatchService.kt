package com.stamper.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.stamper.app.MainActivity
import com.stamper.app.R
import com.stamper.app.StamperApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that watches `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` for newly
 * captured system camera photos and immediately pops up the floating voice annotation card.
 */
class CameraWatchService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var watchStartedEpochSec: Long = 0L

    private lateinit var overlayController: OverlayCaptureController
    private var mediaObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as StamperApp
        overlayController = OverlayCaptureController(this, app.repository)
        createNotificationChannels()
        watchStartedEpochSec = (System.currentTimeMillis() / 1000L) - 2L

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scheduleMediaScan()
            }
        }
        mediaObserver = observer
        runCatching {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        }
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_WATCH) {
            val app = application as StamperApp
            serviceScope.launch {
                app.repository.settingsRepository.setCameraWatchEnabled(false)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The microphone type lets the overlay run offline ASR while the system camera is on top.
            // It is only allowed when started from the foreground with RECORD_AUDIO granted.
            val hasMic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val started = hasMic && runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID_FOREGROUND,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }.isSuccess
            if (!started) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID_FOREGROUND,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID_FOREGROUND, notification)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        mediaObserver?.let {
            runCatching { contentResolver.unregisterContentObserver(it) }
        }
        mediaObserver = null
        overlayController.dismissAllAndDestroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleMediaScan() {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            // Allow system camera brief time to finalize JPEG write
            delay(450L)
            scanLatestCameraPhotos(attempt = 1)
        }
    }

    private suspend fun scanLatestCameraPhotos(attempt: Int) {
        val repository = (application as StamperApp).repository
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_TAKEN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
                add(MediaStore.Images.Media.IS_PENDING)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Images.Media.DATA)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(watchStartedEpochSec.toString())
        val sortOrder = "${MediaStore.Images.Media._ID} DESC"

        var sawPending = false

        runCatching {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                val pendingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                } else {
                    -1
                }
                @Suppress("DEPRECATION")
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                } else {
                    -1
                }

                var inspected = 0
                while (cursor.moveToNext() && inspected < 6) {
                    inspected++
                    val mediaId = cursor.getLong(idCol)
                    if (repository.isSystemMediaSeen(mediaId)) continue

                    if (pendingCol >= 0 && cursor.getInt(pendingCol) != 0) {
                        sawPending = true
                        continue
                    }

                    val pathInfo = when {
                        relPathCol >= 0 -> cursor.getString(relPathCol).orEmpty()
                        dataCol >= 0 -> cursor.getString(dataCol).orEmpty()
                        else -> ""
                    }
                    val displayName = cursor.getString(nameCol).orEmpty()

                    // Ignore Stamper's own output directory and screenshots
                    if (pathInfo.contains("Stamper", ignoreCase = true) ||
                        pathInfo.contains("Screenshots", ignoreCase = true) ||
                        displayName.startsWith("Screenshot", ignoreCase = true)
                    ) {
                        repository.markSystemMediaSeen(mediaId)
                        continue
                    }

                    repository.markSystemMediaSeen(mediaId)

                    val dateTaken = cursor.getLong(takenCol)
                    val dateAddedMs = cursor.getLong(addedCol) * 1000L
                    val capturedAt = if (dateTaken > 0L) dateTaken else dateAddedMs
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        mediaId
                    )

                    if (overlayController.canShowOverlay()) {
                        overlayController.enqueuePhoto(contentUri, capturedAt)
                    } else {
                        postFallbackPromptNotification(contentUri, capturedAt)
                    }
                }
            }
        }

        if (sawPending && attempt < 5) {
            delay(550L)
            scanLatestCameraPhotos(attempt + 1)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CameraWatchService::class.java).apply {
            action = ACTION_STOP_WATCH
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_notification_camera)
            .setContentTitle("正在监听系统相机拍照")
            .setContentText("用手机相机拍照后将自动弹出语音备注框")
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(0, "停止监听", stopPending)
            .build()
    }

    private fun postFallbackPromptNotification(photoUri: Uri, capturedAtMillis: Long) {
        val annotateIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_ANNOTATE_EXTERNAL_URI
            putExtra(EXTRA_PHOTO_URI, photoUri.toString())
            putExtra(EXTRA_CAPTURED_AT, capturedAtMillis)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            photoUri.hashCode(),
            annotateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_notification_camera)
            .setContentTitle("检测到新拍照片，点击语音录入")
            .setContentText("点击立即添加语音备注、边角水印与 EXIF 信息")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_PROMPT_BASE + (photoUri.hashCode() and 0x0FFF), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val watchChannel = NotificationChannel(
                CHANNEL_WATCH,
                "系统相机后台监听",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台监听手机原生相机新拍摄的照片"
            }
            val promptChannel = NotificationChannel(
                CHANNEL_PROMPT,
                "新照片语音录入提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "未开启悬浮窗权限时，提醒点击录入照片语音信息"
            }
            nm.createNotificationChannel(watchChannel)
            nm.createNotificationChannel(promptChannel)
        }
    }

    companion object {
        private const val CHANNEL_WATCH = "stamper_camera_watch"
        private const val CHANNEL_PROMPT = "stamper_photo_prompt"
        private const val NOTIFICATION_ID_FOREGROUND = 2001
        private const val NOTIFICATION_ID_PROMPT_BASE = 3000

        const val ACTION_STOP_WATCH = "com.stamper.app.action.STOP_WATCH"
        const val ACTION_ANNOTATE_EXTERNAL_URI = "com.stamper.app.action.ANNOTATE_EXTERNAL_URI"
        const val EXTRA_PHOTO_URI = "extra_photo_uri"
        const val EXTRA_CAPTURED_AT = "extra_captured_at"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, CameraWatchService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraWatchService::class.java))
        }
    }
}
