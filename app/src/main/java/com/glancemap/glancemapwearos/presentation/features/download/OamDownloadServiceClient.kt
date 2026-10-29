package com.glancemap.glancemapwearos.presentation.features.download

import android.content.Context
import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.maps.DemSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OamOwnedDownloadStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETE,
    CANCELED,
    FAILED,
}

data class OamOwnedDownloadState(
    val status: OamOwnedDownloadStatus = OamOwnedDownloadStatus.IDLE,
    val areaIds: List<String> = emptyList(),
    val selection: OamDownloadSelection? = null,
    val areaCount: Int = 0,
    val completedAreaCount: Int = 0,
    val phase: String? = null,
    val detail: String? = null,
    val bytesDone: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
)

internal enum class OamPersistedDownloadStatus {
    RUNNING,
    PAUSED,
}

internal data class OamPersistedDownloadPlan(
    val areaIds: List<String>,
    val selection: OamDownloadSelection,
    val nextAreaIndex: Int = 0,
    val status: OamPersistedDownloadStatus = OamPersistedDownloadStatus.RUNNING,
)

internal object OamPersistedDownloadPlanCodec {
    private const val VERSION = "1"
    private const val SEPARATOR = "|"
    private const val AREA_SEPARATOR = ","

    fun encode(plan: OamPersistedDownloadPlan): String =
        listOf(
            VERSION,
            plan.status.name,
            plan.nextAreaIndex.coerceAtLeast(0).toString(),
            plan.selection.demSource.id,
            plan.selection.includeMap.asFlag(),
            plan.selection.includePoi.asFlag(),
            plan.selection.includeRouting.asFlag(),
            plan.selection.includeDem.asFlag(),
            plan.selection.includeRefugesInfo.asFlag(),
            plan.areaIds.joinToString(AREA_SEPARATOR),
        ).joinToString(SEPARATOR)

    @Suppress("ReturnCount")
    fun decode(value: String?): OamPersistedDownloadPlan? {
        val parts = value?.split(SEPARATOR) ?: return null
        if (parts.size != 10 || parts[0] != VERSION) return null
        val status = runCatching { OamPersistedDownloadStatus.valueOf(parts[1]) }.getOrNull() ?: return null
        val nextAreaIndex = parts[2].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val areaIds =
            parts[9]
                .split(AREA_SEPARATOR)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        if (areaIds.isEmpty()) return null
        return OamPersistedDownloadPlan(
            areaIds = areaIds,
            selection =
                OamDownloadSelection(
                    includeMap = parts[4].toFlag() ?: return null,
                    includePoi = parts[5].toFlag() ?: return null,
                    includeRouting = parts[6].toFlag() ?: return null,
                    includeDem = parts[7].toFlag() ?: return null,
                    demSource = DemSource.fromId(parts[3]),
                    includeRefugesInfo = parts[8].toFlag() ?: return null,
                ),
            nextAreaIndex = nextAreaIndex.coerceAtMost(areaIds.size),
            status = status,
        )
    }

    private fun Boolean.asFlag(): String = if (this) "1" else "0"

    private fun String.toFlag(): Boolean? =
        when (this) {
            "1" -> true
            "0" -> false
            else -> null
        }
}

internal class OamDownloadOperationStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): OamPersistedDownloadPlan? = OamPersistedDownloadPlanCodec.decode(prefs.getString(KEY_PLAN, null))

    fun save(plan: OamPersistedDownloadPlan) {
        prefs.edit().putString(KEY_PLAN, OamPersistedDownloadPlanCodec.encode(plan)).commit()
    }

    fun clear() {
        prefs.edit().remove(KEY_PLAN).commit()
    }

    private companion object {
        private const val PREFS_NAME = "oam_active_download"
        private const val KEY_PLAN = "plan"
    }
}

private object OamOwnedDownloadRuntime {
    private val mutableState = MutableStateFlow(OamOwnedDownloadState())
    val state: StateFlow<OamOwnedDownloadState> = mutableState.asStateFlow()

    fun initialize(
        plan: OamPersistedDownloadPlan?,
        resources: Resources,
    ) {
        if (mutableState.value.status != OamOwnedDownloadStatus.IDLE || plan == null) return
        mutableState.value = plan.toRuntimeState(resources = resources)
    }

    fun publish(state: OamOwnedDownloadState) {
        mutableState.value = state
    }
}

class OamDownloadServiceClient(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val store = OamDownloadOperationStore(appContext)

    val state: StateFlow<OamOwnedDownloadState> = OamOwnedDownloadRuntime.state

    init {
        OamOwnedDownloadRuntime.initialize(store.load(), resources = appContext.resources)
    }

    fun start(
        areas: List<OamDownloadArea>,
        selection: OamDownloadSelection,
    ) {
        val areaIds = areas.map(OamDownloadArea::id).distinct()
        val plan = resumedOrNewDownloadPlan(store.load(), areaIds, selection)
        store.save(plan)
        publish(plan.toRuntimeState(resources = appContext.resources))
        OamDownloadForegroundService.startPersistedDownload(appContext)
    }

    fun pause() {
        OamDownloadForegroundService.requestPause(appContext)
    }

    fun cancel() {
        OamDownloadForegroundService.requestCancel(appContext)
    }

    fun resumeIfNeeded() {
        if (store.load()?.status == OamPersistedDownloadStatus.RUNNING) {
            OamDownloadForegroundService.startPersistedDownload(appContext)
        }
    }

    fun discardPausedPlan() {
        if (store.load()?.status != OamPersistedDownloadStatus.PAUSED) return
        store.clear()
        publish(OamOwnedDownloadState())
    }

    internal fun loadPlan(): OamPersistedDownloadPlan? = store.load()

    internal fun savePlan(plan: OamPersistedDownloadPlan) {
        store.save(plan)
    }

    internal fun clearPlan() {
        store.clear()
    }

    internal fun publish(state: OamOwnedDownloadState) {
        OamOwnedDownloadRuntime.publish(state)
    }
}

internal fun resumedOrNewDownloadPlan(
    persistedPlan: OamPersistedDownloadPlan?,
    areaIds: List<String>,
    selection: OamDownloadSelection,
): OamPersistedDownloadPlan =
    if (
        persistedPlan?.status == OamPersistedDownloadStatus.PAUSED &&
        persistedPlan.areaIds == areaIds &&
        persistedPlan.selection == selection
    ) {
        persistedPlan.copy(status = OamPersistedDownloadStatus.RUNNING)
    } else {
        OamPersistedDownloadPlan(
            areaIds = areaIds,
            selection = selection,
        )
    }

private fun OamPersistedDownloadPlan.toRuntimeState(resources: Resources): OamOwnedDownloadState =
    OamOwnedDownloadState(
        status =
            when (status) {
                OamPersistedDownloadStatus.RUNNING -> OamOwnedDownloadStatus.RUNNING
                OamPersistedDownloadStatus.PAUSED -> OamOwnedDownloadStatus.PAUSED
            },
        areaIds = areaIds,
        selection = selection,
        areaCount = areaIds.size,
        completedAreaCount = nextAreaIndex,
        phase = if (status == OamPersistedDownloadStatus.PAUSED) "PAUSED" else "STARTING",
        detail = resources.getQuantityString(R.plurals.download_runtime_area_count, areaIds.size, areaIds.size),
    )
