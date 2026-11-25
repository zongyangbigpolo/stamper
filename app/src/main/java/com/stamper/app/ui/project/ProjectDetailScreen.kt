package com.stamper.app.ui.project

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.stamper.app.data.PhotoRecord
import com.stamper.app.data.ProjectItem
import com.stamper.app.data.SettingsRepository
import com.stamper.app.data.StamperRepository
import com.stamper.app.domain.stamper.CaptureSource
import com.stamper.app.domain.stamper.PhotoMetadataInspection
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DISPLAY_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun ProjectDetailScreen(
    projectId: Long,
    repository: StamperRepository,
    onNavigateBack: () -> Unit,
    onOpenCamera: () -> Unit,
    onExportProjectZip: (Long) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val projects by repository.projects.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    val photos by repository.observePhotosForProject(projectId).collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    val filteredPhotos = remember(photos, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            photos
        } else {
            photos.filter {
                it.note.contains(q, ignoreCase = true) ||
                    it.fileName.contains(q, ignoreCase = true)
            }
        }
    }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedPhotoIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedPhotoIds = emptySet()
    }

    var selectedPhoto by remember { mutableStateOf<PhotoRecord?>(null) }
    var zoomViewingPhoto by remember { mutableStateOf<PhotoRecord?>(null) }

    var showEditNoteDialog by remember { mutableStateOf(false) }
    var editingNoteText by remember { mutableStateOf("") }
    var isUpdatingNote by remember { mutableStateOf(false) }

    var inspectedMetadata by remember { mutableStateOf<PhotoMetadataInspection?>(null) }
    var showMetadataDialog by remember { mutableStateOf(false) }
    var isLoadingMetadata by remember { mutableStateOf(false) }

    // Move photos between project groups (supports both 1 photo & batch multi-select)
    var movingPhotoIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var showMoveProjectDialog by remember { mutableStateOf(false) }
    var isMovingPhotos by remember { mutableStateOf(false) }

    // Safe delete confirmation for photos
    var deletingPhotoIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var showDeletePhotosConfirmDialog by remember { mutableStateOf(false) }
    var isDeletingPhotos by remember { mutableStateOf(false) }

    var showDeleteProjectDialog by remember { mutableStateOf(false) }
    var showRenameProjectDialog by remember { mutableStateOf(false) }
    var renamingProjectText by remember { mutableStateOf("") }
    var isRenamingProject by remember { mutableStateOf(false) }

    fun togglePhotoSelection(id: Long) {
        selectedPhotoIds = if (id in selectedPhotoIds) {
            selectedPhotoIds - id
        } else {
            selectedPhotoIds + id
        }
    }

    fun sharePhotosByIds(ids: Collection<Long>) {
        val records = photos.filter { it.id in ids }
        if (records.isEmpty()) return
        if (records.size == 1) {
            val single = records.first()
            val uri = Uri.parse(single.mediaUri)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(single.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享照片"))
        } else {
            val uris = ArrayList(records.map { Uri.parse(it.mediaUri) })
            val clip = ClipData.newRawUri("Stamper Photos", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "批量分享 ${uris.size} 张照片"))
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "已选择 ${selectedPhotoIds.size} 张",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                selectionMode = false
                                selectedPhotoIds = emptySet()
                            }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        val allVisibleIds = filteredPhotos.map { it.id }.toSet()
                        val allSelected = allVisibleIds.isNotEmpty() && selectedPhotoIds.containsAll(allVisibleIds)
                        TextButton(
                            onClick = {
                                selectedPhotoIds = if (allSelected) {
                                    selectedPhotoIds - allVisibleIds
                                } else {
                                    selectedPhotoIds + allVisibleIds
                                }
                            }
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (allSelected) "取消全选" else "全选")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = project?.name ?: "项目详情",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "共 ${photos.size} 张 · Pictures/Stamper/${project?.folderName.orEmpty()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (photos.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    selectionMode = true
                                    selectedPhotoIds = emptySet()
                                }
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = "多选移动或管理照片")
                            }
                        }
                        if (project != null) {
                            IconButton(
                                onClick = {
                                    renamingProjectText = project.name
                                    showRenameProjectDialog = true
                                }
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "修改项目名称")
                            }
                        }
                        if (photos.isNotEmpty()) {
                            IconButton(onClick = { onExportProjectZip(projectId) }) {
                                Icon(Icons.Default.FolderZip, contentDescription = "打包导出 ZIP")
                            }
                        }
                        if (projectId != SettingsRepository.DEFAULT_PROJECT_ID) {
                            IconButton(onClick = { showDeleteProjectDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除项目")
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            repository.settingsRepository.setActiveProjectId(projectId)
                        }
                        onOpenCamera()
                    },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    text = { Text("拍入此分组") }
                )
            }
        },
        bottomBar = {
            if (selectionMode) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Button(
                            enabled = selectedPhotoIds.isNotEmpty(),
                            onClick = {
                                movingPhotoIds = selectedPhotoIds.toList()
                                showMoveProjectDialog = true
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1.4f)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.DriveFileMove,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("变更分组", maxLines = 1)
                        }

                        FilledTonalButton(
                            enabled = selectedPhotoIds.isNotEmpty(),
                            onClick = { sharePhotosByIds(selectedPhotoIds) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("分享", maxLines = 1)
                        }

                        OutlinedButton(
                            enabled = selectedPhotoIds.isNotEmpty(),
                            onClick = {
                                deletingPhotoIds = selectedPhotoIds.toList()
                                showDeletePhotosConfirmDialog = true
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除", color = MaterialTheme.colorScheme.error, maxLines = 1)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (photos.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "该分组还没有归档照片",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击右下角“拍入此分组”即可边拍边说，也可在其他分组长按照片将其迁移到这里。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                // Search & helper hint bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = {
                        Text("搜索备注关键词（如 302、投影仪）…")
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除搜索")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) {
                            "提示：长按照片可批量选择并变更项目分组"
                        } else {
                            "搜索到 ${filteredPhotos.size} / ${photos.size} 张照片"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!selectionMode && filteredPhotos.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectionMode = true
                                selectedPhotoIds = emptySet()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("批量移组", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (filteredPhotos.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "没有包含“$searchQuery”的照片备注",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 6.dp,
                            bottom = innerPadding.calculateBottomPadding() + 88.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredPhotos, key = { it.id }) { photo ->
                            val isSelected = photo.id in selectedPhotoIds
                            PhotoGridCard(
                                photo = photo,
                                selectionMode = selectionMode,
                                isSelected = isSelected,
                                onClick = {
                                    if (selectionMode) {
                                        togglePhotoSelection(photo.id)
                                    } else {
                                        selectedPhoto = photo
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedPhotoIds = setOf(photo.id)
                                    } else {
                                        togglePhotoSelection(photo.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Photo Detail Bottom Sheet
    selectedPhoto?.let { photo ->
        ModalBottomSheet(
            onDismissRequest = { selectedPhoto = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { zoomViewingPhoto = photo }
                ) {
                    AsyncImage(
                        model = photo.mediaUri,
                        contentDescription = photo.note,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    Surface(
                        color = Color.Black.copy(alpha = 0.58f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ZoomIn,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "点击全屏放大",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = photo.note.ifEmpty { "（未填写语音备注）" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "文件名：${photo.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "归档目录：${photo.relativePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val formattedTime = remember(photo.capturedAt) {
                    DISPLAY_TIME_FMT.format(
                        Instant.ofEpochMilli(photo.capturedAt).atZone(ZoneId.systemDefault())
                    )
                }
                Text(
                    text = "时间：$formattedTime · ${photo.width}×${photo.height} · ${CaptureSource.fromWireValue(photo.source).label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Primary Action: Move Project Group!
                Button(
                    onClick = {
                        movingPhotoIds = listOf(photo.id)
                        showMoveProjectDialog = true
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.DriveFileMove,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "变更所属项目分组（当前：${project?.name ?: "默认项目"}）",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalButton(
                        onClick = {
                            editingNoteText = photo.note
                            showEditNoteDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("改备注/文件名", maxLines = 1)
                    }

                    FilledTonalButton(
                        onClick = {
                            isLoadingMetadata = true
                            showMetadataDialog = true
                            scope.launch {
                                inspectedMetadata = repository.inspectMetadata(photo)
                                isLoadingMetadata = false
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("查看元数据", maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { sharePhotosByIds(listOf(photo.id)) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("分享照片", maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            deletingPhotoIds = listOf(photo.id)
                            showDeletePhotosConfirmDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("删除照片", color = MaterialTheme.colorScheme.error, maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Full-screen pinch-to-zoom & double-tap viewer
    zoomViewingPhoto?.let { photo ->
        FullScreenZoomablePhotoDialog(
            photo = photo,
            onDismiss = { zoomViewingPhoto = null }
        )
    }

    // Move Single or Batch Photos to Another Project Group Dialog
    if (showMoveProjectDialog && movingPhotoIds.isNotEmpty()) {
        MovePhotosProjectDialog(
            photoCount = movingPhotoIds.size,
            currentProjectId = projectId,
            projects = projects,
            isMoving = isMovingPhotos,
            onDismiss = {
                if (!isMovingPhotos) {
                    showMoveProjectDialog = false
                    movingPhotoIds = emptyList()
                }
            },
            onMoveToExisting = { targetProj ->
                isMovingPhotos = true
                val idsToMove = movingPhotoIds
                scope.launch {
                    repository.movePhotosToProject(idsToMove, targetProj.id)
                        .onSuccess { moved ->
                            isMovingPhotos = false
                            showMoveProjectDialog = false
                            movingPhotoIds = emptyList()
                            if (selectedPhoto?.id in idsToMove) {
                                selectedPhoto = null
                            }
                            selectionMode = false
                            selectedPhotoIds = emptySet()
                            Toast.makeText(
                                context,
                                "已将 $moved 张照片移至「${targetProj.name}」",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .onFailure { err ->
                            isMovingPhotos = false
                            Toast.makeText(
                                context,
                                "迁移失败: ${err.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            },
            onCreateAndMove = { newName ->
                isMovingPhotos = true
                val idsToMove = movingPhotoIds
                scope.launch {
                    repository.createProjectAndMovePhotos(newName, idsToMove)
                        .onSuccess { (created, moved) ->
                            isMovingPhotos = false
                            showMoveProjectDialog = false
                            movingPhotoIds = emptyList()
                            if (selectedPhoto?.id in idsToMove) {
                                selectedPhoto = null
                            }
                            selectionMode = false
                            selectedPhotoIds = emptySet()
                            Toast.makeText(
                                context,
                                "已新建「${created.name}」并移入 $moved 张照片",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .onFailure { err ->
                            isMovingPhotos = false
                            Toast.makeText(
                                context,
                                "新建并迁移失败: ${err.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        )
    }

    // Confirm Photo Deletion Dialog (Single or Batch)
    if (showDeletePhotosConfirmDialog && deletingPhotoIds.isNotEmpty()) {
        val count = deletingPhotoIds.size
        AlertDialog(
            onDismissRequest = {
                if (!isDeletingPhotos) {
                    showDeletePhotosConfirmDialog = false
                    deletingPhotoIds = emptyList()
                }
            },
            title = { Text(if (count == 1) "确认删除这张照片？" else "确认删除选中的 $count 张照片？") },
            text = {
                Text("删除后将同时移除手机相册 Pictures/Stamper 下的对应照片文件，此操作无法恢复。")
            },
            confirmButton = {
                Button(
                    enabled = !isDeletingPhotos,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    onClick = {
                        isDeletingPhotos = true
                        val ids = deletingPhotoIds
                        scope.launch {
                            repository.deletePhotos(ids)
                            isDeletingPhotos = false
                            showDeletePhotosConfirmDialog = false
                            deletingPhotoIds = emptyList()
                            if (selectedPhoto?.id in ids) {
                                selectedPhoto = null
                            }
                            selectionMode = false
                            selectedPhotoIds = emptySet()
                            Toast.makeText(context, "已删除 $count 张照片", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(if (isDeletingPhotos) "删除中…" else "确认删除")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeletingPhotos,
                    onClick = {
                        showDeletePhotosConfirmDialog = false
                        deletingPhotoIds = emptyList()
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    // Edit Note & Filename Dialog
    if (showEditNoteDialog && selectedPhoto != null) {
        val targetPhoto = selectedPhoto!!
        AlertDialog(
            onDismissRequest = { if (!isUpdatingNote) showEditNoteDialog = false },
            title = { Text("更新备注元数据与文件名") },
            text = {
                OutlinedTextField(
                    value = editingNoteText,
                    onValueChange = { editingNoteText = it },
                    label = { Text("照片说明") },
                    supportingText = {
                        Text("将同步更新照片内部的 EXIF UserComment、XMP 描述以及图片文件名（已合成在画面上的像素水印保持不变）")
                    },
                    maxLines = 4
                )
            },
            confirmButton = {
                Button(
                    enabled = !isUpdatingNote,
                    onClick = {
                        isUpdatingNote = true
                        scope.launch {
                            repository.updatePhotoNoteMetadata(targetPhoto.id, editingNoteText)
                                .onSuccess { updated ->
                                    selectedPhoto = updated
                                    isUpdatingNote = false
                                    showEditNoteDialog = false
                                    Toast.makeText(
                                        context,
                                        "已更新为: ${updated.fileName}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .onFailure { err ->
                                    isUpdatingNote = false
                                    Toast.makeText(
                                        context,
                                        "更新失败: ${err.localizedMessage}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        }
                    }
                ) {
                    Text(if (isUpdatingNote) "写入中…" else "保存更新")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isUpdatingNote,
                    onClick = { showEditNoteDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }

    // Live EXIF & XMP Inspector Dialog
    if (showMetadataDialog) {
        AlertDialog(
            onDismissRequest = { showMetadataDialog = false },
            title = { Text("照片内嵌 EXIF / XMP 校验") },
            text = {
                if (isLoadingMetadata) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    val info = inspectedMetadata
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        MetadataSection(
                            label = "EXIF UserComment 解析结果 (AI 推荐直读)",
                            value = info?.parsedStamperMetadata?.toPrettyUtf8Json() ?: "（无结构化 JSON）"
                        )
                        MetadataSection(
                            label = "EXIF ImageDescription",
                            value = info?.imageDescription ?: "（空）"
                        )
                        MetadataSection(
                            label = "EXIF DateTimeOriginal & 尺寸",
                            value = "${info?.dateTimeOriginal ?: "未知"} | ${info?.dimensions ?: "未知"}" +
                                (info?.gpsLatLng?.let { "\nGPS: ${it.first}, ${it.second}" } ?: "")
                        )
                        MetadataSection(
                            label = "XMP UTF-8 数据包 (dc:description)",
                            value = info?.xmpPacket ?: "（未检测到 XMP 段）"
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMetadataDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (showRenameProjectDialog && project != null) {
        AlertDialog(
            onDismissRequest = { if (!isRenamingProject) showRenameProjectDialog = false },
            title = { Text("修改项目分组名称") },
            text = {
                OutlinedTextField(
                    value = renamingProjectText,
                    onValueChange = { renamingProjectText = it },
                    singleLine = true,
                    label = { Text("项目名称") },
                    supportingText = {
                        Text("该分组内的照片文件夹与 EXIF/XMP 项目名也将自动同步更新")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = renamingProjectText.trim()
                        if (clean.isNotEmpty() && !isRenamingProject) {
                            isRenamingProject = true
                            scope.launch {
                                repository.renameProject(projectId, clean)
                                    .onSuccess {
                                        isRenamingProject = false
                                        showRenameProjectDialog = false
                                        Toast.makeText(context, "已重命名为「$clean」", Toast.LENGTH_SHORT).show()
                                    }
                                    .onFailure { err ->
                                        isRenamingProject = false
                                        Toast.makeText(
                                            context,
                                            err.localizedMessage ?: "重命名失败",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }
                        }
                    },
                    enabled = renamingProjectText.isNotBlank() && !isRenamingProject
                ) {
                    Text(if (isRenamingProject) "同步中…" else "保存")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isRenamingProject,
                    onClick = { showRenameProjectDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeleteProjectDialog && project != null) {
        AlertDialog(
            onDismissRequest = { showDeleteProjectDialog = false },
            title = { Text("删除项目分组“${project.name}”？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "• 仅从 Stamper 移除分组记录：手机相册 Pictures/Stamper/${project.folderName} 里的图片文件将保留在相册中，但不再显示在本应用内。\n• 连同照片文件彻底删除：将同时从手机相册删除该分组下的全部 ${photos.size} 张照片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FilledTonalButton(
                        onClick = {
                            showDeleteProjectDialog = false
                            scope.launch {
                                repository.deleteProject(projectId, deleteMediaFiles = false)
                                Toast.makeText(context, "已移除分组记录（相册原图已保留）", Toast.LENGTH_SHORT).show()
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("仅移除分组记录（保留相册照片）")
                    }
                    Button(
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        onClick = {
                            showDeleteProjectDialog = false
                            scope.launch {
                                repository.deleteProject(projectId, deleteMediaFiles = true)
                                Toast.makeText(context, "已删除分组及照片文件", Toast.LENGTH_SHORT).show()
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("连同相册照片彻底删除")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeleteProjectDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Keep selectedPhoto synchronized if list updates
    LaunchedEffect(photos) {
        val current = selectedPhoto ?: return@LaunchedEffect
        selectedPhoto = photos.firstOrNull { it.id == current.id }
    }
}

@Composable
private fun MovePhotosProjectDialog(
    photoCount: Int,
    currentProjectId: Long,
    projects: List<ProjectItem>,
    isMoving: Boolean,
    onDismiss: () -> Unit,
    onMoveToExisting: (ProjectItem) -> Unit,
    onCreateAndMove: (String) -> Unit
) {
    var showCreateField by remember { mutableStateOf(projects.size <= 1) }
    var newGroupName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (photoCount == 1) "变更照片所属项目分组" else "将 $photoCount 张照片移至其他分组",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "迁移后将自动同步更新手机相册 Pictures/Stamper/<目标分组> 目录及照片 EXIF/XMP 项目信息：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isMoving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Text("正在迁移照片文件并更新元数据…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    projects.forEach { item ->
                        val isCurrent = item.id == currentProjectId
                        OutlinedCard(
                            onClick = {
                                if (!isCurrent) onMoveToExisting(item)
                            },
                            enabled = !isCurrent,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                }
                            ),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (isCurrent) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = if (isCurrent) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${item.photoCount} 张 · Pictures/Stamper/${item.folderName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isCurrent) {
                                    Text(
                                        text = "当前所在",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                                        contentDescription = "移入此分组",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    if (!showCreateField) {
                        FilledTonalButton(
                            onClick = { showCreateField = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("新建项目分组并直接移入")
                        }
                    } else {
                        OutlinedTextField(
                            value = newGroupName,
                            onValueChange = { newGroupName = it },
                            singleLine = true,
                            label = { Text("新建项目分组名（如：3号楼弱电）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = newGroupName.isNotBlank(),
                            onClick = { onCreateAndMove(newGroupName.trim()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("创建新分组并移入")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(enabled = !isMoving, onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun FullScreenZoomablePhotoDialog(
    photo: PhotoRecord,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 4.5f)
                        scale = newScale
                        offset = if (newScale <= 1.02f) {
                            Offset.Zero
                        } else {
                            Offset(
                                x = offset.x + pan.x,
                                y = offset.y + pan.y
                            )
                        }
                    }
                }
        ) {
            AsyncImage(
                model = photo.mediaUri,
                contentDescription = photo.note,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(14.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = photo.note.ifEmpty { photo.fileName },
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                IconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = "关闭全屏预览")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGridCard(
    photo: PhotoRecord,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) {
            BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = photo.mediaUri,
                    contentDescription = photo.note,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (selectionMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.Black.copy(alpha = 0.45f)
                            )
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "已选中",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = photo.note.ifEmpty { "未填写备注" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = photo.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MetadataSection(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
