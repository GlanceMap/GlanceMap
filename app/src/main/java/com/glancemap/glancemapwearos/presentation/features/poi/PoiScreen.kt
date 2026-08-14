@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glancemap.glancemapwearos.presentation.features.poi

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewComfyAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.data.repository.USER_POI_CATEGORY_ID
import com.glancemap.glancemapwearos.data.repository.USER_POI_SOURCE_PATH
import com.glancemap.glancemapwearos.presentation.navigation.WatchRoutes
import com.glancemap.glancemapwearos.presentation.ui.CompactIconHitTargetButton
import com.glancemap.glancemapwearos.presentation.ui.DeleteConfirmationDialog
import com.glancemap.glancemapwearos.presentation.ui.FeatureListHeader
import com.glancemap.glancemapwearos.presentation.ui.FeatureListScaffold
import com.glancemap.glancemapwearos.presentation.ui.RenameValueDialog
import com.glancemap.glancemapwearos.presentation.ui.WearInfoDialog
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize
import kotlinx.coroutines.launch

private sealed interface PoiListRow {
    data class SectionHeader(
        val label: String,
    ) : PoiListRow

    data class FileRow(
        val file: PoiFileUiState,
    ) : PoiListRow

    data class CategoryRow(
        val filePath: String,
        val category: PoiCategoryUiState,
        val isExpanded: Boolean,
    ) : PoiListRow

    data class CategoryPoiRow(
        val filePath: String,
        val categoryId: Int,
        val depth: Int,
        val point: PoiCategoryPreviewPointUiState,
    ) : PoiListRow

    data class CategoryInfoRow(
        val filePath: String,
        val categoryId: Int,
        val depth: Int,
        val text: String,
        val isError: Boolean = false,
    ) : PoiListRow
}

private fun MutableList<PoiListRow>.addCategoryPreviewRows(
    filePath: String,
    categoryId: Int,
    depth: Int,
    preview: PoiCategoryPreviewUiState?,
    emptyText: String,
) {
    when {
        preview == null || preview.isLoading -> {
            add(
                PoiListRow.CategoryInfoRow(
                    filePath = filePath,
                    categoryId = categoryId,
                    depth = depth,
                    text = "Loading POI...",
                ),
            )
        }

        preview.errorMessage != null -> {
            add(
                PoiListRow.CategoryInfoRow(
                    filePath = filePath,
                    categoryId = categoryId,
                    depth = depth,
                    text = preview.errorMessage,
                    isError = true,
                ),
            )
        }

        preview.totalPoiCount == 0 -> {
            add(
                PoiListRow.CategoryInfoRow(
                    filePath = filePath,
                    categoryId = categoryId,
                    depth = depth,
                    text = emptyText,
                ),
            )
        }

        else -> {
            preview.points.forEach { point ->
                add(
                    PoiListRow.CategoryPoiRow(
                        filePath = filePath,
                        categoryId = categoryId,
                        depth = depth,
                        point = point,
                    ),
                )
            }
            if (preview.hasMore) {
                add(
                    PoiListRow.CategoryInfoRow(
                        filePath = filePath,
                        categoryId = categoryId,
                        depth = depth,
                        text = "${preview.points.size}/${preview.totalPoiCount} POI shown",
                    ),
                )
            }
        }
    }
}

private const val POI_HELP_PREFS = "poi_screen_help_prefs"
private const val POI_HELP_SHOWN_KEY = "poi_help_shown"
private const val POI_LOADING_DRAG_DISMISS_PX = 34f

@Composable
fun PoiScreen(
    navController: NavHostController,
    poiViewModel: PoiViewModel,
) {
    val screenSize = rememberWearScreenSize()
    val adaptive = rememberWearAdaptiveSpec()
    val scope = rememberCoroutineScope()
    val poiFiles by poiViewModel.poiFiles.collectAsState()
    val isLoadingPoiFiles by poiViewModel.isLoadingPoiFiles.collectAsState()
    val poiTapToCenterEnabled by poiViewModel.poiTapToCenterEnabled.collectAsState()
    val categoryPreviews by poiViewModel.categoryPreviews.collectAsState()
    val categoryCounts by poiViewModel.categoryCounts.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var isDeleteMode by remember { mutableStateOf(false) }
    var isRenameMode by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<PoiFileUiState?>(null) }
    var poiToRename by remember { mutableStateOf<PoiCategoryPreviewPointUiState?>(null) }
    var poiToDelete by remember { mutableStateOf<PoiCategoryPreviewPointUiState?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInProgress by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var loadingOverlayDismissed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val helpPrefs =
        remember(context) {
            context.getSharedPreferences(POI_HELP_PREFS, android.content.Context.MODE_PRIVATE)
        }

    val listHorizontalPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 16.dp
            WearScreenSize.MEDIUM -> 14.dp
            WearScreenSize.SMALL -> 12.dp
        }
    val listTopPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 1.dp
            WearScreenSize.MEDIUM -> 0.dp
            WearScreenSize.SMALL -> 0.dp
        }
    val listBottomPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 2.dp
            WearScreenSize.MEDIUM -> 1.dp
            WearScreenSize.SMALL -> 0.dp
        }
    val headerTopPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 8.dp
            WearScreenSize.MEDIUM -> 6.dp
            WearScreenSize.SMALL -> 4.dp
        }
    val headerBottomPadding = 0.dp
    val headerActionButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 24.dp
            WearScreenSize.MEDIUM -> 22.dp
            WearScreenSize.SMALL -> 20.dp
        }
    val headerActionIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 14.dp
            WearScreenSize.MEDIUM -> 13.dp
            WearScreenSize.SMALL -> 12.dp
        }
    val headerActionVisualOffsetY =
        when (screenSize) {
            WearScreenSize.LARGE -> 4.dp
            WearScreenSize.MEDIUM -> 4.dp
            WearScreenSize.SMALL -> 3.dp
        }
    val headerActionSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> 4.dp
            WearScreenSize.MEDIUM -> 3.dp
            WearScreenSize.SMALL -> 2.dp
        }
    val headerVerticalSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> (-14).dp
            WearScreenSize.MEDIUM -> (-15).dp
            WearScreenSize.SMALL -> (-16).dp
        }
    val rowSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> 8.dp
            WearScreenSize.MEDIUM -> 7.dp
            WearScreenSize.SMALL -> 5.dp
        }
    val emptyStatePadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 16.dp
            WearScreenSize.MEDIUM -> 14.dp
            WearScreenSize.SMALL -> 12.dp
        }
    val settingsButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 28.dp
            WearScreenSize.MEDIUM -> 26.dp
            WearScreenSize.SMALL -> 24.dp
        }
    val bottomActionBottomPadding = 0.dp
    val bottomActionVisualOffsetY =
        when (screenSize) {
            WearScreenSize.LARGE -> (-6).dp
            WearScreenSize.MEDIUM -> (-5).dp
            WearScreenSize.SMALL -> (-4).dp
        }
    val fileActionButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 32.dp
            WearScreenSize.MEDIUM -> 30.dp
            WearScreenSize.SMALL -> 28.dp
        }
    val fileActionIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 18.dp
            WearScreenSize.MEDIUM -> 16.dp
            WearScreenSize.SMALL -> 14.dp
        }
    val categoryActionButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 24.dp
            WearScreenSize.MEDIUM -> 22.dp
            WearScreenSize.SMALL -> 20.dp
        }
    val categoryActionIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 14.dp
            WearScreenSize.MEDIUM -> 12.dp
            WearScreenSize.SMALL -> 10.dp
        }
    val categoryIndentStep =
        when (screenSize) {
            WearScreenSize.LARGE -> 8.dp
            WearScreenSize.MEDIUM -> 7.dp
            WearScreenSize.SMALL -> 5.dp
        }
    val compactMode = screenSize == WearScreenSize.SMALL
    val headerTopSafePadding = headerTopPadding + adaptive.headerTopSafeInset
    val expandedCategoryIdsByFile = remember { mutableStateMapOf<String, Set<Int>>() }

    LaunchedEffect(helpPrefs) {
        if (!helpPrefs.getBoolean(POI_HELP_SHOWN_KEY, false)) {
            showHelpDialog = true
        }
    }
    LaunchedEffect(isLoadingPoiFiles) {
        if (isLoadingPoiFiles) {
            loadingOverlayDismissed = false
        }
    }

    fun toggleCategoryExpanded(
        filePath: String,
        categoryId: Int,
    ) {
        val current = expandedCategoryIdsByFile[filePath].orEmpty()
        expandedCategoryIdsByFile[filePath] =
            if (categoryId in current) {
                current - categoryId
            } else {
                current + categoryId
            }
    }

    val expandedByFileSnapshot = expandedCategoryIdsByFile.toMap()
    val rows =
        remember(poiFiles, categoryPreviews, isDeleteMode, expandedByFileSnapshot) {
            buildList<PoiListRow> {
                val userFiles = poiFiles.filter { it.path == USER_POI_SOURCE_PATH }
                val waypointFiles =
                    poiFiles.filter { it.path != USER_POI_SOURCE_PATH && it.isGpxWaypointFolder }
                val importedPoiFiles =
                    poiFiles.filter { it.path != USER_POI_SOURCE_PATH && !it.isGpxWaypointFolder }
                val sections =
                    listOf(
                        "My creation" to userFiles,
                        "GPX waypoints" to waypointFiles,
                        "POI files" to importedPoiFiles,
                    )

                sections.forEach { (label, files) ->
                    if (files.isEmpty()) return@forEach
                    add(PoiListRow.SectionHeader(label))
                    files.forEach { file ->
                        add(PoiListRow.FileRow(file))
                        val showExpandedRows =
                            file.isExpanded &&
                                (
                                    !isDeleteMode || file.path == USER_POI_SOURCE_PATH
                                )
                        if (showExpandedRows) {
                            if (file.path == USER_POI_SOURCE_PATH) {
                                addCategoryPreviewRows(
                                    filePath = file.path,
                                    categoryId = USER_POI_CATEGORY_ID,
                                    depth = 1,
                                    preview =
                                        categoryPreviews[
                                            PoiCategoryPreviewKey(file.path, USER_POI_CATEGORY_ID),
                                        ],
                                    emptyText = "No saved places yet.",
                                )
                                return@forEach
                            }
                            val expandedIds = expandedByFileSnapshot[file.path].orEmpty()
                            val categoriesById = file.categories.associateBy { it.id }
                            file.categories.forEach { category ->
                                if (isCategoryVisible(category, categoriesById, expandedIds)) {
                                    val isCategoryExpanded = category.id in expandedIds
                                    add(
                                        PoiListRow.CategoryRow(
                                            filePath = file.path,
                                            category = category,
                                            isExpanded = isCategoryExpanded,
                                        ),
                                    )
                                    if (isCategoryExpanded) {
                                        val isSyntheticGroup = category.id < 0 && category.hasChildren
                                        if (!isSyntheticGroup) {
                                            val poiDepth = category.depth + 1
                                            addCategoryPreviewRows(
                                                filePath = file.path,
                                                categoryId = category.id,
                                                depth = poiDepth,
                                                preview =
                                                    categoryPreviews[
                                                        PoiCategoryPreviewKey(file.path, category.id),
                                                    ],
                                                emptyText =
                                                    if (category.hasChildren) {
                                                        "No direct POI. Expand sub-folders."
                                                    } else {
                                                        "No POI in this folder."
                                                    },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    val hasDownloadedPoiFiles = remember(poiFiles) { poiFiles.any { it.path != USER_POI_SOURCE_PATH } }

    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    LaunchedEffect(Unit) {
        listState.scrollToItem(0)
    }

    fun dismissHelpDialog() {
        showHelpDialog = false
        helpPrefs.edit().putBoolean(POI_HELP_SHOWN_KEY, true).apply()
    }

    LaunchedEffect(Unit) {
        poiViewModel.loadPoiFiles(collapseAll = true)
    }
    LaunchedEffect(poiFiles.size) {
        if (poiFiles.isEmpty()) {
            isDeleteMode = false
            isRenameMode = false
        }
    }
    LaunchedEffect(poiFiles) {
        val validByFile =
            poiFiles.associate { file ->
                file.path to file.categories.map { it.id }.toSet()
            }
        expandedCategoryIdsByFile.keys.toList().forEach { filePath ->
            val validIds = validByFile[filePath]
            if (validIds == null) {
                expandedCategoryIdsByFile.remove(filePath)
            } else {
                val filtered = expandedCategoryIdsByFile[filePath].orEmpty().intersect(validIds)
                if (filtered.isEmpty()) {
                    expandedCategoryIdsByFile.remove(filePath)
                } else if (filtered != expandedCategoryIdsByFile[filePath]) {
                    expandedCategoryIdsByFile[filePath] = filtered
                }
            }
        }
    }
    LaunchedEffect(poiFiles, expandedByFileSnapshot, isDeleteMode) {
        if (isDeleteMode) return@LaunchedEffect
        poiFiles.forEach { file ->
            if (!file.isExpanded) return@forEach
            if (file.path == USER_POI_SOURCE_PATH) return@forEach
            val expandedIds = expandedByFileSnapshot[file.path].orEmpty()
            val categoriesById = file.categories.associateBy { it.id }
            file.categories.forEach { category ->
                if (isCategoryVisible(category, categoriesById, expandedIds)) {
                    poiViewModel.loadCategoryCount(file.path, category.id)
                }
            }
        }
    }
    LaunchedEffect(poiFiles, expandedByFileSnapshot) {
        poiFiles.forEach { file ->
            if (!file.isExpanded) return@forEach
            if (file.path == USER_POI_SOURCE_PATH) {
                poiViewModel.loadCategoryPreview(file.path, USER_POI_CATEGORY_ID)
                return@forEach
            }
            val categoriesById = file.categories.associateBy { it.id }
            expandedByFileSnapshot[file.path].orEmpty().forEach { categoryId ->
                val category = categoriesById[categoryId] ?: return@forEach
                val isSyntheticGroup = category.id < 0 && category.hasChildren
                if (!isSyntheticGroup) {
                    poiViewModel.loadCategoryPreview(file.path, categoryId)
                }
            }
        }
    }

    ScreenScaffold(scrollState = listState) {
        DeleteConfirmationDialog(
            visible = showDeleteDialog,
            title =
                if (fileToDelete?.path == USER_POI_SOURCE_PATH) {
                    "Delete all created places?"
                } else if (poiToDelete != null) {
                    "Delete created place?"
                } else {
                    "Delete POI file?"
                },
            message =
                if (fileToDelete?.path == USER_POI_SOURCE_PATH) {
                    "Delete every place in mycreation?"
                } else if (poiToDelete != null) {
                    "Delete '${poiToDelete?.name}'?"
                } else {
                    "Delete '${fileToDelete?.name}'?"
                },
            onConfirm = {
                when {
                    poiToDelete != null -> {
                        val target = poiToDelete
                        scope.launch {
                            target?.let {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                poiViewModel.deleteMyCreationPoi(it.id)
                            }
                        }
                    }
                    fileToDelete != null -> {
                        fileToDelete?.path?.let {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            poiViewModel.deletePoiFile(it)
                        }
                    }
                }
                showDeleteDialog = false
                fileToDelete = null
                poiToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                fileToDelete = null
                poiToDelete = null
            },
        )

        RenameValueDialog(
            visible = showRenameDialog && poiToRename != null,
            title = "Rename POI",
            initialValue = poiToRename?.name.orEmpty(),
            isSaving = renameInProgress,
            error = renameError,
            fullScreen = true,
            onDismiss = {
                if (!renameInProgress) {
                    showRenameDialog = false
                    poiToRename = null
                    renameError = null
                }
            },
            onConfirm = { newName ->
                val target = poiToRename ?: return@RenameValueDialog
                if (renameInProgress) return@RenameValueDialog
                renameInProgress = true
                renameError = null
                scope.launch {
                    runCatching {
                        poiViewModel.renameMyCreationPoi(target.id, newName)
                    }.onSuccess {
                        renameInProgress = false
                        showRenameDialog = false
                        poiToRename = null
                        renameError = null
                    }.onFailure { error ->
                        renameInProgress = false
                        renameError = error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "Failed to rename the POI."
                    }
                }
            },
        )

        WearInfoDialog(
            visible = showHelpDialog,
            title = "POI Actions",
            onDismiss = { dismissHelpDialog() },
        ) {
            item {
                Text(
                    "Toggle POI files or categories to show them on the map.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "Expand a category to preview its POIs.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "Tap a POI on the map to see details.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text =
                        buildAnnotatedString {
                            append("Create POI by using the tool button (")
                            appendInlineContent("toolButton", "[tool]")
                            append(").")
                        },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    inlineContent =
                        mapOf(
                            "toolButton" to
                                InlineTextContent(
                                    placeholder =
                                        Placeholder(
                                            width = 16.sp,
                                            height = 16.sp,
                                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                                        ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ViewComfyAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.White,
                                    )
                                },
                        ),
                )
            }
        }

        FeatureListScaffold {
            FeatureListHeader(
                title = "POI",
                titleStyle =
                    if (adaptive.isCompact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                topPadding = headerTopSafePadding,
                bottomPadding = headerBottomPadding,
                actionSpacing = headerActionSpacing,
                verticalSpacing = headerVerticalSpacing,
                statusText =
                    when {
                        isDeleteMode -> "Delete mode"
                        isRenameMode -> "Rename mode"
                        else -> null
                    },
                statusColor =
                    if (isDeleteMode) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            ) {
                CompactIconHitTargetButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showHelpDialog = true
                    },
                    visualSize = headerActionButtonSize,
                    visualOffsetY = headerActionVisualOffsetY,
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    contentColor = Color.White,
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "POI actions help",
                        modifier = Modifier.size(headerActionIconSize),
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding =
                        PaddingValues(
                            top = listTopPadding,
                            start = listHorizontalPadding,
                            end = listHorizontalPadding,
                            bottom = listBottomPadding,
                        ),
                    anchorType = ScalingLazyListAnchorType.ItemStart,
                    autoCentering = null,
                ) {
                    if (rows.isEmpty()) {
                        item {
                            Text(
                                text = "Loading POI...",
                                modifier = Modifier.padding(emptyStatePadding),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    items(
                        items = rows,
                        key = { row ->
                            when (row) {
                                is PoiListRow.SectionHeader -> "s:${row.label}"
                                is PoiListRow.FileRow -> "f:${row.file.path}"
                                is PoiListRow.CategoryRow -> "c:${row.filePath}:${row.category.id}"
                                is PoiListRow.CategoryPoiRow -> {
                                    "p:${row.filePath}:${row.categoryId}:${row.point.id}"
                                }
                                is PoiListRow.CategoryInfoRow -> {
                                    "i:${row.filePath}:${row.categoryId}:${row.text}"
                                }
                            }
                        },
                    ) { row ->
                        when (row) {
                            is PoiListRow.SectionHeader -> {
                                Text(
                                    text = row.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            is PoiListRow.FileRow -> {
                                PoiFileRow(
                                    file = row.file,
                                    showDelete = isDeleteMode,
                                    rowSpacing = rowSpacing,
                                    actionButtonSize = fileActionButtonSize,
                                    actionIconSize = fileActionIconSize,
                                    compactMode = compactMode,
                                    onToggle = { checked ->
                                        poiViewModel.setFileEnabled(row.file.path, checked)
                                    },
                                    onToggleExpanded = {
                                        poiViewModel.toggleExpanded(row.file.path)
                                    },
                                    onDelete = {
                                        fileToDelete = row.file
                                        showDeleteDialog = true
                                    },
                                )
                            }

                            is PoiListRow.CategoryRow -> {
                                PoiCategoryRow(
                                    category = row.category,
                                    categoryCount =
                                        categoryCounts[
                                            PoiCategoryPreviewKey(row.filePath, row.category.id),
                                        ],
                                    isExpanded = row.isExpanded,
                                    categoryIndentStep = categoryIndentStep,
                                    actionButtonSize = categoryActionButtonSize,
                                    actionIconSize = categoryActionIconSize,
                                    compactMode = compactMode,
                                    onToggle = { checked ->
                                        poiViewModel.setCategoryEnabled(
                                            path = row.filePath,
                                            categoryId = row.category.id,
                                            enabled = checked,
                                        )
                                    },
                                    onToggleExpanded = {
                                        toggleCategoryExpanded(row.filePath, row.category.id)
                                    },
                                )
                            }

                            is PoiListRow.CategoryPoiRow -> {
                                PoiCategoryPoiRow(
                                    point = row.point,
                                    filePath = row.filePath,
                                    depth = row.depth,
                                    categoryIndentStep = categoryIndentStep,
                                    compactMode = compactMode,
                                    tapToCenterEnabled = poiTapToCenterEnabled,
                                    showDelete = isDeleteMode && isUserPoiFile(row.filePath),
                                    showRename = isRenameMode && isUserPoiFile(row.filePath),
                                    onDelete = {
                                        poiToDelete = row.point
                                        fileToDelete = null
                                        showDeleteDialog = true
                                    },
                                    onRename = {
                                        poiToRename = row.point
                                        showRenameDialog = true
                                        renameError = null
                                    },
                                    onClick = {
                                        if (!poiTapToCenterEnabled) return@PoiCategoryPoiRow
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        poiViewModel.requestNavigateToPoi(row.point)
                                        val popped =
                                            navController.popBackStack(
                                                WatchRoutes.NAVIGATE,
                                                inclusive = false,
                                            )
                                        if (!popped) {
                                            navController.navigate(WatchRoutes.NAVIGATE) {
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                )
                            }

                            is PoiListRow.CategoryInfoRow -> {
                                PoiCategoryInfoRow(
                                    text = row.text,
                                    depth = row.depth,
                                    categoryIndentStep = categoryIndentStep,
                                    isError = row.isError,
                                )
                            }
                        }
                    }

                    if (rows.isNotEmpty() && !hasDownloadedPoiFiles) {
                        item(key = "no-downloaded-poi-files") {
                            Text(
                                text = "Download POIs on the watch or send .poi files from the companion phone app.",
                                modifier = Modifier.padding(emptyStatePadding),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = bottomActionBottomPadding),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (poiFiles.isNotEmpty()) {
                        CompactIconHitTargetButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val nextRenameMode = !isRenameMode
                                isRenameMode = nextRenameMode
                                if (nextRenameMode) {
                                    isDeleteMode = false
                                }
                            },
                            visualSize = headerActionButtonSize,
                            visualOffsetY = bottomActionVisualOffsetY,
                            containerColor =
                                if (isRenameMode) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Black.copy(alpha = 0.8f)
                                },
                            contentColor =
                                if (isRenameMode) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    Color.White
                                },
                        ) {
                            Icon(
                                imageVector = if (isRenameMode) Icons.Default.Close else Icons.Default.Edit,
                                contentDescription =
                                    if (isRenameMode) {
                                        "Exit rename mode"
                                    } else {
                                        "Enter rename mode"
                                    },
                                modifier = Modifier.size(headerActionIconSize),
                            )
                        }
                    }
                    CompactIconHitTargetButton(
                        onClick = { navController.navigate(WatchRoutes.POI_SETTINGS) },
                        visualSize = settingsButtonSize,
                        visualOffsetY = bottomActionVisualOffsetY,
                        containerColor = Color.Black.copy(alpha = 0.8f),
                        contentColor = Color.White,
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "POI Settings")
                    }
                    if (poiFiles.isNotEmpty()) {
                        CompactIconHitTargetButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isDeleteMode = !isDeleteMode
                                if (isDeleteMode) {
                                    isRenameMode = false
                                }
                            },
                            visualSize = headerActionButtonSize,
                            visualOffsetY = bottomActionVisualOffsetY,
                            containerColor =
                                if (isDeleteMode) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    Color.Black.copy(alpha = 0.8f)
                                },
                            contentColor =
                                if (isDeleteMode) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    Color.White
                                },
                        ) {
                            Icon(
                                imageVector = if (isDeleteMode) Icons.Default.Close else Icons.Default.Delete,
                                contentDescription =
                                    if (isDeleteMode) {
                                        "Exit delete mode"
                                    } else {
                                        "Enter delete mode"
                                    },
                                modifier = Modifier.size(headerActionIconSize),
                            )
                        }
                    }
                }
            }
        }

        if (isLoadingPoiFiles && !loadingOverlayDismissed && !showHelpDialog && !showDeleteDialog && !showRenameDialog) {
            PoiLoadingOverlay(
                onDismiss = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    loadingOverlayDismissed = true
                },
            )
        }
    }
}

@Composable
private fun PoiLoadingOverlay(onDismiss: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.78f)
                    .pointerInput(onDismiss) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onDragEnd = { totalDrag = 0f },
                            onDragCancel = { totalDrag = 0f },
                        ) { _, dragAmount ->
                            totalDrag += dragAmount
                            if (totalDrag > POI_LOADING_DRAG_DISMISS_PX) {
                                onDismiss()
                                totalDrag = 0f
                            }
                        }
                    }.background(
                        color = Color.Black.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(18.dp),
                    ).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(26.dp)
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.42f), RoundedCornerShape(50)),
                )
                Text(
                    text = "Loading POI...",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Downloaded POIs will appear shortly.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
