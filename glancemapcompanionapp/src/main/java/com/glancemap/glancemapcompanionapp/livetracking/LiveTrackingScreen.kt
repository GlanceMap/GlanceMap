@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MaxLineLength",
    "ReturnCount",
    "TooManyFunctions",
)

package com.glancemap.glancemapcompanionapp.livetracking

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.companionAdaptiveSpec
import com.glancemap.glancemapcompanionapp.resolveUriDisplayName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val START_AFTER_UPLOAD_DELAY_MS = 1500L

private enum class LiveTrackingPage {
    MAIN,
    SETUP,
}

private enum class EmailPickerTarget {
    NOTIFICATION,
    ALERT,
}

private enum class ExternalSettingsStartStep {
    BACKGROUND_LOCATION,
    BATTERY_OPTIMIZATION,
}

internal enum class RecordedTrackDownloadTarget {
    USER,
    GROUP,
}

@Composable
fun LiveTrackingScreen(
    onBack: () -> Unit,
    onOpenQuickGuide: () -> Unit,
    lastTransferGpxUri: Uri? = null,
    lastTransferGpxName: String = "",
) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    val coroutineScope = rememberCoroutineScope()
    val sessionState by LiveTrackingSessionStore.state.collectAsState()
    val savedSettings = remember(context) { LiveTrackingPreferences.load(context) }
    val savedDraft = remember(context) { LiveTrackingPreferences.loadDraft(context) }
    var page by remember { mutableStateOf(LiveTrackingPage.MAIN) }
    var group by remember { mutableStateOf(savedSettings.group) }
    var participantPassword by remember { mutableStateOf(savedSettings.participantPassword) }
    var followerPassword by remember { mutableStateOf(savedSettings.followerPassword) }
    var userName by remember { mutableStateOf(savedSettings.userName) }
    var notificationEmailInput by remember { mutableStateOf("") }
    var notificationEmailAddresses by remember {
        mutableStateOf(savedSettings.notificationEmailAddresses)
    }
    var alertRecipientInput by remember { mutableStateOf("") }
    var alertRecipients by remember { mutableStateOf(savedSettings.alertRecipients) }
    var isValidatingAlertRecipient by remember { mutableStateOf(false) }
    var alertRecipientStatusMessage by remember { mutableStateOf<String?>(null) }
    var alertRecipientWarning by remember { mutableStateOf<String?>(null) }
    var unsupportedSmsRecipientsOnStart by remember { mutableStateOf<List<String>?>(null) }
    var stuckAlarmMinutes by remember { mutableStateOf(savedSettings.stuckAlarmMinutes) }
    var comments by remember { mutableStateOf(savedDraft.comments) }
    var trackingEndpoint by remember { mutableStateOf(ArkluzTrackingEndpoint.defaultEndpoint) }
    var updateIntervalSeconds by remember { mutableStateOf(savedSettings.updateIntervalSeconds) }
    var selectedGpxUri by remember {
        mutableStateOf(savedDraft.gpxUri.takeIf(String::isNotBlank)?.let(Uri::parse))
    }
    var selectedGpxName by remember { mutableStateOf(savedDraft.gpxName) }
    var hasLocationPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    var hasBackgroundLocationPermission by remember {
        mutableStateOf(hasBackgroundLocationPermission(context))
    }
    var hasNotificationPermission by remember { mutableStateOf(hasLiveTrackingNotificationPermission(context)) }
    var isStartWaitingForPermissionResult by remember { mutableStateOf(false) }
    var continueStartAfterPermissionResult by remember { mutableStateOf(false) }
    var continueStartAfterBackgroundLocationResult by remember { mutableStateOf(false) }
    var pendingExternalSettingsStartStep by remember {
        mutableStateOf<ExternalSettingsStartStep?>(null)
    }
    var continueStartAfterExternalSettingsResult by remember {
        mutableStateOf<ExternalSettingsStartStep?>(null)
    }
    var showLocationDisclosureDialog by remember { mutableStateOf(false) }
    var showNotificationPermissionWarningDialog by remember { mutableStateOf(false) }
    var showBackgroundLocationDialog by remember { mutableStateOf(false) }
    var showBatteryOptimizationDialog by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var sendStatusMessage by remember { mutableStateOf<String?>(null) }
    var loginJoinStatusMessage by remember { mutableStateOf<String?>(null) }
    var isLoginJoinLoading by remember { mutableStateOf(false) }
    var saveSettingsStatusMessage by remember { mutableStateOf<String?>(null) }
    var isSavingSettings by remember { mutableStateOf(false) }
    var settingsSnapshot by remember { mutableStateOf<SavedLiveTrackingSettings?>(null) }
    var showUnsavedSettingsDialog by remember { mutableStateOf(false) }
    var showChangeGroupDialog by remember { mutableStateOf(false) }
    var pendingRegistrationGroup by remember { mutableStateOf<String?>(null) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var createGroupPasswordConfirmation by remember { mutableStateOf("") }
    var isSendingPlan by remember { mutableStateOf(false) }
    var isStartingSession by remember { mutableStateOf(false) }
    var deleteTracksStatusMessage by remember { mutableStateOf<String?>(null) }
    var isDeletingTracks by remember { mutableStateOf(false) }
    var recordedTrackDownloadStatusMessage by remember { mutableStateOf<String?>(null) }
    var isDownloadingRecordedTrack by remember { mutableStateOf(false) }
    var pendingRecordedTrackDownload by remember { mutableStateOf<ArkluzRecordedGpxDownload?>(null) }
    var planSent by remember { mutableStateOf(false) }
    var emailPickerTarget by remember { mutableStateOf<EmailPickerTarget?>(null) }
    var showUseLastTransferGpxDialog by remember { mutableStateOf(false) }

    fun savePlannedDraft(
        draftComments: String = comments,
        draftGpxUri: Uri? = selectedGpxUri,
        draftGpxName: String = selectedGpxName,
    ) {
        LiveTrackingPreferences.saveDraft(
            context = context,
            draft =
                SavedLiveTrackingDraft(
                    comments = draftComments,
                    gpxUri = draftGpxUri?.toString().orEmpty(),
                    gpxName = draftGpxName,
                ),
        )
    }

    fun selectPlannedGpx(
        uri: Uri,
        name: String,
    ) {
        val cleanName = name.ifBlank { resolveUriDisplayName(context, uri).ifBlank { "Selected GPX" } }
        selectedGpxUri = uri
        selectedGpxName = cleanName
        planSent = false
        sendStatusMessage = null
        savePlannedDraft(
            draftGpxUri = uri,
            draftGpxName = cleanName,
        )
    }

    fun clearPlannedGpx() {
        selectedGpxUri = null
        selectedGpxName = ""
        planSent = false
        sendStatusMessage = null
        savePlannedDraft(
            draftGpxUri = null,
            draftGpxName = "",
        )
    }

    LaunchedEffect(sessionState.status) {
        if (sessionState.status == "Stopped") {
            comments = ""
            selectedGpxUri = null
            selectedGpxName = ""
            planSent = false
            sendStatusMessage = null
            LiveTrackingPreferences.clearDraft(context)
        }
    }

    val gpxPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            selectPlannedGpx(
                uri = uri,
                name = resolveUriDisplayName(context, uri),
            )
        }
    val recordedTrackSavePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/gpx+xml"),
        ) { uri ->
            val download = pendingRecordedTrackDownload
            pendingRecordedTrackDownload = null
            if (uri == null || download == null) {
                download?.delete()
                isDownloadingRecordedTrack = false
                if (download != null) recordedTrackDownloadStatusMessage = "Download cancelled"
                return@rememberLauncherForActivityResult
            }

            recordedTrackDownloadStatusMessage = "Saving recorded GPX"
            coroutineScope.launch {
                runCatching {
                    ArkluzLiveTrackingClient(context).saveRecordedGpx(
                        download = download,
                        outputUri = uri,
                    )
                }.onSuccess { result ->
                    recordedTrackDownloadStatusMessage = result.message
                }.onFailure { error ->
                    recordedTrackDownloadStatusMessage =
                        "Download failed: ${error.toArkluzFailureDetail()}"
                }
                isDownloadingRecordedTrack = false
            }
        }

    fun downloadRecordedTrack(target: RecordedTrackDownloadTarget) {
        isDownloadingRecordedTrack = true
        recordedTrackDownloadStatusMessage = "Downloading recorded GPX"
        val downloadSettings =
            LiveTrackingSettings(
                trackingUrl = trackingEndpoint.url,
                updateIntervalSeconds = updateIntervalSeconds,
                group = group,
                participantPassword = participantPassword,
                followerPassword = followerPassword,
                userName = userName,
                notificationEmails = "",
                alertEmails = "",
                stuckAlarmMinutes = stuckAlarmMinutes,
                comments = "",
                gpxUri = null,
                gpxName = "",
            )
        coroutineScope.launch {
            runCatching {
                ArkluzLiveTrackingClient(context).downloadRecordedGpx(
                    settings = downloadSettings,
                    userOnly = target == RecordedTrackDownloadTarget.USER,
                    fallbackFileName =
                        recordedTrackDownloadFilename(
                            group = group,
                            userName = userName,
                            target = target,
                        ),
                )
            }.onSuccess { download ->
                pendingRecordedTrackDownload?.delete()
                pendingRecordedTrackDownload = download
                recordedTrackDownloadStatusMessage = "Choose where to save the recorded GPX"
                recordedTrackSavePicker.launch(download.suggestedFileName)
            }.onFailure { error ->
                recordedTrackDownloadStatusMessage =
                    "Download failed: ${error.toArkluzFailureDetail()}"
                isDownloadingRecordedTrack = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingRecordedTrackDownload?.delete()
        }
    }

    fun addAlertRecipient(recipient: AlertRecipient) {
        if (alertRecipients.any { it.equals(recipient.value, ignoreCase = true) }) {
            alertRecipientStatusMessage = "Recipient already added"
            return
        }
        if (recipient.type == AlertRecipientType.EMAIL) {
            alertRecipients = alertRecipients + recipient.value
            alertRecipientInput = ""
            alertRecipientStatusMessage = null
            return
        }

        isValidatingAlertRecipient = true
        alertRecipientStatusMessage = null
        coroutineScope.launch {
            runCatching {
                ArkluzLiveTrackingClient(context).checkSmsSupport(
                    trackingUrl = trackingEndpoint.url,
                    phoneNumber = recipient.value,
                )
            }.onSuccess { support ->
                if (support == ArkluzSmsSupport.SUPPORTED) {
                    alertRecipients = alertRecipients + recipient.value
                    alertRecipientInput = ""
                    alertRecipientStatusMessage = null
                } else {
                    alertRecipientStatusMessage =
                        "SMS alerts are not supported for ${recipient.value}."
                }
            }.onFailure { error ->
                alertRecipientStatusMessage =
                    "Could not check SMS support: ${error.toArkluzFailureDetail()}"
            }
            isValidatingAlertRecipient = false
        }
    }

    val contactEmailPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val target = emailPickerTarget
            emailPickerTarget = null
            if (result.resultCode != Activity.RESULT_OK || target == null) return@rememberLauncherForActivityResult
            val email =
                result.data
                    ?.data
                    ?.let { uri -> resolveSelectedContactEmail(context, uri) }
            if (email.isNullOrBlank()) {
                saveSettingsStatusMessage = "No email address selected"
                return@rememberLauncherForActivityResult
            }
            when (target) {
                EmailPickerTarget.NOTIFICATION -> {
                    if (notificationEmailAddresses.any { it.equals(email, ignoreCase = true) }) {
                        saveSettingsStatusMessage = "Email already added"
                    } else {
                        notificationEmailAddresses = notificationEmailAddresses + email
                        notificationEmailInput = ""
                        saveSettingsStatusMessage = null
                    }
                }

                EmailPickerTarget.ALERT -> {
                    addAlertRecipient(AlertRecipient(email, AlertRecipientType.EMAIL))
                }
            }
        }
    val contactPhonePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val phoneNumber =
                result.data
                    ?.data
                    ?.let { uri -> resolveSelectedContactPhone(context, uri) }
            if (phoneNumber.isNullOrBlank()) {
                alertRecipientStatusMessage =
                    "Choose a phone number with a country code starting with +"
                return@rememberLauncherForActivityResult
            }
            addAlertRecipient(AlertRecipient(phoneNumber, AlertRecipientType.SMS))
        }
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            val locationGranted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    hasLocationPermission(context)
            val notificationGranted =
                result[Manifest.permission.POST_NOTIFICATIONS] == true ||
                    hasLiveTrackingNotificationPermission(context)
            hasLocationPermission = locationGranted
            hasNotificationPermission = notificationGranted
            if (isStartWaitingForPermissionResult) {
                isStartWaitingForPermissionResult = false
                when (liveTrackingPermissionOutcome(locationGranted, notificationGranted)) {
                    LiveTrackingPermissionOutcome.LOCATION_REQUIRED -> {
                        isStartingSession = false
                        validationMessage = "Location permission is required to start live tracking."
                    }

                    LiveTrackingPermissionOutcome.NOTIFICATION_WARNING -> {
                        isStartingSession = false
                        showNotificationPermissionWarningDialog = true
                    }

                    LiveTrackingPermissionOutcome.CONTINUE -> continueStartAfterPermissionResult = true
                }
            }
        }
    val backgroundLocationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasBackgroundLocationPermission = granted || hasBackgroundLocationPermission(context)
            continueStartAfterBackgroundLocationResult = true
        }
    val externalSettingsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) {
            val step =
                pendingExternalSettingsStartStep
                    ?: return@rememberLauncherForActivityResult
            pendingExternalSettingsStartStep = null
            continueStartAfterExternalSettingsResult = step
        }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val adaptive =
            remember(maxWidth, maxHeight, fontScale) {
                companionAdaptiveSpec(
                    windowWidth = maxWidth,
                    windowHeight = maxHeight,
                    fontScale = fontScale,
                )
            }
        val compactLayout = adaptive.useCompactPageLayout
        val contentPadding = if (compactLayout) 6.dp else 12.dp
        val contentSpacing = if (compactLayout) 6.dp else 10.dp
        val mainScrollState = rememberScrollState()
        val setupScrollState = rememberScrollState()
        val settings =
            remember(
                group,
                participantPassword,
                followerPassword,
                userName,
                notificationEmailAddresses,
                notificationEmailInput,
                alertRecipients,
                alertRecipientInput,
                stuckAlarmMinutes,
                comments,
                selectedGpxUri,
                selectedGpxName,
                planSent,
                trackingEndpoint,
                updateIntervalSeconds,
            ) {
                LiveTrackingSettings(
                    trackingUrl = trackingEndpoint.url,
                    updateIntervalSeconds = updateIntervalSeconds,
                    group = group,
                    participantPassword = participantPassword,
                    followerPassword = followerPassword,
                    userName = userName,
                    notificationEmails =
                        emailAddressesForRequest(
                            addresses = notificationEmailAddresses,
                            pendingInput = notificationEmailInput,
                        ),
                    alertEmails =
                        alertRecipientsForRequest(
                            recipients = alertRecipients,
                            pendingInput = alertRecipientInput,
                        ),
                    stuckAlarmMinutes = stuckAlarmMinutes,
                    comments = if (planSent) "" else comments,
                    gpxUri = if (planSent) null else selectedGpxUri,
                    gpxName = selectedGpxName,
                )
            }
        val groupTrackUrl =
            remember(group, followerPassword, userName, trackingEndpoint) {
                arkluzTrackUrl(
                    baseUrl = trackingEndpoint.url,
                    group = group,
                    followerPassword = followerPassword,
                    user = null,
                    selectedUser = userName,
                )
            }
        val userTrackUrl =
            remember(group, followerPassword, userName, trackingEndpoint) {
                arkluzTrackUrl(
                    baseUrl = trackingEndpoint.url,
                    group = group,
                    followerPassword = followerPassword,
                    user = userName,
                    selectedUser = null,
                )
            }
        val isConnected = followerPassword.isNotBlank()
        val hasPlanContent = selectedGpxUri != null || comments.isNotBlank()
        val showSendPlan = sessionState.isTracking
        val canSendPlan = showSendPlan && hasPlanContent && isConnected
        val canStart =
            group.isNotBlank() &&
                participantPassword.isNotBlank() &&
                followerPassword.isNotBlank() &&
                userName.isNotBlank()

        fun currentSettingsSnapshot(
            committedNotificationEmails: List<String> = notificationEmailAddresses,
            committedAlertRecipients: List<String> = alertRecipients,
            committedFollowerPassword: String = followerPassword,
        ): SavedLiveTrackingSettings =
            SavedLiveTrackingSettings(
                group = group.trim(),
                participantPassword = participantPassword.trim(),
                followerPassword = committedFollowerPassword.trim(),
                userName = userName.trim(),
                notificationEmailAddresses = committedNotificationEmails,
                alertRecipients = committedAlertRecipients,
                stuckAlarmMinutes = stuckAlarmMinutes,
                updateIntervalSeconds = updateIntervalSeconds,
            )

        fun applySettingsSnapshot(snapshot: SavedLiveTrackingSettings) {
            userName = snapshot.userName
            notificationEmailInput = ""
            notificationEmailAddresses = snapshot.notificationEmailAddresses
            alertRecipientInput = ""
            alertRecipients = snapshot.alertRecipients
            stuckAlarmMinutes = snapshot.stuckAlarmMinutes
            updateIntervalSeconds = snapshot.updateIntervalSeconds
            saveSettingsStatusMessage = null
        }

        fun restoreGroupProfile(groupName: String) {
            val profile = LiveTrackingPreferences.loadGroupSettings(context, groupName)
            profile?.let(::applySettingsSnapshot)
            val connectedSettings = currentSettingsSnapshot()
            LiveTrackingPreferences.save(context, connectedSettings)
            settingsSnapshot = connectedSettings
            saveSettingsStatusMessage = null
            page = LiveTrackingPage.SETUP
        }

        suspend fun unsupportedSmsRecipients(recipients: List<String>): List<String> {
            val client = ArkluzLiveTrackingClient(context)
            return smsAlertRecipients(recipients).filter { phoneNumber ->
                client.checkSmsSupport(
                    trackingUrl = trackingEndpoint.url,
                    phoneNumber = phoneNumber,
                ) == ArkluzSmsSupport.UNSUPPORTED
            }
        }

        fun unsupportedSmsRecipientMessage(phoneNumbers: List<String>): String = "SMS alerts are not supported for ${phoneNumbers.joinToString()}. Update or remove them."

        fun saveSettings(exitAfterSave: Boolean = false) {
            saveSettingsStatusMessage =
                validateStartSettings(
                    group = group,
                    participantPassword = participantPassword,
                    followerPassword = followerPassword,
                    userName = userName,
                    stuckAlarmMinutes = stuckAlarmMinutes,
                )
                    ?: validatePendingRecipientInputs(
                        notificationEmailInput = notificationEmailInput,
                        alertRecipientInput = alertRecipientInput,
                    )
            if (saveSettingsStatusMessage != null) return

            val committedNotificationEmails =
                resolvedEmailAddresses(notificationEmailAddresses, notificationEmailInput)
            val committedAlertRecipients =
                resolvedAlertRecipients(alertRecipients, alertRecipientInput)
            val settingsForSave =
                settings.copy(
                    notificationEmails = committedNotificationEmails.joinToString(","),
                    alertEmails = committedAlertRecipients.joinToString(","),
                )

            isSavingSettings = true
            saveSettingsStatusMessage = "Checking SMS alerts"
            coroutineScope.launch {
                runCatching { unsupportedSmsRecipients(committedAlertRecipients) }
                    .onSuccess { unsupportedRecipients ->
                        if (unsupportedRecipients.isNotEmpty()) {
                            val message = unsupportedSmsRecipientMessage(unsupportedRecipients)
                            alertRecipientWarning = message
                            saveSettingsStatusMessage = message
                        } else {
                            saveSettingsStatusMessage = "Saving settings"
                            runCatching {
                                ArkluzLiveTrackingClient(context).saveSettings(settingsForSave)
                            }.onSuccess { result ->
                                val committedFollowerPassword = result.viewerPassword ?: followerPassword
                                val committedSettings =
                                    currentSettingsSnapshot(
                                        committedNotificationEmails = committedNotificationEmails,
                                        committedAlertRecipients = committedAlertRecipients,
                                        committedFollowerPassword = committedFollowerPassword,
                                    )
                                followerPassword = committedFollowerPassword
                                notificationEmailAddresses = committedNotificationEmails
                                notificationEmailInput = ""
                                alertRecipients = committedAlertRecipients
                                alertRecipientInput = ""
                                alertRecipientStatusMessage = null
                                alertRecipientWarning = null
                                LiveTrackingPreferences.save(context, committedSettings)
                                LiveTrackingPreferences.saveGroupSettings(context, committedSettings)
                                settingsSnapshot = committedSettings
                                if (sessionState.isTracking) {
                                    LiveTrackingService.updateAlertSettings(
                                        context = context,
                                        notificationEmails = settingsForSave.notificationEmails,
                                        alertEmails = settingsForSave.alertEmails,
                                        stuckAlarmMinutes = settingsForSave.stuckAlarmMinutes,
                                    )
                                }
                                saveSettingsStatusMessage = "Settings saved"
                                if (exitAfterSave) {
                                    page = LiveTrackingPage.MAIN
                                }
                            }.onFailure { error ->
                                saveSettingsStatusMessage =
                                    "Save failed: ${error.toArkluzFailureDetail()}"
                            }
                        }
                    }.onFailure { error ->
                        saveSettingsStatusMessage =
                            "Could not verify SMS alerts: ${error.toArkluzFailureDetail()}"
                    }
                isSavingSettings = false
            }
        }

        fun requestLeaveSettings() {
            val hasPendingRecipientInput =
                notificationEmailInput.isNotBlank() || alertRecipientInput.isNotBlank()
            if (
                hasPendingRecipientInput ||
                settingsSnapshot?.let { it != currentSettingsSnapshot() } == true
            ) {
                showUnsavedSettingsDialog = true
            } else {
                page = LiveTrackingPage.MAIN
            }
        }

        fun disconnectAndPrepareGroupSetup() {
            LiveTrackingService.stop(context)
            LiveTrackingPreferences.clear(context)
            group = ""
            participantPassword = ""
            followerPassword = ""
            loginJoinStatusMessage = null
            validationMessage = null
            sendStatusMessage = null
            saveSettingsStatusMessage = null
            deleteTracksStatusMessage = null
            recordedTrackDownloadStatusMessage = null
            pendingRegistrationGroup = null
            showCreateGroupDialog = false
            createGroupPasswordConfirmation = ""
            userName = ""
            notificationEmailInput = ""
            notificationEmailAddresses = emptyList()
            alertRecipientInput = ""
            alertRecipients = emptyList()
            alertRecipientStatusMessage = null
            alertRecipientWarning = null
            stuckAlarmMinutes = DEFAULT_NO_MOVEMENT_ALERT_MINUTES
            updateIntervalSeconds = 60
            comments = ""
            selectedGpxUri = null
            selectedGpxName = ""
            planSent = false
            settingsSnapshot = null
            showChangeGroupDialog = false
            page = LiveTrackingPage.SETUP
        }

        fun uploadPlanThenStart(settings: LiveTrackingSettings) {
            isStartingSession = true
            isSendingPlan = true
            sendStatusMessage = "Sending planned route"
            coroutineScope.launch {
                runCatching {
                    val client = ArkluzLiveTrackingClient(context)
                    client.uploadPlannedRoute(settings)
                }.onSuccess {
                    planSent = true
                    sendStatusMessage = null
                    delay(START_AFTER_UPLOAD_DELAY_MS)
                    LiveTrackingService.start(
                        context = context,
                        settings = settings,
                    )
                }.onFailure { error ->
                    sendStatusMessage = "Send failed: ${error.toArkluzFailureDetail()}"
                }
                isSendingPlan = false
                isStartingSession = false
            }
        }

        fun startTrackingNow() {
            isStartWaitingForPermissionResult = false
            continueStartAfterPermissionResult = false
            continueStartAfterBackgroundLocationResult = false
            pendingExternalSettingsStartStep = null
            continueStartAfterExternalSettingsResult = null
            showNotificationPermissionWarningDialog = false
            showBackgroundLocationDialog = false
            showBatteryOptimizationDialog = false
            isStartingSession = true
            validationMessage = null
            sendStatusMessage = null
            if (hasPlanContent && !planSent) {
                uploadPlanThenStart(settings)
            } else {
                LiveTrackingService.start(context = context, settings = settings)
                isStartingSession = false
            }
        }

        fun continueStartWithBatteryProtection() {
            if (!isIgnoringBatteryOptimizations(context)) {
                isStartingSession = false
                showBatteryOptimizationDialog = true
                return
            }
            startTrackingNow()
        }

        fun continueStartWithBackgroundLocationProtection() {
            hasBackgroundLocationPermission = hasBackgroundLocationPermission(context)
            if (needsBackgroundLocationPermission(context)) {
                isStartingSession = false
                showBackgroundLocationDialog = true
                return
            }
            continueStartWithBatteryProtection()
        }

        fun continueStartAfterSmsValidation() {
            val locationGranted = hasLocationPermission(context)
            val notificationGranted = hasLiveTrackingNotificationPermission(context)
            hasLocationPermission = locationGranted
            hasNotificationPermission = notificationGranted
            val missingPermissions = missingLiveTrackingRuntimePermissions(context)
            if (missingPermissions.isNotEmpty()) {
                if (needsLiveTrackingLocationDisclosure(missingPermissions)) {
                    isStartingSession = false
                    showLocationDisclosureDialog = true
                } else {
                    isStartWaitingForPermissionResult = true
                    locationPermissionLauncher.launch(missingPermissions)
                }
            } else {
                continueStartWithBackgroundLocationProtection()
            }
        }

        fun startLiveTracking() {
            if (group.isBlank()) {
                validationMessage = null
                page = LiveTrackingPage.SETUP
                return
            }
            validationMessage =
                validateStartSettings(
                    group = group,
                    participantPassword = participantPassword,
                    followerPassword = followerPassword,
                    userName = userName,
                    stuckAlarmMinutes = stuckAlarmMinutes,
                )
                    ?: validatePendingRecipientInputs(
                        notificationEmailInput = notificationEmailInput,
                        alertRecipientInput = alertRecipientInput,
                    )
            if (validationMessage != null) {
                isStartWaitingForPermissionResult = false
                isStartingSession = false
                return
            }
            if (!canStart) {
                isStartWaitingForPermissionResult = false
                isStartingSession = false
                return
            }
            if (isSendingPlan) {
                isStartWaitingForPermissionResult = false
                isStartingSession = false
                sendStatusMessage = "Please wait for the current send to finish before starting."
                return
            }
            isStartingSession = true
            val recipientsForStart = resolvedAlertRecipients(alertRecipients, alertRecipientInput)
            coroutineScope.launch {
                runCatching { unsupportedSmsRecipients(recipientsForStart) }
                    .onSuccess { unsupportedRecipients ->
                        if (unsupportedRecipients.isNotEmpty()) {
                            unsupportedSmsRecipientsOnStart = unsupportedRecipients
                            val message = unsupportedSmsRecipientMessage(unsupportedRecipients)
                            alertRecipientStatusMessage = message
                            alertRecipientWarning = message
                            isStartingSession = false
                        } else {
                            continueStartAfterSmsValidation()
                        }
                    }.onFailure { continueStartAfterSmsValidation() }
            }
        }

        LaunchedEffect(page, isConnected, alertRecipients, trackingEndpoint) {
            if (page != LiveTrackingPage.SETUP || !isConnected) return@LaunchedEffect
            val phoneNumbers = smsAlertRecipients(alertRecipients)
            if (phoneNumbers.isEmpty()) {
                alertRecipientWarning = null
                return@LaunchedEffect
            }
            runCatching {
                val client = ArkluzLiveTrackingClient(context)
                phoneNumbers.filter { phoneNumber ->
                    client.checkSmsSupport(
                        trackingUrl = trackingEndpoint.url,
                        phoneNumber = phoneNumber,
                    ) == ArkluzSmsSupport.UNSUPPORTED
                }
            }.onSuccess { unsupportedRecipients ->
                alertRecipientWarning =
                    if (unsupportedRecipients.isEmpty()) {
                        null
                    } else {
                        unsupportedSmsRecipientMessage(unsupportedRecipients)
                    }
            }
        }

        LaunchedEffect(continueStartAfterPermissionResult) {
            if (continueStartAfterPermissionResult) {
                continueStartAfterPermissionResult = false
                continueStartWithBackgroundLocationProtection()
            }
        }

        LaunchedEffect(continueStartAfterBackgroundLocationResult) {
            if (continueStartAfterBackgroundLocationResult) {
                continueStartAfterBackgroundLocationResult = false
                continueStartWithBatteryProtection()
            }
        }

        LaunchedEffect(continueStartAfterExternalSettingsResult) {
            when (continueStartAfterExternalSettingsResult) {
                ExternalSettingsStartStep.BACKGROUND_LOCATION -> {
                    continueStartAfterExternalSettingsResult = null
                    continueStartWithBackgroundLocationProtection()
                }

                ExternalSettingsStartStep.BATTERY_OPTIMIZATION -> {
                    continueStartAfterExternalSettingsResult = null
                    continueStartWithBatteryProtection()
                }

                null -> Unit
            }
        }

        BackHandler(enabled = page == LiveTrackingPage.SETUP) {
            if (isConnected) {
                requestLeaveSettings()
            } else {
                page = LiveTrackingPage.MAIN
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            when (page) {
                LiveTrackingPage.MAIN -> {
                    MainTrackingContent(
                        onBack = onBack,
                        onOpenSetup = {
                            if (isConnected) {
                                settingsSnapshot = currentSettingsSnapshot()
                            }
                            page = LiveTrackingPage.SETUP
                        },
                        onOpenGuide = onOpenQuickGuide,
                        isConnected = isConnected,
                        group = group,
                        hasSelectedGpx = selectedGpxUri != null,
                        selectedGpxName = selectedGpxName,
                        comments = comments,
                        onCommentsChange = {
                            comments = it
                            planSent = false
                            sendStatusMessage = null
                            savePlannedDraft(draftComments = it)
                        },
                        onPickGpx = {
                            if (
                                lastTransferGpxUri != null &&
                                selectedGpxUri?.toString() != lastTransferGpxUri.toString()
                            ) {
                                showUseLastTransferGpxDialog = true
                            } else {
                                gpxPicker.launch(arrayOf("application/gpx+xml", "text/xml", "*/*"))
                            }
                        },
                        onClearGpx = { clearPlannedGpx() },
                        showSendPlan = showSendPlan,
                        canSendPlan = canSendPlan,
                        isSendingPlan = isSendingPlan,
                        planSent = planSent,
                        onSendPlan = {
                            if (!sessionState.isTracking) {
                                sendStatusMessage = "Start live tracking before sending a route or comment."
                                return@MainTrackingContent
                            }
                            validationMessage =
                                validateAccountSettings(
                                    group = group,
                                    participantPassword = participantPassword,
                                    followerPassword = followerPassword,
                                )
                                    ?: validatePendingRecipientInputs(
                                        notificationEmailInput = notificationEmailInput,
                                        alertRecipientInput = alertRecipientInput,
                                    )
                            if (validationMessage != null) return@MainTrackingContent
                            isSendingPlan = true
                            sendStatusMessage = "Sending planned route"
                            coroutineScope.launch {
                                runCatching {
                                    val client = ArkluzLiveTrackingClient(context)
                                    client.uploadPlannedRoute(settings)
                                }.onSuccess { result ->
                                    planSent = true
                                    sendStatusMessage = result.message.ifBlank { "Sent" }
                                }.onFailure { error ->
                                    sendStatusMessage = "Send failed: ${error.toArkluzFailureDetail()}"
                                }
                                isSendingPlan = false
                            }
                        },
                        sessionState = sessionState,
                        updateIntervalSeconds = updateIntervalSeconds,
                        isStartingSession = isStartingSession,
                        validationMessage = validationMessage,
                        sendStatusMessage = sendStatusMessage,
                        onStart = { startLiveTracking() },
                        onPause = { LiveTrackingService.pause(context) },
                        onResume = { LiveTrackingService.resume(context) },
                        onStop = { LiveTrackingService.stop(context) },
                        userName = userName,
                        groupTrackUrl = groupTrackUrl,
                        userTrackUrl = userTrackUrl,
                        recordedTrackDownloadStatusMessage = recordedTrackDownloadStatusMessage,
                        isDownloadingRecordedTrack = isDownloadingRecordedTrack,
                        isDeletingTracks = isDeletingTracks,
                        deleteTracksStatusMessage = deleteTracksStatusMessage,
                        onDeleteRecordedTracks = {
                            deleteTracksStatusMessage = validatePlanSettings(group, participantPassword)
                            if (deleteTracksStatusMessage == null) {
                                isDeletingTracks = true
                                deleteTracksStatusMessage = "Deleting recorded tracks"
                                coroutineScope.launch {
                                    runCatching {
                                        ArkluzLiveTrackingClient(context).deleteRecordedTracks(settings)
                                    }.onSuccess { result ->
                                        deleteTracksStatusMessage =
                                            result.message.takeUnless { it == "Server accepted request" }
                                                ?: "Recorded tracks deleted"
                                    }.onFailure { error ->
                                        deleteTracksStatusMessage =
                                            "Delete failed: ${error.toArkluzFailureDetail()}"
                                    }
                                    isDeletingTracks = false
                                }
                            }
                        },
                        onDownloadUserTrack = {
                            recordedTrackDownloadStatusMessage =
                                validateRecordedTrackDownloadSettings(
                                    group = group,
                                    followerPassword = followerPassword,
                                    userName = userName,
                                    userOnly = true,
                                )
                            if (recordedTrackDownloadStatusMessage == null) {
                                downloadRecordedTrack(RecordedTrackDownloadTarget.USER)
                            }
                        },
                        scrollState = mainScrollState,
                        contentSpacing = contentSpacing,
                        isCompactLayout = compactLayout,
                        isCompactScreen = adaptive.isCompactScreen,
                        adaptive = adaptive,
                    )
                }

                LiveTrackingPage.SETUP -> {
                    if (!isConnected) {
                        LoginJoinContent(
                            onBack = { page = LiveTrackingPage.MAIN },
                            group = group,
                            onGroupChange = {
                                group = it
                                followerPassword = ""
                                loginJoinStatusMessage = null
                                pendingRegistrationGroup = null
                                showCreateGroupDialog = false
                                createGroupPasswordConfirmation = ""
                            },
                            participantPassword = participantPassword,
                            onParticipantPasswordChange = {
                                participantPassword = it
                                followerPassword = ""
                                if (pendingRegistrationGroup != group.trim()) {
                                    loginJoinStatusMessage = null
                                    pendingRegistrationGroup = null
                                    showCreateGroupDialog = false
                                    createGroupPasswordConfirmation = ""
                                }
                            },
                            isLoginJoinLoading = isLoginJoinLoading,
                            loginJoinStatusMessage = loginJoinStatusMessage,
                            onLoginJoin = {
                                loginJoinStatusMessage = validatePlanSettings(group, participantPassword)
                                if (loginJoinStatusMessage == null) {
                                    val cleanGroup = group.trim()
                                    isLoginJoinLoading = true
                                    loginJoinStatusMessage = "Checking group"
                                    coroutineScope.launch {
                                        runCatching {
                                            val client = ArkluzLiveTrackingClient(context)
                                            val checkResult = client.checkGroup(settings)
                                            if (checkResult.groupAvailable) {
                                                pendingRegistrationGroup = cleanGroup
                                                "Group available"
                                            } else {
                                                checkNotNull(checkResult.viewerPassword) {
                                                    "Connected, but viewer password was not returned"
                                                }.let { followerPassword = it }
                                                "Connected"
                                            }
                                        }.onSuccess { status ->
                                            if (status == "Group available") {
                                                loginJoinStatusMessage = "Group does not exist."
                                                createGroupPasswordConfirmation = ""
                                                showCreateGroupDialog = true
                                            } else {
                                                restoreGroupProfile(cleanGroup)
                                                loginJoinStatusMessage = "Connected to $cleanGroup"
                                            }
                                        }.onFailure { error ->
                                            loginJoinStatusMessage =
                                                "Unable to connect: ${error.toArkluzFailureDetail()}"
                                        }
                                        isLoginJoinLoading = false
                                    }
                                }
                            },
                            showCreateGroupDialog = showCreateGroupDialog,
                            createGroupPasswordConfirmation = createGroupPasswordConfirmation,
                            onCreateGroupPasswordConfirmationChange = { createGroupPasswordConfirmation = it },
                            onDismissCreateGroupDialog = {
                                showCreateGroupDialog = false
                                createGroupPasswordConfirmation = ""
                            },
                            onConfirmCreateGroup = {
                                loginJoinStatusMessage = validatePlanSettings(group, participantPassword)
                                if (loginJoinStatusMessage == null) {
                                    if (createGroupPasswordConfirmation.trim() != participantPassword.trim()) {
                                        loginJoinStatusMessage = "Password confirmation does not match."
                                        return@LoginJoinContent
                                    }
                                    showCreateGroupDialog = false
                                    isLoginJoinLoading = true
                                    loginJoinStatusMessage = "Creating group"
                                    coroutineScope.launch {
                                        runCatching {
                                            val client = ArkluzLiveTrackingClient(context)
                                            val registerResult = client.registerGroup(settings)
                                            val viewerPassword =
                                                registerResult.viewerPassword
                                                    ?: client.checkGroup(settings).viewerPassword
                                            checkNotNull(viewerPassword) {
                                                "Group created, but viewer password was not returned"
                                            }.let { followerPassword = it }
                                            pendingRegistrationGroup = null
                                            createGroupPasswordConfirmation = ""
                                            "Created + connected"
                                        }.onSuccess { status ->
                                            val cleanGroup = group.trim()
                                            restoreGroupProfile(cleanGroup)
                                            loginJoinStatusMessage = "$status to $cleanGroup"
                                        }.onFailure { error ->
                                            loginJoinStatusMessage =
                                                "Unable to create group: ${error.toArkluzFailureDetail()}"
                                        }
                                        isLoginJoinLoading = false
                                    }
                                }
                            },
                            scrollState = setupScrollState,
                            contentSpacing = contentSpacing,
                        )
                    } else {
                        SettingsContent(
                            onBack = { requestLeaveSettings() },
                            group = group,
                            onChangeGroup = { showChangeGroupDialog = true },
                            userName = userName,
                            onUserNameChange = {
                                userName = it
                                saveSettingsStatusMessage = null
                            },
                            notificationEmailInput = notificationEmailInput,
                            onNotificationEmailInputChange = {
                                notificationEmailInput = it
                                saveSettingsStatusMessage = null
                            },
                            notificationEmailAddresses = notificationEmailAddresses,
                            onNotificationEmailAdd = { email ->
                                notificationEmailAddresses = notificationEmailAddresses + email
                                saveSettingsStatusMessage = null
                            },
                            onNotificationEmailRemove = { email ->
                                notificationEmailAddresses = notificationEmailAddresses - email
                                saveSettingsStatusMessage = null
                            },
                            onPickNotificationEmailFromContacts = {
                                emailPickerTarget = EmailPickerTarget.NOTIFICATION
                                runCatching {
                                    contactEmailPicker.launch(contactEmailPickerIntent())
                                }.onFailure {
                                    emailPickerTarget = null
                                    saveSettingsStatusMessage = "No contacts app found"
                                }
                            },
                            alertRecipientInput = alertRecipientInput,
                            onAlertRecipientInputChange = {
                                alertRecipientInput = it
                                alertRecipientStatusMessage = null
                                saveSettingsStatusMessage = null
                            },
                            alertRecipients = alertRecipients,
                            onAlertRecipientAdd = ::addAlertRecipient,
                            onAlertRecipientRemove = { recipient ->
                                alertRecipients = alertRecipients - recipient
                                alertRecipientWarning = null
                                saveSettingsStatusMessage = null
                            },
                            onPickAlertEmailFromContacts = {
                                emailPickerTarget = EmailPickerTarget.ALERT
                                runCatching {
                                    contactEmailPicker.launch(contactEmailPickerIntent())
                                }.onFailure {
                                    emailPickerTarget = null
                                    saveSettingsStatusMessage = "No contacts app found"
                                }
                            },
                            onPickAlertPhoneFromContacts = {
                                runCatching {
                                    contactPhonePicker.launch(contactPhonePickerIntent())
                                }.onFailure {
                                    alertRecipientStatusMessage = "No contacts app found"
                                }
                            },
                            isValidatingAlertRecipient = isValidatingAlertRecipient,
                            alertRecipientStatusMessage =
                                alertRecipientStatusMessage ?: alertRecipientWarning,
                            stuckAlarmMinutes = stuckAlarmMinutes,
                            onStuckAlarmMinutesChange = { value ->
                                stuckAlarmMinutes =
                                    if (value == "-1") {
                                        value
                                    } else {
                                        value.filter(Char::isDigit)
                                    }
                                saveSettingsStatusMessage = null
                            },
                            updateIntervalSeconds = updateIntervalSeconds,
                            onUpdateIntervalSecondsChange = {
                                updateIntervalSeconds = it
                                saveSettingsStatusMessage = null
                            },
                            isSavingSettings = isSavingSettings,
                            saveSettingsStatusMessage = saveSettingsStatusMessage,
                            onSaveSettings = { saveSettings(exitAfterSave = true) },
                            scrollState = setupScrollState,
                            contentSpacing = contentSpacing,
                        )
                    }
                }
            }
        }
        if (showUseLastTransferGpxDialog && lastTransferGpxUri != null) {
            AlertDialog(
                onDismissRequest = { showUseLastTransferGpxDialog = false },
                title = { Text(stringResource(R.string.live_tracking_use_selected_gpx_title)) },
                text = {
                    Text(
                        lastTransferGpxName
                            .ifBlank { stringResource(R.string.live_tracking_last_selected_gpx) },
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showUseLastTransferGpxDialog = false
                            selectPlannedGpx(
                                uri = lastTransferGpxUri,
                                name = lastTransferGpxName,
                            )
                        },
                    ) {
                        Text(stringResource(R.string.live_tracking_action_use_this_gpx))
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showUseLastTransferGpxDialog = false }) {
                            Text(stringResource(R.string.common_action_cancel))
                        }
                        OutlinedButton(
                            onClick = {
                                showUseLastTransferGpxDialog = false
                                gpxPicker.launch(arrayOf("application/gpx+xml", "text/xml", "*/*"))
                            },
                        ) {
                            Text(stringResource(R.string.live_tracking_action_choose_another))
                        }
                    }
                },
            )
        }
        if (showLocationDisclosureDialog) {
            AlertDialog(
                onDismissRequest = {
                    showLocationDisclosureDialog = false
                    isStartingSession = false
                },
                title = { Text(stringResource(R.string.live_tracking_location_disclosure_title)) },
                text = {
                    Text(
                        stringResource(R.string.live_tracking_location_disclosure_message),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showLocationDisclosureDialog = false
                            val missingPermissions = missingLiveTrackingRuntimePermissions(context)
                            if (missingPermissions.isEmpty()) {
                                continueStartWithBackgroundLocationProtection()
                            } else {
                                isStartingSession = true
                                isStartWaitingForPermissionResult = true
                                locationPermissionLauncher.launch(missingPermissions)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.common_action_continue))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showLocationDisclosureDialog = false
                            isStartingSession = false
                        },
                    ) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                },
            )
        }
        if (showNotificationPermissionWarningDialog) {
            AlertDialog(
                onDismissRequest = {
                    showNotificationPermissionWarningDialog = false
                    isStartingSession = false
                },
                title = { Text(stringResource(R.string.live_tracking_notifications_off_title)) },
                text = {
                    Text(
                        stringResource(R.string.live_tracking_notifications_off_message),
                    )
                },
                confirmButton = {
                    Button(onClick = { continueStartWithBackgroundLocationProtection() }) {
                        Text(stringResource(R.string.live_tracking_action_start_anyway))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showNotificationPermissionWarningDialog = false
                            isStartingSession = false
                        },
                    ) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                },
            )
        }
        if (showBackgroundLocationDialog) {
            AlertDialog(
                onDismissRequest = {
                    showBackgroundLocationDialog = false
                    isStartingSession = false
                },
                title = { Text(stringResource(R.string.live_tracking_background_location_title)) },
                text = {
                    Text(
                        stringResource(R.string.live_tracking_background_location_message),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBackgroundLocationDialog = false
                            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                                backgroundLocationPermissionLauncher.launch(
                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                )
                            } else {
                                runCatching {
                                    pendingExternalSettingsStartStep =
                                        ExternalSettingsStartStep.BACKGROUND_LOCATION
                                    externalSettingsLauncher.launch(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        ),
                                    )
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.live_tracking_action_open_location_settings))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showBackgroundLocationDialog = false
                            continueStartWithBatteryProtection()
                        },
                    ) {
                        Text(stringResource(R.string.live_tracking_action_start_foreground_service))
                    }
                },
            )
        }
        if (showBatteryOptimizationDialog) {
            AlertDialog(
                onDismissRequest = {
                    showBatteryOptimizationDialog = false
                    isStartingSession = false
                },
                title = { Text(stringResource(R.string.live_tracking_battery_optimization_title)) },
                text = {
                    Text(
                        stringResource(R.string.live_tracking_battery_optimization_message),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBatteryOptimizationDialog = false
                            runCatching {
                                pendingExternalSettingsStartStep =
                                    ExternalSettingsStartStep.BATTERY_OPTIMIZATION
                                externalSettingsLauncher.launch(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.live_tracking_action_open_battery_settings))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showBatteryOptimizationDialog = false
                            startTrackingNow()
                        },
                    ) {
                        Text(stringResource(R.string.live_tracking_action_start_anyway))
                    }
                },
            )
        }
        if (showChangeGroupDialog) {
            AlertDialog(
                onDismissRequest = { showChangeGroupDialog = false },
                title = { Text(stringResource(R.string.live_tracking_change_group_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.live_tracking_change_group_message,
                            group.trim().ifBlank {
                                stringResource(R.string.live_tracking_current_group)
                            },
                        ),
                    )
                },
                confirmButton = {
                    Button(onClick = { disconnectAndPrepareGroupSetup() }) {
                        Text(stringResource(R.string.live_tracking_action_change_group))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showChangeGroupDialog = false }) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                },
            )
        }
        if (showUnsavedSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showUnsavedSettingsDialog = false },
                title = { Text(stringResource(R.string.live_tracking_save_settings_title)) },
                text = { Text(stringResource(R.string.live_tracking_save_settings_message)) },
                confirmButton = {
                    Button(
                        onClick = {
                            showUnsavedSettingsDialog = false
                            saveSettings(exitAfterSave = true)
                        },
                        enabled = !isSavingSettings,
                    ) {
                        Text(stringResource(R.string.common_action_save))
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showUnsavedSettingsDialog = false }) {
                            Text(stringResource(R.string.common_action_cancel))
                        }
                        OutlinedButton(
                            onClick = {
                                settingsSnapshot?.let(::applySettingsSnapshot)
                                showUnsavedSettingsDialog = false
                                page = LiveTrackingPage.MAIN
                            },
                        ) {
                            Text(stringResource(R.string.common_action_discard))
                        }
                    }
                },
            )
        }
        unsupportedSmsRecipientsOnStart?.let { phoneNumbers ->
            AlertDialog(
                onDismissRequest = { unsupportedSmsRecipientsOnStart = null },
                title = { Text(stringResource(R.string.live_tracking_sms_alerts_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.live_tracking_sms_alerts_message,
                            phoneNumbers.joinToString(),
                        ),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            settingsSnapshot = currentSettingsSnapshot()
                            unsupportedSmsRecipientsOnStart = null
                            page = LiveTrackingPage.SETUP
                        },
                    ) {
                        Text(stringResource(R.string.live_tracking_action_update_alerts))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { unsupportedSmsRecipientsOnStart = null }) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                },
            )
        }
    }
}
