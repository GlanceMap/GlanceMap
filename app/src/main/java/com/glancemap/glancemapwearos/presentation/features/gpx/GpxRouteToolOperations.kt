package com.glancemap.glancemapwearos.presentation.features.gpx

import android.content.res.Resources
import androidx.annotation.StringRes
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterConfig
import com.glancemap.glancemapwearos.core.routing.RouteGeometryPoint
import com.glancemap.glancemapwearos.core.routing.RoutePlanner
import com.glancemap.glancemapwearos.core.routing.RoutePlannerRequest
import com.glancemap.glancemapwearos.data.repository.GpxRepositoryImpl
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteCreateMode
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteModifyMode
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteReshapeBounds
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteReshapeDirection
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteSaveBehavior
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolCreatePreview
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolEditOutput
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolKind
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolModifyPreview
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolPlannedCreation
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSaveResult
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteToolSession
import com.glancemap.glancemapwearos.presentation.features.routetools.buildRenamedGpxFileName
import com.glancemap.glancemapwearos.presentation.features.routetools.buildRouteToolEditOutput
import com.glancemap.glancemapwearos.presentation.features.routetools.buildRouteToolEndpointChangeOutput
import com.glancemap.glancemapwearos.presentation.features.routetools.buildRouteToolExtensionOutput
import com.glancemap.glancemapwearos.presentation.features.routetools.buildRouteToolReplaceSectionOutput
import com.glancemap.glancemapwearos.presentation.features.routetools.buildRouteToolReshapeOutput
import com.glancemap.glancemapwearos.presentation.features.routetools.buildRouteToolReshapePreview
import com.glancemap.glancemapwearos.presentation.features.routetools.encodeTrackAsGpx
import com.glancemap.glancemapwearos.presentation.features.routetools.pointAt
import com.glancemap.glancemapwearos.presentation.features.routetools.previewBeforeSaving
import com.glancemap.glancemapwearos.presentation.features.routetools.reshapeCandidateMatchesUserIntent
import com.glancemap.glancemapwearos.presentation.features.routetools.resolveReplaceSectionEndpoints
import com.glancemap.glancemapwearos.presentation.features.routetools.resolveRouteReshapeCandidateBounds
import com.glancemap.glancemapwearos.presentation.features.routetools.resolveRouteReshapeWaypoint
import com.glancemap.glancemapwearos.presentation.features.routetools.resolveRouteToolTrackMatch
import com.glancemap.glancemapwearos.presentation.features.routetools.resolveRouteToolTrackPosition
import com.glancemap.glancemapwearos.presentation.features.routetools.routeToolSnapThresholdMeters
import com.glancemap.glancemapwearos.presentation.features.routetools.toPlannerPreset
import com.glancemap.glancemapwearos.presentation.features.routetools.toRoutePlannerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import java.io.ByteArrayInputStream
import java.io.File

@Suppress("LargeClass")
internal class GpxRouteToolOperations(
    private val resources: Resources,
    private val gpxRepository: GpxRepositoryImpl,
    private val routePlanner: RoutePlanner,
    private val activeGpxFiles: () -> List<GpxFileState>,
    private val elevationFilterConfig: () -> GpxElevationFilterConfig,
    private val etaModelConfig: () -> GpxEtaModelConfig,
) {
    suspend fun applyModification(
        session: RouteToolSession,
        onProgress: (String) -> Unit = {},
    ): RouteToolSaveResult {
        require(session.options.toolKind == RouteToolKind.MODIFY) {
            resources.getString(R.string.gpx_only_gpx_modify_actions_are_supported_here)
        }

        val source = requireSingleActiveRouteToolSource()
        val editOutput = buildModificationEditOutput(source = source, session = session)

        val bytes =
            encodeTrackAsGpx(
                title = editOutput.title,
                points = editOutput.points,
            )

        reportProgress(onProgress, resources.getString(R.string.gpx_saving_gpx))
        gpxRepository.saveGpxFileAtomic(
            fileName = editOutput.fileName,
            inputStream = ByteArrayInputStream(bytes),
            onProgress = {},
            expectedSize = bytes.size.toLong(),
        )

        val savedFile =
            gpxRepository
                .listGpxFiles()
                .firstOrNull { it.name == editOutput.fileName }
                ?: error(resources.getString(R.string.gpx_the_edited_gpx_was_not_found_after_saving))

        gpxRepository.setActiveGpxFiles(setOf(savedFile.absolutePath))

        reportProgress(onProgress, resources.getString(R.string.gpx_preparing_stats))
        return buildRouteToolSaveResult(
            savedFile = savedFile,
            replacedCurrent = session.options.saveBehavior == RouteSaveBehavior.REPLACE_CURRENT,
        )
    }

    private suspend fun buildModificationEditOutput(
        source: ActiveRouteToolSource,
        session: RouteToolSession,
    ): RouteToolEditOutput =
        when (session.options.modifyMode) {
            RouteModifyMode.RESHAPE_ROUTE -> {
                val reshapePlan = buildReshapePlan(source = source, session = session)
                buildRouteToolReshapeOutput(
                    sourcePath = source.file.absolutePath,
                    sourceFileName = source.file.name,
                    sourceTitle = source.fileState.displayTitle,
                    profile = source.profile,
                    session = session,
                    firstLegPoints = reshapePlan.firstLegPoints,
                    secondLegPoints = reshapePlan.secondLegPoints,
                    bounds = reshapePlan.bounds,
                    resources = resources,
                )
            }

            RouteModifyMode.REPLACE_SECTION_A_TO_B -> {
                val sectionStart =
                    requireNotNull(session.pointA) {
                        resources.getString(R.string.gpx_pick_point_a_first)
                    }
                val sectionEnd =
                    requireNotNull(session.pointB) {
                        resources.getString(R.string.gpx_pick_point_b_first)
                    }
                val (origin, destination) =
                    resolveReplaceSectionEndpoints(
                        sourcePath = source.file.absolutePath,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        pointA = sectionStart,
                        pointB = sectionEnd,
                    )
                val plannerRequest =
                    RoutePlannerRequest(
                        origin = origin,
                        destination = destination,
                        preset = session.options.routeStyle.toPlannerPreset(),
                        useElevation = session.options.useElevation,
                        allowFerries = session.options.allowFerries,
                    )
                val route = routePlanner.createRoute(plannerRequest)
                buildRouteToolReplaceSectionOutput(
                    sourcePath = source.file.absolutePath,
                    sourceFileName = source.file.name,
                    sourceTitle = source.fileState.displayTitle,
                    profile = source.profile,
                    session = session,
                    routedPoints = route.points,
                    resources = resources,
                )
            }

            RouteModifyMode.TRIM_START_TO_HERE -> {
                val startTarget =
                    requireNotNull(session.pointA) {
                        resources.getString(R.string.gpx_pick_the_new_start_first)
                    }
                val match =
                    resolveRouteToolTrackMatch(
                        sourcePath = source.file.absolutePath,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        target = startTarget,
                    )
                val endpointPosition =
                    session.pointATrackPosition?.resolveRouteToolTrackPosition(source.profile)
                if (match.distanceMeters <= routeToolSnapThresholdMeters()) {
                    buildRouteToolEditOutput(
                        sourcePath = source.file.absolutePath,
                        sourceFileName = source.file.name,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        session = session.copy(pointATrackPosition = match.position),
                        resources = resources,
                    )
                } else if (endpointPosition != null) {
                    val originalStart = pointAt(source.profile.points, endpointPosition).latLong
                    val route =
                        routePlanner.createRoute(
                            RoutePlannerRequest(
                                origin = startTarget,
                                destination = originalStart,
                                preset = session.options.routeStyle.toPlannerPreset(),
                                useElevation = session.options.useElevation,
                                allowFerries = session.options.allowFerries,
                            ),
                        )
                    buildRouteToolEndpointChangeOutput(
                        sourceFileName = source.file.name,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        session = session,
                        snappedPosition = endpointPosition,
                        routedPoints = route.points,
                        resources = resources,
                    )
                } else {
                    val route =
                        routePlanner.createRoute(
                            RoutePlannerRequest(
                                origin = startTarget,
                                destination = match.latLong,
                                preset = session.options.routeStyle.toPlannerPreset(),
                                useElevation = session.options.useElevation,
                                allowFerries = session.options.allowFerries,
                            ),
                        )
                    buildRouteToolEndpointChangeOutput(
                        sourceFileName = source.file.name,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        session = session,
                        snappedPosition = match.position,
                        routedPoints = route.points,
                        resources = resources,
                    )
                }
            }

            RouteModifyMode.TRIM_END_FROM_HERE -> {
                val endTarget =
                    requireNotNull(session.pointB) {
                        resources.getString(R.string.gpx_pick_the_new_end_first)
                    }
                val match =
                    resolveRouteToolTrackMatch(
                        sourcePath = source.file.absolutePath,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        target = endTarget,
                    )
                val endpointPosition =
                    session.pointBTrackPosition?.resolveRouteToolTrackPosition(source.profile)
                if (match.distanceMeters <= routeToolSnapThresholdMeters()) {
                    buildRouteToolEditOutput(
                        sourcePath = source.file.absolutePath,
                        sourceFileName = source.file.name,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        session = session.copy(pointBTrackPosition = match.position),
                        resources = resources,
                    )
                } else if (endpointPosition != null) {
                    val originalEnd = pointAt(source.profile.points, endpointPosition).latLong
                    val route =
                        routePlanner.createRoute(
                            RoutePlannerRequest(
                                origin = originalEnd,
                                destination = endTarget,
                                preset = session.options.routeStyle.toPlannerPreset(),
                                useElevation = session.options.useElevation,
                                allowFerries = session.options.allowFerries,
                            ),
                        )
                    buildRouteToolEndpointChangeOutput(
                        sourceFileName = source.file.name,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        session = session,
                        snappedPosition = endpointPosition,
                        routedPoints = route.points,
                        resources = resources,
                    )
                } else {
                    val route =
                        routePlanner.createRoute(
                            RoutePlannerRequest(
                                origin = match.latLong,
                                destination = endTarget,
                                preset = session.options.routeStyle.toPlannerPreset(),
                                useElevation = session.options.useElevation,
                                allowFerries = session.options.allowFerries,
                            ),
                        )
                    buildRouteToolEndpointChangeOutput(
                        sourceFileName = source.file.name,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        session = session,
                        snappedPosition = match.position,
                        routedPoints = route.points,
                        resources = resources,
                    )
                }
            }

            else ->
                buildRouteToolEditOutput(
                    sourcePath = source.file.absolutePath,
                    sourceFileName = source.file.name,
                    sourceTitle = source.fileState.displayTitle,
                    profile = source.profile,
                    session = session,
                    resources = resources,
                )
        }

    suspend fun previewModification(session: RouteToolSession): RouteToolModifyPreview {
        require(session.options.toolKind == RouteToolKind.MODIFY) {
            resources.getString(R.string.gpx_only_gpx_modify_actions_are_supported_here)
        }
        require(session.options.modifyMode.previewBeforeSaving) {
            resources.getString(R.string.gpx_preview_is_only_available_for_reshape_and_endpoint_edits)
        }

        val source = requireSingleActiveRouteToolSource()
        if (session.options.modifyMode == RouteModifyMode.RESHAPE_ROUTE) {
            val reshapePlan = buildReshapePlan(source = source, session = session)
            return buildRouteToolReshapePreview(
                sourcePath = source.file.absolutePath,
                sourceTitle = source.fileState.displayTitle,
                profile = source.profile,
                session = session,
                firstLegPoints = reshapePlan.firstLegPoints,
                secondLegPoints = reshapePlan.secondLegPoints,
                bounds = reshapePlan.bounds,
            )
        }

        val editOutput = buildModificationEditOutput(source = source, session = session)
        return RouteToolModifyPreview(previewPoints = editOutput.points.map { it.latLong })
    }

    suspend fun previewCreation(
        session: RouteToolSession,
        currentLocation: LatLong?,
    ): RouteToolCreatePreview {
        require(session.options.toolKind == RouteToolKind.CREATE) {
            resources.getString(R.string.gpx_only_gpx_create_actions_are_supported_here)
        }
        require(
            session.options.createMode == RouteCreateMode.MULTI_POINT_CHAIN ||
                session.options.createMode == RouteCreateMode.LOOP_AROUND_HERE,
        ) {
            resources.getString(R.string.gpx_preview_is_only_available_for_multi_point_and_loop_creation)
        }

        val route =
            when (session.options.createMode) {
                RouteCreateMode.MULTI_POINT_CHAIN -> {
                    val plannerRequest = session.toRoutePlannerRequest(currentLocation, resources = resources)
                    routePlanner.createRoute(plannerRequest)
                }

                RouteCreateMode.LOOP_AROUND_HERE -> {
                    createLoopRoute(
                        routePlanner = routePlanner,
                        elevationFilterConfig = elevationFilterConfig,
                        etaModelConfig = etaModelConfig,
                        session = session,
                        currentLocation = currentLocation,
                        resources = resources,
                    )
                }
            }
        val previewPoints = route.points.map { it.latLong }
        val profile =
            buildRouteOutputProfile(
                route = route,
                elevationFilterConfig = elevationFilterConfig,
            )
        return RouteToolCreatePreview(
            previewPoints = previewPoints,
            distanceMeters = profile.totalDistance,
            elevationGainMeters = profile.totalAscent,
            elevationLossMeters = profile.totalDescent,
            estimatedDurationSec = buildEtaProjection(profile, etaModelConfig())?.totalSeconds,
            plannedCreation =
                RouteToolPlannedCreation(
                    fileName = route.fileName,
                    gpxBytes = route.gpxBytes,
                ),
            multiPointChainPointCount =
                session.chainPoints.size.takeIf {
                    session.options.createMode == RouteCreateMode.MULTI_POINT_CHAIN
                },
        )
    }

    suspend fun applyCreation(
        session: RouteToolSession,
        currentLocation: LatLong?,
        preview: RouteToolCreatePreview? = null,
        onProgress: (String) -> Unit = {},
    ): RouteToolSaveResult {
        require(session.options.toolKind == RouteToolKind.CREATE) {
            resources.getString(R.string.gpx_only_gpx_create_actions_are_supported_here)
        }

        val currentActive = gpxRepository.getActiveGpxFiles().first()
        return when (session.options.createMode) {
            RouteCreateMode.ACTIVE_GPX_END_TO_HERE -> {
                val source = requireSingleActiveRouteToolSource()
                val plannerRequest =
                    session.toRoutePlannerRequest(
                        currentLocation = currentLocation,
                        activeGpxEnd =
                            source.profile.points
                                .lastOrNull()
                                ?.latLong,
                        resources = resources,
                    )
                val route = routePlanner.createRoute(plannerRequest)
                val extensionOutput =
                    buildRouteToolExtensionOutput(
                        sourceFileName = source.file.name,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        routedPoints = route.points,
                        resources = resources,
                    )
                val extensionBytes =
                    encodeTrackAsGpx(
                        title = extensionOutput.title,
                        points = extensionOutput.points,
                    )

                reportProgress(onProgress, resources.getString(R.string.gpx_saving_gpx))
                gpxRepository.saveGpxFileAtomic(
                    fileName = extensionOutput.fileName,
                    inputStream = ByteArrayInputStream(extensionBytes),
                    onProgress = {},
                    expectedSize = extensionBytes.size.toLong(),
                )

                val savedFile =
                    gpxRepository
                        .listGpxFiles()
                        .firstOrNull { it.name == extensionOutput.fileName }
                        ?: error(resources.getString(R.string.gpx_the_extended_gpx_was_not_found_after_saving))

                gpxRepository.setActiveGpxFiles(
                    (currentActive - source.file.absolutePath) + savedFile.absolutePath,
                )

                reportProgress(onProgress, resources.getString(R.string.gpx_preparing_stats))
                buildRouteToolSaveResult(
                    savedFile = savedFile,
                    replacedCurrent = false,
                )
            }

            RouteCreateMode.LOOP_AROUND_HERE -> {
                val plannedCreation =
                    preview?.plannedCreation ?: run {
                        val route =
                            createLoopRoute(
                                routePlanner = routePlanner,
                                elevationFilterConfig = elevationFilterConfig,
                                etaModelConfig = etaModelConfig,
                                session = session,
                                currentLocation = currentLocation,
                                resources = resources,
                            )
                        RouteToolPlannedCreation(
                            fileName = route.fileName,
                            gpxBytes = route.gpxBytes,
                        )
                    }
                saveCreatedRoute(
                    plannedCreation = plannedCreation,
                    currentActive = currentActive,
                    onProgress = onProgress,
                )
            }

            else -> {
                val plannedCreation =
                    preview?.plannedCreation ?: run {
                        val plannerRequest = session.toRoutePlannerRequest(currentLocation, resources = resources)
                        val route = routePlanner.createRoute(plannerRequest)
                        RouteToolPlannedCreation(
                            fileName = route.fileName,
                            gpxBytes = route.gpxBytes,
                        )
                    }
                saveCreatedRoute(
                    plannedCreation = plannedCreation,
                    currentActive = currentActive,
                    onProgress = onProgress,
                )
            }
        }
    }

    suspend fun renameSavedRoute(
        filePath: String,
        newName: String,
    ): RouteToolSaveResult {
        val savedFile =
            renameGpxFileOnDisk(
                filePath = filePath,
                newName = newName,
            )
        return buildRouteToolSaveResult(
            savedFile = savedFile,
            replacedCurrent = false,
            successMessageRes = R.string.gpx_gpx_renamed,
        )
    }

    suspend fun renameGpxFileOnDisk(
        filePath: String,
        newName: String,
    ): File {
        val sourceFile = File(filePath)
        require(sourceFile.exists()) { resources.getString(R.string.gpx_the_gpx_could_not_be_found) }

        val normalizedTitle =
            newName
                .trim()
                .replace(Regex("\\s+"), " ")
        require(normalizedTitle.isNotBlank()) { resources.getString(R.string.gpx_enter_a_gpx_name_first) }

        val parsed = parseGpxData(sourceFile)
        require(parsed.points.isNotEmpty()) { resources.getString(R.string.gpx_the_gpx_does_not_contain_any_points) }

        val targetFileName = buildRenamedGpxFileName(normalizedTitle)
        val targetMatchesSource = targetFileName.equals(sourceFile.name, ignoreCase = true)
        if (!targetMatchesSource && gpxRepository.fileExists(targetFileName)) {
            error(resources.getString(R.string.gpx_a_gpx_with_that_name_already_exists))
        }

        val bytes = renameGpxXmlTitle(sourceFile.readText(), normalizedTitle)
        gpxRepository.saveGpxFileAtomic(
            fileName = targetFileName,
            inputStream = ByteArrayInputStream(bytes),
            onProgress = {},
            expectedSize = bytes.size.toLong(),
        )

        val savedFile =
            gpxRepository
                .listGpxFiles()
                .firstOrNull { it.name.equals(targetFileName, ignoreCase = true) }
                ?: error(resources.getString(R.string.gpx_the_renamed_gpx_was_not_found_after_saving))

        val currentActive = gpxRepository.getActiveGpxFiles().first()
        if (filePath in currentActive || savedFile.absolutePath in currentActive) {
            val updatedActive = (currentActive - filePath) + savedFile.absolutePath
            gpxRepository.setActiveGpxFiles(updatedActive)
        }

        if (!sourceFile.absolutePath.equals(savedFile.absolutePath, ignoreCase = true)) {
            gpxRepository.deleteGpxFile(sourceFile.absolutePath)
        }

        return savedFile
    }

    private suspend fun requireSingleActiveRouteToolSource(): ActiveRouteToolSource {
        val activeFiles = activeGpxFiles().filter { it.isActive }
        require(activeFiles.size == 1) {
            resources.getString(R.string.gpx_activate_exactly_one_gpx_before_using_this_tool)
        }

        val sourceFileState = activeFiles.single()
        val sourceFile = File(sourceFileState.path)
        require(sourceFile.exists()) { resources.getString(R.string.gpx_the_active_gpx_could_not_be_found_on_disk) }

        val parsed = parseGpxData(sourceFile)
        require(parsed.points.size >= 2) {
            resources.getString(R.string.gpx_the_active_gpx_does_not_contain_enough_points_to_edit)
        }

        return ActiveRouteToolSource(
            fileState = sourceFileState,
            file = sourceFile,
            parsed = parsed,
            profile =
                buildProfile(
                    sig = sigOf(sourceFile),
                    pts = parsed.points,
                    elevationFilterConfig = elevationFilterConfig(),
                ),
        )
    }

    private suspend fun buildReshapePlan(
        source: ActiveRouteToolSource,
        session: RouteToolSession,
    ): RouteToolReshapePlan {
        val selectedPoint =
            requireNotNull(session.pointA) {
                resources.getString(R.string.gpx_select_the_route_point_first)
            }
        val shapingPoint =
            requireNotNull(session.destination) {
                resources.getString(R.string.gpx_pick_the_shaping_point_first)
            }
        val sourceTitle = source.fileState.displayTitle
        val segmentStart =
            resolveRouteReshapeWaypoint(
                sourcePath = source.file.absolutePath,
                sourceTitle = sourceTitle,
                profile = source.profile,
                anchor = selectedPoint,
                direction = RouteReshapeDirection.START,
            )
        val firstLeg =
            routePlanner.createRoute(
                RoutePlannerRequest(
                    origin = segmentStart,
                    destination = shapingPoint,
                    preset = session.options.routeStyle.toPlannerPreset(),
                    useElevation = session.options.useElevation,
                    allowFerries = session.options.allowFerries,
                ),
            )

        val candidateBounds =
            resolveRouteReshapeCandidateBounds(
                sourcePath = source.file.absolutePath,
                sourceTitle = sourceTitle,
                profile = source.profile,
                anchor = selectedPoint,
                rejoinHint = shapingPoint,
            )

        var fallbackPlan: RouteToolReshapePlan? = null
        for (bounds in candidateBounds) {
            val secondLeg =
                routePlanner.createRoute(
                    RoutePlannerRequest(
                        origin = shapingPoint,
                        destination = bounds.endPoint.latLong,
                        preset = session.options.routeStyle.toPlannerPreset(),
                        useElevation = session.options.useElevation,
                        allowFerries = session.options.allowFerries,
                    ),
                )
            val plan =
                RouteToolReshapePlan(
                    bounds = bounds,
                    firstLegPoints = firstLeg.points,
                    secondLegPoints = secondLeg.points,
                )
            if (fallbackPlan == null) {
                fallbackPlan = plan
            }
            if (
                reshapeCandidateMatchesUserIntent(
                    sourcePath = source.file.absolutePath,
                    sourceTitle = sourceTitle,
                    profile = source.profile,
                    anchor = selectedPoint,
                    shapingPoint = shapingPoint,
                    bounds = bounds,
                    firstLegPoints = firstLeg.points,
                    secondLegPoints = secondLeg.points,
                )
            ) {
                return plan
            }
        }

        return fallbackPlan ?: error(resources.getString(R.string.gpx_could_not_build_the_reshape_route))
    }

    private fun buildRouteToolSaveResult(
        savedFile: File,
        replacedCurrent: Boolean,
        @StringRes successMessageRes: Int? = null,
    ): RouteToolSaveResult {
        val parsed = parseGpxData(savedFile)
        val profile =
            buildProfile(
                sig = sigOf(savedFile),
                pts = parsed.points,
                elevationFilterConfig = elevationFilterConfig(),
            )
        val etaSeconds = buildEtaProjection(profile, etaModelConfig())?.totalSeconds
        return RouteToolSaveResult(
            fileName = savedFile.name,
            filePath = savedFile.absolutePath,
            displayTitle =
                normalizeUserFacingGpxText(savedFile.nameWithoutExtension)
                    ?: savedFile.nameWithoutExtension,
            distanceMeters = profile.totalDistance,
            elevationGainMeters = profile.totalAscent,
            elevationLossMeters = profile.totalDescent,
            estimatedDurationSec = etaSeconds,
            replacedCurrent = replacedCurrent,
            successMessageRes = successMessageRes,
        )
    }

    private suspend fun saveCreatedRoute(
        plannedCreation: RouteToolPlannedCreation,
        currentActive: Set<String>,
        onProgress: (String) -> Unit,
    ): RouteToolSaveResult {
        reportProgress(onProgress, resources.getString(R.string.gpx_saving_gpx))
        gpxRepository.saveGpxFileAtomic(
            fileName = plannedCreation.fileName,
            inputStream = ByteArrayInputStream(plannedCreation.gpxBytes),
            onProgress = {},
            expectedSize = plannedCreation.gpxBytes.size.toLong(),
        )

        val savedFile =
            gpxRepository
                .listGpxFiles()
                .firstOrNull { it.name == plannedCreation.fileName }
                ?: error(resources.getString(R.string.gpx_the_generated_gpx_was_not_found_after_saving))

        gpxRepository.setActiveGpxFiles(currentActive + savedFile.absolutePath)

        reportProgress(onProgress, resources.getString(R.string.gpx_preparing_stats))
        return buildRouteToolSaveResult(
            savedFile = savedFile,
            replacedCurrent = false,
        )
    }

    private suspend fun reportProgress(
        onProgress: (String) -> Unit,
        message: String,
    ) {
        withContext(Dispatchers.Main) {
            onProgress(message)
        }
    }
}

private data class ActiveRouteToolSource(
    val fileState: GpxFileState,
    val file: File,
    val parsed: ParsedGpxData,
    val profile: TrackProfile,
)

private data class RouteToolReshapePlan(
    val bounds: RouteReshapeBounds,
    val firstLegPoints: List<RouteGeometryPoint>,
    val secondLegPoints: List<RouteGeometryPoint>,
)
