package com.glancemap.glancemapwearos.presentation.features.recording.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RecordingHealthRequestState {
    private val desired = MutableStateFlow<RecordingHealthDesiredSession?>(null)
    val requests = desired.asStateFlow()
    private val preparingSave = MutableStateFlow(false)
    val saving = preparingSave.asStateFlow()

    fun update(next: RecordingHealthDesiredSession?) {
        desired.value = next
        preparingSave.value = false
    }

    fun beginSave() {
        preparingSave.value = true
        desired.value = null
    }
}
