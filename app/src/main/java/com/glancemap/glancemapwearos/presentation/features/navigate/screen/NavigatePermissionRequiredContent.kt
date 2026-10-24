package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.ui.WearVerticalScrollIndicator

@Composable
internal fun NavigatePermissionRequiredContent(
    sizing: NavigateContentSizing,
    onPermissionLaunch: () -> Unit,
) {
    val permissionScrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxHeight)
                        .verticalScroll(permissionScrollState)
                        .padding(
                            start = sizing.permissionContentPadding,
                            top = sizing.permissionScrollTopPadding,
                            end = sizing.permissionContentPadding,
                            bottom = sizing.permissionScrollBottomPadding,
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text(
                    stringResource(R.string.navigate_location_permission_required_for_this_screen),
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onPermissionLaunch,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = sizing.permissionButtonMinHeight),
                ) {
                    Text(
                        stringResource(R.string.navigate_grant_permission),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(1.dp))
            }
        }
        WearVerticalScrollIndicator(
            scrollState = permissionScrollState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp),
        )
    }
}
