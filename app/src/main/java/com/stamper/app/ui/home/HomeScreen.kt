package com.stamper.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stamper.app.R
import com.stamper.app.data.ProjectItem
import com.stamper.app.data.StamperRepository
import com.stamper.app.ui.components.HintIconButton
import com.stamper.app.ui.components.WithHint
import com.stamper.app.ui.theme.StamperPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    repository: StamperRepository,
    onOpenCamera: () -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onImportUrisSelected: (List<Uri>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val projects by repository.projects.collectAsState()
    val activeProjectId by repository.settingsRepository.activeProjectIdFlow.collectAsState(initial = 1L)
    val activeId = projects.firstOrNull { it.id == activeProjectId }?.id ?: projects.firstOrNull()?.id

    var showCreateDialog by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris -> if (uris.isNotEmpty()) onImportUrisSelected(uris) }

    fun setActive(id: Long) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { repository.settingsRepository.setActiveProjectId(id) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            item(span = { GridItemSpan(2) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(1.45f)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                        HintIconButton(icon = Icons.Outlined.Tune, hint = "设置", onClick = onOpenSettings)
                    }
                }
            }

            items(projects, key = { it.id }) { project ->
                GroupTile(
                    project = project,
                    isActive = project.id == activeId,
                    onOpen = { onOpenProject(project.id) },
                    onSetActive = { setActive(project.id) },
                    onShoot = onOpenCamera
                )
            }

            item(key = "add") {
                AddGroupTile(onClick = { showCreateDialog = true })
            }
        }

        BottomDock(
            onImport = {
                photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onShoot = onOpenCamera,
            onSearch = onOpenSearch,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                scope.launch { repository.createAndSelectProject(name) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTile(
    project: ProjectItem,
    isActive: Boolean,
    onOpen: () -> Unit,
    onSetActive: () -> Unit,
    onShoot: () -> Unit
) {
    val ring by animateDpAsState(if (isActive) 3.dp else 0.dp, label = "ring")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f / 1.12f)
            .shadow(if (isActive) 10.dp else 3.dp, RoundedCornerShape(22.dp), ambientColor = StamperPalette.Gold, spotColor = if (isActive) StamperPalette.Gold else Color.Black)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(ring, StamperPalette.Gold, RoundedCornerShape(22.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onSetActive)
            .padding(6.dp)
    ) {
        CoverMosaic(uris = project.coverUris, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 17.dp, bottomEnd = 17.dp))
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                .padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 8.dp)
        ) {
            Text(
                text = project.name,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = project.photoCount.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (isActive) {
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
            WithHint("拍入此组") {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(32.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(StamperPalette.Gold)
                        .clickable(onClick = onShoot)
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = "拍入此组", tint = StamperPalette.GoldInk, modifier = Modifier.size(18.dp))
                }
            }
            }
        }
    }
}

@Composable
private fun CoverMosaic(uris: List<String>, modifier: Modifier = Modifier) {
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = modifier.clip(RoundedCornerShape(17.dp)).background(placeholder)) {
        when (uris.size) {
            0 -> Icon(
                Icons.Outlined.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
            )
            1 -> AsyncImage(model = uris[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxSize()) {
                MosaicCell(uris[0], Modifier.weight(1f))
                MosaicCell(uris[1], Modifier.weight(1f))
            }
            3 -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxSize()) {
                MosaicCell(uris[0], Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                    MosaicCell(uris[1], Modifier.weight(1f))
                    MosaicCell(uris[2], Modifier.weight(1f))
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxSize()) {
                for (rowIdx in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                        MosaicCell(uris[rowIdx * 2], Modifier.weight(1f))
                        MosaicCell(uris[rowIdx * 2 + 1], Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MosaicCell(uri: String, modifier: Modifier) {
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun AddGroupTile(onClick: () -> Unit) {
    val dashColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    WithHint("新建分组") {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.12f)
                .clip(RoundedCornerShape(22.dp))
                .drawBehind {
                    drawRoundRect(
                        color = dashColor,
                        cornerRadius = CornerRadius(22.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)))
                    )
                }
                .clickable(onClick = onClick)
        ) {
            Icon(
                Icons.Outlined.CreateNewFolder,
                contentDescription = "新建分组",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
private fun BottomDock(
    onImport: () -> Unit,
    onShoot: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, bottom = 18.dp)
            .height(112.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(38.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(horizontal = 22.dp)
            ) {
                HintIconButton(icon = Icons.Outlined.Image, hint = "相册补录", onClick = onImport, iconSize = 26.dp)
                HintIconButton(icon = Icons.Outlined.Search, hint = "搜索", onClick = onSearch, iconSize = 26.dp)
            }
        }
        WithHint("拍照") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(y = (-34).dp)
                    .size(98.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(7.dp)
                    .shadow(10.dp, CircleShape, spotColor = StamperPalette.Gold)
                    .clip(CircleShape)
                    .background(StamperPalette.Gold)
                    .clickable(onClick = onShoot)
            ) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = "拍照", tint = StamperPalette.GoldInk, modifier = Modifier.size(38.dp))
            }
        }
    }
}

@Composable
fun CreateGroupDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("分组名") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            HintIconButton(
                icon = Icons.Rounded.Check,
                hint = "创建",
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim()) },
                colors = IconButtonDefaults.filledIconButtonColors()
            )
        },
        dismissButton = {
            HintIconButton(icon = Icons.Rounded.Close, hint = "取消", onClick = onDismiss)
        }
    )
}
