package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.Context
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
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
                text = "Credits & Legal",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "Thanks to OpenAndroMaps, Elevate, OpenHiking, Tiramisu, Hike, Ride & Sight, OpenStreetMap, Refuges.info, Overpass, Mapsforge and BRouter.",
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
                        text = document.label,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                secondaryLabel = {
                    WearText(
                        text = document.secondaryLabel,
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
    val label: String,
    val secondaryLabel: String,
    val title: String,
    val assetPath: String,
)

private val LICENSE_DOCUMENTS =
    listOf(
        LicenseDocument(
            label = "Privacy Policy",
            secondaryLabel = "Data access, sharing and retention",
            title = "Privacy Policy",
            assetPath = "licenses/PRIVACY_POLICY.md",
        ),
        LicenseDocument(
            label = "Safety & Limits",
            secondaryLabel = "Map/theme errors and personal responsibility",
            title = "Safety & Limitations",
            assetPath = "licenses/SAFETY_AND_LIMITATIONS.md",
        ),
        LicenseDocument(
            label = "Credits & Thanks",
            secondaryLabel = "Main contributors and projects",
            title = "Credits & Thanks",
            assetPath = "licenses/CREDITS_AND_THANKS.md",
        ),
        LicenseDocument(
            label = "AI Acknowledgment",
            secondaryLabel = "Human creators and transparency",
            title = "AI & Creator Acknowledgment",
            assetPath = "licenses/AI_ACKNOWLEDGEMENT.md",
        ),
        LicenseDocument(
            label = "Companion Sources",
            secondaryLabel = "Map, GPX and refuge websites",
            title = "Companion External Sources",
            assetPath = "licenses/COMPANION_EXTERNAL_SOURCES.md",
        ),
        LicenseDocument(
            label = "Compliance Status",
            secondaryLabel = "Release checklist and pending items",
            title = "Compliance Status",
            assetPath = "licenses/COMPLIANCE_STATUS.md",
        ),
        LicenseDocument(
            label = "Open Source Notices",
            secondaryLabel = "Libraries and OSS licenses",
            title = "Open Source Notices",
            assetPath = "licenses/THIRD_PARTY_NOTICES.md",
        ),
        LicenseDocument(
            label = "OpenHiking Theme",
            secondaryLabel = "Bundled hiking theme details",
            title = "OpenHiking Theme",
            assetPath = "licenses/OPENHIKING_THEME.md",
        ),
        LicenseDocument(
            label = "French Kiss Theme",
            secondaryLabel = "Bundled IGN-style theme details",
            title = "French Kiss Theme",
            assetPath = "licenses/FRENCH_KISS_THEME.md",
        ),
        LicenseDocument(
            label = "Tiramisu Theme",
            secondaryLabel = "Bundled cycle/hike theme details",
            title = "Tiramisu Theme",
            assetPath = "licenses/TIRAMISU_THEME.md",
        ),
        LicenseDocument(
            label = "Hike, Ride & Sight",
            secondaryLabel = "Bundled overlay-rich theme details",
            title = "Hike, Ride & Sight Theme",
            assetPath = "licenses/HIKE_RIDE_SIGHT_THEME.md",
        ),
        LicenseDocument(
            label = "Voluntary Theme",
            secondaryLabel = "Bundled OS-inspired theme details",
            title = "Voluntary Theme",
            assetPath = "licenses/VOLUNTARY_THEME.md",
        ),
        LicenseDocument(
            label = "Data & Asset Attribution",
            secondaryLabel = "OSM, Elevate, bundled themes, DEM, icons",
            title = "Data & Asset Attribution",
            assetPath = "licenses/DATA_AND_ASSET_ATTRIBUTION.md",
        ),
        LicenseDocument(
            label = "Service Terms & API Usage",
            secondaryLabel = "Provider terms and usage limits",
            title = "Service Terms & API Usage",
            assetPath = "licenses/SERVICE_TERMS_AND_API_USAGE.md",
        ),
    )

@Composable
private fun LicenseDocumentDialog(
    document: LicenseDocument,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val documentText =
        remember(document.assetPath) {
            loadTextAsset(context, document.assetPath)
        }

    WearInfoDialog(
        visible = true,
        title = document.title,
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
        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        "Version $versionName ($versionCode)"
    }.getOrElse {
        "Version unknown"
    }

private fun loadTextAsset(
    context: Context,
    assetPath: String,
): String =
    runCatching {
        context.assets
            .open(assetPath)
            .bufferedReader()
            .use { it.readText() }
    }.getOrElse {
        "Unable to load: $assetPath"
    }
