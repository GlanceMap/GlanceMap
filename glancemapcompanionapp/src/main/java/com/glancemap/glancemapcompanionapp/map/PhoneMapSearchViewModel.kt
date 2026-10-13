package com.glancemap.glancemapcompanionapp.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

internal class PhoneMapSearchViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = PhoneMapSearchRepository(application)
    val controller = PhoneMapSearchController(viewModelScope, repository::search)
}
