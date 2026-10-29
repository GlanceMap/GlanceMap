package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.Context
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.ui.WearInfoDialog
import androidx.wear.compose.material.Text as WearText

@Composable
fun LicensesScreen(onOpenGeneralSettings: () -> Unit) {
    val appVersionLabel = rememberAppVersionLabel()
    val listTokens =
        rememberSettingsListTokens(
            compactTop = 24.dp,
            standardTop = 28.dp,
            expandedTop = 32.dp,
            compactBottom = 56.dp,
            standardBottom = 60.dp,
            expandedBottom = 68.dp,
        )
    var selectedDocument by remember { mutableStateOf<LicenseDocument?>(null) }

    WearSettingsListScreen(listTokens = listTokens, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            GeneralSettingsShortcutChip(onClick = onOpenGeneralSettings)
        }
        item {
            Text(
                text = stringResource(R.string.settings_credits_legal),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text =
                    stringResource(
                        R.string.settings_thanks_to_openandromaps_elevate_openhiking_tiramisu_hike_ride_sight_op,
                    ),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = appVersionLabel,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        items(LICENSE_DOCUMENTS) { document ->
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = {
                    WearText(
                        text = stringResource(document.labelRes),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                secondaryLabel = {
                    WearText(
                        text = stringResource(document.secondaryLabelRes),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                onClick = { selectedDocument = document },
            )
        }
        item {
            Spacer(modifier = Modifier.height(72.dp))
        }
    }

    selectedDocument?.let { document ->
        LicenseDocumentDialog(
            document = document,
            onDismiss = { selectedDocument = null },
        )
    }
}

private data class LicenseDocument(
    @StringRes val labelRes: Int,
    @StringRes val secondaryLabelRes: Int,
    @StringRes val titleRes: Int,
    @RawRes val bodyRes: Int,
)

private val LICENSE_DOCUMENTS =
    listOf(
        LicenseDocument(
            labelRes = R.string.legal_privacy_policy,
            secondaryLabelRes = R.string.legal_data_access_sharing_and_retention,
            titleRes = R.string.legal_privacy_policy,
            bodyRes = R.raw.legal_privacy_policy,
        ),
        LicenseDocument(
            labelRes = R.string.legal_safety_limits,
            secondaryLabelRes = R.string.legal_map_theme_errors_and_personal_responsibility,
            titleRes = R.string.legal_safety_limitations,
            bodyRes = R.raw.legal_safety_and_limitations,
        ),
        LicenseDocument(
            labelRes = R.string.legal_credits_thanks,
            secondaryLabelRes = R.string.legal_main_contributors_and_projects,
            titleRes = R.string.legal_credits_thanks,
            bodyRes = R.raw.legal_credits_and_thanks,
        ),
        LicenseDocument(
            labelRes = R.string.legal_ai_acknowledgment,
            secondaryLabelRes = R.string.legal_human_creators_and_transparency,
            titleRes = R.string.legal_ai_creator_acknowledgment,
            bodyRes = R.raw.legal_ai_acknowledgement,
        ),
        LicenseDocument(
            labelRes = R.string.legal_companion_sources,
            secondaryLabelRes = R.string.legal_map_gpx_and_refuge_websites,
            titleRes = R.string.legal_companion_external_sources,
            bodyRes = R.raw.legal_companion_external_sources,
        ),
        LicenseDocument(
            labelRes = R.string.legal_compliance_status,
            secondaryLabelRes = R.string.legal_release_checklist_and_pending_items,
            titleRes = R.string.legal_compliance_status,
            bodyRes = R.raw.legal_compliance_status,
        ),
        LicenseDocument(
            labelRes = R.string.legal_open_source_notices,
            secondaryLabelRes = R.string.legal_libraries_and_oss_licenses,
            titleRes = R.string.legal_open_source_notices,
            bodyRes = R.raw.legal_third_party_notices,
        ),
        LicenseDocument(
            labelRes = R.string.legal_openhiking_theme,
            secondaryLabelRes = R.string.legal_bundled_hiking_theme_details,
            titleRes = R.string.legal_openhiking_theme,
            bodyRes = R.raw.legal_openhiking_theme,
        ),
        LicenseDocument(
            labelRes = R.string.legal_french_kiss_theme,
            secondaryLabelRes = R.string.legal_bundled_ign_style_theme_details,
            titleRes = R.string.legal_french_kiss_theme,
            bodyRes = R.raw.legal_french_kiss_theme,
        ),
        LicenseDocument(
            labelRes = R.string.legal_tiramisu_theme,
            secondaryLabelRes = R.string.legal_bundled_cycle_hike_theme_details,
            titleRes = R.string.legal_tiramisu_theme,
            bodyRes = R.raw.legal_tiramisu_theme,
        ),
        LicenseDocument(
            labelRes = R.string.legal_hike_ride_sight,
            secondaryLabelRes = R.string.legal_bundled_overlay_rich_theme_details,
            titleRes = R.string.legal_hike_ride_sight_theme,
            bodyRes = R.raw.legal_hike_ride_sight_theme,
        ),
        LicenseDocument(
            labelRes = R.string.legal_voluntary_theme,
            secondaryLabelRes = R.string.legal_bundled_os_inspired_theme_details,
            titleRes = R.string.legal_voluntary_theme,
            bodyRes = R.raw.legal_voluntary_theme,
        ),
        LicenseDocument(
            labelRes = R.string.legal_data_asset_attribution,
            secondaryLabelRes = R.string.legal_osm_elevate_bundled_themes_dem_icons,
            titleRes = R.string.legal_data_asset_attribution,
            bodyRes = R.raw.legal_data_and_asset_attribution,
        ),
        LicenseDocument(
            labelRes = R.string.legal_service_terms_api_usage,
            secondaryLabelRes = R.string.legal_provider_terms_and_usage_limits,
            titleRes = R.string.legal_service_terms_api_usage,
            bodyRes = R.raw.legal_service_terms_and_api_usage,
        ),
    )

@Composable
private fun LicenseDocumentDialog(
    document: LicenseDocument,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val documentText =
        remember(document.bodyRes, androidx.compose.ui.platform.LocalConfiguration.current) {
            loadTextResource(context, document.bodyRes)
        }

    WearInfoDialog(
        visible = true,
        title = stringResource(document.titleRes),
        onDismiss = onDismiss,
    ) {
        item {
            Text(
                text = documentText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun rememberAppVersionLabel(): String {
    val context = LocalContext.current
    return remember(context) {
        buildAppVersionLabel(context)
    }
}

@Suppress("DEPRECATION")
private fun buildAppVersionLabel(context: Context): String =
    runCatching {
        val packageInfo =
            context.packageManager.getPackageInfo(
                context.packageName,
                0,
            )
        val versionName = packageInfo.versionName ?: context.resources.getString(R.string.settings_unknown)
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        context.resources.getString(R.string.settings_version, versionName, versionCode)
    }.getOrElse {
        context.resources.getString(R.string.settings_version_unknown)
    }

private fun loadTextResource(
    context: Context,
    @RawRes bodyRes: Int,
): String =
    runCatching {
        context.resources
            .openRawResource(bodyRes)
            .bufferedReader()
            .use { it.readText() }
    }.getOrElse {
        context.getString(R.string.legal_unable_to_load, context.resources.getResourceEntryName(bodyRes))
    }
