package com.glancemap.glancemapwearos.presentation.features.gpx

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
            "Only GPX modify actions are supported here."
        }

        val source = requireSingleActiveRouteToolSource()
        val editOutput = buildModificationEditOutput(source = source, session = session)

        val bytes =
            encodeTrackAsGpx(
                title = editOutput.title,
                points = editOutput.points,
            )

        reportProgress(onProgress, "Saving GPX...")
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
                ?: error("The edited GPX was not found after saving.")

        gpxRepository.setActiveGpxFiles(setOf(savedFile.absolutePath))

        reportProgress(onProgress, "Preparing stats...")
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
                )
            }

            RouteModifyMode.REPLACE_SECTION_A_TO_B -> {
                val sectionStart =
                    requireNotNull(session.pointA) {
                        "Pick point A first."
                    }
                val sectionEnd =
                    requireNotNull(session.pointB) {
                        "Pick point B first."
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
                )
            }

            RouteModifyMode.TRIM_START_TO_HERE -> {
                val startTarget =
                    requireNotNull(session.pointA) {
                        "Pick the new start first."
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
                    )
                }
            }

            RouteModifyMode.TRIM_END_FROM_HERE -> {
                val endTarget =
                    requireNotNull(session.pointB) {
                        "Pick the new end first."
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
                )
        }

    suspend fun previewModification(session: RouteToolSession): RouteToolModifyPreview {
        require(session.options.toolKind == RouteToolKind.MODIFY) {
            "Only GPX modify actions are supported here."
        }
        require(session.options.modifyMode.previewBeforeSaving) {
            "Preview is only available for reshape and endpoint edits."
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
            "Only GPX create actions are supported here."
        }
        require(
            session.options.createMode == RouteCreateMode.MULTI_POINT_CHAIN ||
                session.options.createMode == RouteCreateMode.LOOP_AROUND_HERE,
        ) {
            "Preview is only available for multi-point and loop creation."
        }

        val route =
            when (session.options.createMode) {
                RouteCreateMode.MULTI_POINT_CHAIN -> {
                    val plannerRequest = session.toRoutePlannerRequest(currentLocation)
                    routePlanner.createRoute(plannerRequest)
                }

                RouteCreateMode.LOOP_AROUND_HERE -> {
                    createLoopRoute(
                        routePlanner = routePlanner,
                        elevationFilterConfig = elevationFilterConfig,
                        etaModelConfig = etaModelConfig,
                        session = session,
                        currentLocation = currentLocation,
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
            "Only GPX create actions are supported here."
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
                    )
                val route = routePlanner.createRoute(plannerRequest)
                val extensionOutput =
                    buildRouteToolExtensionOutput(
                        sourceFileName = source.file.name,
                        sourceTitle = source.fileState.displayTitle,
                        profile = source.profile,
                        routedPoints = route.points,
                    )
                val extensionBytes =
                    encodeTrackAsGpx(
                        title = extensionOutput.title,
                        points = extensionOutput.points,
                    )

                reportProgress(onProgress, "Saving GPX...")
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
                        ?: error("The extended GPX was not found after saving.")

                gpxRepository.setActiveGpxFiles(
                    (currentActive - source.file.absolutePath) + savedFile.absolutePath,
                )

                reportProgress(onProgress, "Preparing stats...")
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
                        val plannerRequest = session.toRoutePlannerRequest(currentLocation)
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
            successMessage = "GPX renamed",
        )
    }

    suspend fun renameGpxFileOnDisk(
        filePath: String,
        newName: String,
    ): File {
        val sourceFile = File(filePath)
        require(sourceFile.exists()) { "The GPX could not be found." }

        val normalizedTitle =
            newName
                .trim()
                .replace(Regex("\\s+"), " ")
        require(normalizedTitle.isNotBlank()) { "Enter a GPX name first." }

        val parsed = parseGpxData(sourceFile)
        require(parsed.points.isNotEmpty()) { "The GPX does not contain any points." }

        val targetFileName = buildRenamedGpxFileName(normalizedTitle)
        val targetMatchesSource = targetFileName.equals(sourceFile.name, ignoreCase = true)
        if (!targetMatchesSource && gpxRepository.fileExists(targetFileName)) {
            error("A GPX with that name already exists.")
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
                ?: error("The renamed GPX was not found after saving.")

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
            "Activate exactly one GPX before using this tool."
        }

        val sourceFileState = activeFiles.single()
        val sourceFile = File(sourceFileState.path)
        require(sourceFile.exists()) { "The active GPX could not be found on disk." }

        val parsed = parseGpxData(sourceFile)
        require(parsed.points.size >= 2) { "The active GPX does not contain enough points to edit." }

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
                "Select the route point first."
            }
        val shapingPoint =
            requireNotNull(session.destination) {
                "Pick the shaping point first."
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

        return fallbackPlan ?: error("Could not build the reshape route.")
    }

    private fun buildRouteToolSaveResult(
        savedFile: File,
        replacedCurrent: Boolean,
        successMessage: String? = null,
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
            successMessage = successMessage,
        )
    }

    private suspend fun saveCreatedRoute(
        plannedCreation: RouteToolPlannedCreation,
        currentActive: Set<String>,
        onProgress: (String) -> Unit,
    ): RouteToolSaveResult {
        reportProgress(onProgress, "Saving GPX...")
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
                ?: error("The generated GPX was not found after saving.")

        gpxRepository.setActiveGpxFiles(currentActive + savedFile.absolutePath)

        reportProgress(onProgress, "Preparing stats...")
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
