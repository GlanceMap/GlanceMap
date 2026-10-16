package com.glancemap.glancemapcompanionapp.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
@Suppress("FunctionNaming") // Compose source settings entry point.
internal fun RouteSourcesPanel(
    settings: RouteSourceSettings,
    onSave: (RouteSourceSettings) -> Unit,
    onBack: () -> Unit,
) {
    var overpass by remember(settings.overpassUrl) { mutableStateOf(settings.overpassUrl) }
    var registry by remember(settings.geotrekRegistry) { mutableStateOf(settings.geotrekRegistry) }
    var invalid by remember { mutableStateOf(false) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.discovery_back_results)) }
        Text(stringResource(R.string.discovery_sources))
        RouteSourceToggles(settings) { provider, enabled ->
            val sources = if (enabled) settings.enabled + provider else settings.enabled - provider
            onSave(settings.copy(enabled = sources))
        }
        Text(stringResource(R.string.discovery_wanderer_limit))
        Text(stringResource(R.string.discovery_datatourisme_limit))
        OutlinedTextField(
            value = overpass,
            onValueChange = { overpass = it.take(2048) },
            label = { Text(stringResource(R.string.discovery_overpass_url)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.discovery_overpass_limit))
        Text(stringResource(R.string.discovery_geotrek_help))
        OutlinedTextField(
            value = registry,
            onValueChange = { registry = it.take(12000) },
            label = { Text(stringResource(R.string.discovery_geotrek_registry)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        if (invalid) Text(stringResource(R.string.discovery_invalid_settings))
        Button(onClick = {
            invalid =
                runCatching { onSave(settings.copy(overpassUrl = overpass, geotrekRegistry = registry)) }.isFailure
        }) { Text(stringResource(R.string.discovery_apply)) }
    }
}

@Composable
@Suppress("FunctionNaming") // Independent persistent source switches, shared by settings tests.
internal fun RouteSourceToggles(settings: RouteSourceSettings, onChanged: (TrailProvider, Boolean) -> Unit) {
    TrailProvider.entries.forEach { provider ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(provider.label, Modifier.weight(1f))
            Switch(
                checked = provider in settings.enabled,
                onCheckedChange = { onChanged(provider, it) },
                modifier = Modifier.semantics { contentDescription = provider.label },
            )
        }
    }
}
