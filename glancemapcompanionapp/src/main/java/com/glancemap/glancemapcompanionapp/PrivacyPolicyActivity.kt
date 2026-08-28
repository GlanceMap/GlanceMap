package com.glancemap.glancemapcompanionapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.glancemap.glancemapcompanionapp.ui.theme.GlanceMapTheme
import com.glancemap.shared.transfer.TransferDataLayerContract

class PrivacyPolicyActivity : ComponentActivity() {
    companion object {
        fun creditsAndLegalIntent(context: Context): Intent = Intent(context, PrivacyPolicyActivity::class.java)

        fun privacyPolicyIntent(context: Context): Intent =
            documentIntent(
                context = context,
                document = COMPANION_CREDITS_AND_LEGAL_DOCUMENTS.first(),
            )

        fun creditsIntent(context: Context): Intent =
            documentIntent(
                context = context,
                document = COMPANION_CREDITS_AND_LEGAL_DOCUMENTS.first { it.assetPath == CREDITS_AND_THANKS_ASSET_PATH },
            )

        private fun documentIntent(
            context: Context,
            document: LegalDocument,
        ): Intent =
            Intent(context, PrivacyPolicyActivity::class.java).apply {
                putExtra(EXTRA_DOCUMENT_ASSET_PATH, document.assetPath)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val document = intent.toLegalDocumentOrNull()
        title = document?.let { getString(it.documentTitleResId) } ?: getString(R.string.settings_credits_legal_title)

        setContent {
            GlanceMapTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (document == null) {
                        CreditsAndLegalScreen(
                            onBack = ::finish,
                            onOpenDocument = { selected ->
                                startActivity(documentIntent(this, selected))
                            },
                        )
                    } else {
                        LegalDocumentScreen(
                            document = document,
                            onBack = ::finish,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditsAndLegalScreen(
    onBack: () -> Unit,
    onOpenDocument: (LegalDocument) -> Unit,
) {
    val versionLabel = rememberAppVersionLabel()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(
                onClick = onBack,
                colors = companionTonalIconButtonColors(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_content_description_back),
                )
            }
            Text(
                text = stringResource(R.string.settings_credits_legal_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CreditsAndLegalIntro(versionLabel = versionLabel)
            COMPANION_CREDITS_AND_LEGAL_DOCUMENTS.forEach { document ->
                OutlinedButton(
                    onClick = { onOpenDocument(document) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(document.buttonLabelResId),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(document.secondaryLabelResId),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun CreditsAndLegalIntro(versionLabel: String) {
    Text(
        text = versionLabel,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LegalDocumentScreen(
    document: LegalDocument,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val documentText =
        remember(document.assetPath) {
            loadAssetDocumentText(
                context = context,
                assetPath = document.assetPath,
            )
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(
                onClick = onBack,
                colors = companionTonalIconButtonColors(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_content_description_back),
                )
            }
            Text(
                text = stringResource(document.documentTitleResId),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(document.secondaryLabelResId),
                style = MaterialTheme.typography.bodyMedium,
            )
            SelectionContainer {
                Text(
                    text = documentText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (document.showPrivacyContact) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { openPrivacyContactEmail(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_privacy_contact_action))
                }
            }
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
        context.getString(R.string.settings_version, versionName, versionCode)
    }.getOrElse {
        context.getString(R.string.settings_version_unknown)
    }

private fun loadAssetDocumentText(
    context: Context,
    assetPath: String,
): String =
    runCatching {
        context.assets
            .open(assetPath)
            .bufferedReader()
            .use { it.readText() }
    }.getOrElse {
        context.getString(R.string.settings_legal_document_load_error)
    }

private fun openPrivacyContactEmail(context: Context) {
    val intent =
        Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("mailto:${TransferDataLayerContract.DIAGNOSTICS_SUPPORT_EMAIL}"),
        ).apply {
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_privacy_contact_email_subject))
        }

    runCatching {
        context.startActivity(intent)
    }.recoverCatching {
        throw ActivityNotFoundException("No email app available")
    }
}

@Composable
private fun companionTonalIconButtonColors() =
    IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

private data class LegalDocument(
    @StringRes val buttonLabelResId: Int,
    @StringRes val documentTitleResId: Int,
    @StringRes val secondaryLabelResId: Int,
    val assetPath: String,
    val showPrivacyContact: Boolean,
)

private fun Intent.toLegalDocumentOrNull(): LegalDocument? {
    val assetPath = getStringExtra(EXTRA_DOCUMENT_ASSET_PATH).orEmpty()
    if (assetPath.isBlank()) return null
    return COMPANION_CREDITS_AND_LEGAL_DOCUMENTS.firstOrNull { it.assetPath == assetPath }
}

private const val PRIVACY_POLICY_ASSET_PATH = "PRIVACY_POLICY.md"
private const val CREDITS_AND_THANKS_ASSET_PATH = "CREDITS_AND_THANKS.md"
private const val EXTRA_DOCUMENT_ASSET_PATH = "document_asset_path"
private const val SAFETY_AND_LIMITATIONS_ASSET_PATH = "SAFETY_AND_LIMITATIONS.md"
private const val AI_ACKNOWLEDGEMENT_ASSET_PATH = "AI_ACKNOWLEDGEMENT.md"
private const val COMPANION_EXTERNAL_SOURCES_ASSET_PATH = "COMPANION_EXTERNAL_SOURCES.md"
private const val COMPLIANCE_STATUS_ASSET_PATH = "COMPLIANCE_STATUS.md"
private const val THIRD_PARTY_NOTICES_ASSET_PATH = "THIRD_PARTY_NOTICES.md"
private const val OPENHIKING_THEME_ASSET_PATH = "OPENHIKING_THEME.md"
private const val FRENCH_KISS_THEME_ASSET_PATH = "FRENCH_KISS_THEME.md"
private const val TIRAMISU_THEME_ASSET_PATH = "TIRAMISU_THEME.md"
private const val HIKE_RIDE_SIGHT_THEME_ASSET_PATH = "HIKE_RIDE_SIGHT_THEME.md"
private const val VOLUNTARY_THEME_ASSET_PATH = "VOLUNTARY_THEME.md"
private const val DATA_AND_ASSET_ATTRIBUTION_ASSET_PATH = "DATA_AND_ASSET_ATTRIBUTION.md"
private const val SERVICE_TERMS_AND_API_USAGE_ASSET_PATH = "SERVICE_TERMS_AND_API_USAGE.md"

private val COMPANION_CREDITS_AND_LEGAL_DOCUMENTS =
    listOf(
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_privacy_policy_label,
            documentTitleResId = R.string.settings_legal_privacy_policy_label,
            secondaryLabelResId = R.string.settings_legal_privacy_policy_description,
            assetPath = PRIVACY_POLICY_ASSET_PATH,
            showPrivacyContact = true,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_safety_limits_label,
            documentTitleResId = R.string.settings_legal_safety_limitations_title,
            secondaryLabelResId = R.string.settings_legal_safety_limits_description,
            assetPath = SAFETY_AND_LIMITATIONS_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_credits_thanks_label,
            documentTitleResId = R.string.settings_legal_credits_thanks_label,
            secondaryLabelResId = R.string.settings_legal_credits_thanks_description,
            assetPath = CREDITS_AND_THANKS_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_ai_acknowledgment_label,
            documentTitleResId = R.string.settings_legal_ai_acknowledgment_title,
            secondaryLabelResId = R.string.settings_legal_ai_acknowledgment_description,
            assetPath = AI_ACKNOWLEDGEMENT_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_companion_sources_label,
            documentTitleResId = R.string.settings_legal_companion_sources_title,
            secondaryLabelResId = R.string.settings_legal_companion_sources_description,
            assetPath = COMPANION_EXTERNAL_SOURCES_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_compliance_status_label,
            documentTitleResId = R.string.settings_legal_compliance_status_label,
            secondaryLabelResId = R.string.settings_legal_compliance_status_description,
            assetPath = COMPLIANCE_STATUS_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_open_source_notices_label,
            documentTitleResId = R.string.settings_legal_open_source_notices_label,
            secondaryLabelResId = R.string.settings_legal_open_source_notices_description,
            assetPath = THIRD_PARTY_NOTICES_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_open_hiking_theme_label,
            documentTitleResId = R.string.settings_legal_open_hiking_theme_label,
            secondaryLabelResId = R.string.settings_legal_open_hiking_theme_description,
            assetPath = OPENHIKING_THEME_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_french_kiss_theme_label,
            documentTitleResId = R.string.settings_legal_french_kiss_theme_label,
            secondaryLabelResId = R.string.settings_legal_french_kiss_theme_description,
            assetPath = FRENCH_KISS_THEME_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_tiramisu_theme_label,
            documentTitleResId = R.string.settings_legal_tiramisu_theme_label,
            secondaryLabelResId = R.string.settings_legal_tiramisu_theme_description,
            assetPath = TIRAMISU_THEME_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_hike_ride_sight_label,
            documentTitleResId = R.string.settings_legal_hike_ride_sight_title,
            secondaryLabelResId = R.string.settings_legal_hike_ride_sight_description,
            assetPath = HIKE_RIDE_SIGHT_THEME_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_voluntary_theme_label,
            documentTitleResId = R.string.settings_legal_voluntary_theme_label,
            secondaryLabelResId = R.string.settings_legal_voluntary_theme_description,
            assetPath = VOLUNTARY_THEME_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_data_attribution_label,
            documentTitleResId = R.string.settings_legal_data_attribution_label,
            secondaryLabelResId = R.string.settings_legal_data_attribution_description,
            assetPath = DATA_AND_ASSET_ATTRIBUTION_ASSET_PATH,
            showPrivacyContact = false,
        ),
        LegalDocument(
            buttonLabelResId = R.string.settings_legal_service_terms_label,
            documentTitleResId = R.string.settings_legal_service_terms_label,
            secondaryLabelResId = R.string.settings_legal_service_terms_description,
            assetPath = SERVICE_TERMS_AND_API_USAGE_ASSET_PATH,
            showPrivacyContact = false,
        ),
    )
