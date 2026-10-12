package com.glancemap.glancemapcompanionapp.livetracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.glancemap.glancemapcompanionapp.R
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun validatePlanSettings(
    text: (Int) -> String,
    group: String,
    participantPassword: String,
): String? =
    when {
        group.isBlank() -> text(R.string.live_tracking_private_group_is_required)
        participantPassword.isBlank() -> text(R.string.live_tracking_participant_password_is_required)
        else -> null
    }

internal fun validateAccountSettings(
    text: (Int) -> String,
    group: String,
    participantPassword: String,
    followerPassword: String,
): String? =
    when {
        group.isBlank() -> text(R.string.live_tracking_private_group_is_required)
        participantPassword.isBlank() -> text(R.string.live_tracking_participant_password_is_required)
        followerPassword.isBlank() -> text(R.string.live_tracking_set_up_live_tracking_first)
        else -> null
    }

internal fun validateStartSettings(
    text: (Int) -> String,
    group: String,
    participantPassword: String,
    followerPassword: String,
    userName: String,
): String? =
    when {
        group.isBlank() -> text(R.string.live_tracking_private_group_is_required)
        participantPassword.isBlank() -> text(R.string.live_tracking_participant_password_is_required)
        followerPassword.isBlank() -> text(R.string.live_tracking_create_join_in_settings_first)
        userName.isBlank() -> text(R.string.live_tracking_participant_name_is_required)
        else -> null
    }

internal fun validateNoMovementAlertMinutes(
    text: (Int) -> String,
    minutes: String,
): String? {
    val value = minutes.trim().toIntOrNull()
    return when {
        value == -1 -> null
        value == null || value < 10 -> text(R.string.live_tracking_no_movement_alert_must_be_at_least)
        else -> null
    }
}

internal fun validateRecordedTrackDownloadSettings(
    text: (Int) -> String,
    group: String,
    followerPassword: String,
    userName: String,
    userOnly: Boolean,
): String? =
    when {
        group.isBlank() -> text(R.string.live_tracking_private_group_is_required)
        followerPassword.isBlank() -> text(R.string.live_tracking_set_up_live_tracking_first)
        userOnly && userName.isBlank() -> text(R.string.live_tracking_participant_name_is_required)
        else -> null
    }

internal enum class AlertRecipientType {
    EMAIL,
    SMS,
}

internal data class AlertRecipient(
    val value: String,
    val type: AlertRecipientType,
)

internal fun validatePendingRecipientInputs(
    text: (Int) -> String,
    notificationEmailInput: String,
    alertRecipientInput: String,
): String? =
    validatePendingEmailInput(
        text,
        input = notificationEmailInput,
        label = text(R.string.live_tracking_tracking_notification_email),
    )
        ?: validatePendingAlertRecipientInput(text, alertRecipientInput)

internal fun validatePendingEmailInput(
    text: (Int) -> String,
    input: String,
    label: String,
): String? {
    val email = input.normalizedEmailInput()
    return when {
        email.isBlank() -> null
        email.isValidEmailAddress() -> null
        else -> text(R.string.live_tracking_enter_a_valid_value_address).format(label)
    }
}

internal fun validatePendingAlertRecipientInput(
    text: (Int) -> String,
    input: String,
): String? =
    when {
        input.isBlank() -> null
        normalizedAlertRecipient(input) != null -> null
        else -> text(R.string.live_tracking_enter_a_valid_alert_email_address_or)
    }

internal fun emailAddressesForRequest(
    addresses: List<String>,
    pendingInput: String,
): String = resolvedEmailAddresses(addresses, pendingInput).joinToString(",")

internal fun resolvedEmailAddresses(
    addresses: List<String>,
    pendingInput: String,
): List<String> {
    val pendingEmail = pendingInput.normalizedEmailInput()
    return buildList {
        addresses
            .map(String::normalizedEmailInput)
            .filter(String::isNotBlank)
            .forEach(::add)
        if (pendingEmail.isValidEmailAddress()) add(pendingEmail)
    }.distinctBy(String::lowercase)
}

private fun String.normalizedEmailInput(): String = trim().trimEnd(',', ';').lowercase()

private fun String.isValidEmailAddress(): Boolean = matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))

internal fun alertRecipientsForRequest(
    recipients: List<String>,
    pendingInput: String,
): String = resolvedAlertRecipients(recipients, pendingInput).joinToString(",")

internal fun resolvedAlertRecipients(
    recipients: List<String>,
    pendingInput: String,
): List<String> =
    buildList {
        recipients.mapNotNull(::normalizedAlertRecipient).forEach { add(it.value) }
        normalizedAlertRecipient(pendingInput)?.let { add(it.value) }
    }.distinctBy(String::lowercase)

internal fun smsAlertRecipients(
    recipients: List<String>,
    pendingInput: String = "",
): List<String> =
    buildList {
        recipients.forEach { recipient ->
            normalizedAlertRecipient(recipient)
                ?.takeIf { it.type == AlertRecipientType.SMS }
                ?.let { add(it.value) }
        }
        normalizedAlertRecipient(pendingInput)
            ?.takeIf { it.type == AlertRecipientType.SMS }
            ?.let { add(it.value) }
    }.distinct()

internal fun normalizedAlertRecipient(input: String): AlertRecipient? {
    val trimmedInput = input.trim()
    val email = trimmedInput.lowercase()
    val digits = trimmedInput.filter(Char::isDigit)
    val hasValidPhoneCharacters =
        trimmedInput.all { character ->
            character.isDigit() || character in "+-()./ "
        }
    return when {
        trimmedInput.isBlank() -> null
        email.isValidEmailAddress() -> AlertRecipient(value = email, type = AlertRecipientType.EMAIL)
        !trimmedInput.startsWith("+") || trimmedInput.count { it == '+' } != 1 -> null
        !hasValidPhoneCharacters || digits.isBlank() -> null
        else -> AlertRecipient(value = "+$digits", type = AlertRecipientType.SMS)
    }
}

internal fun recordedTrackDownloadFilename(
    group: String,
    userName: String,
    target: RecordedTrackDownloadTarget,
): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val rawName =
        when (target) {
            RecordedTrackDownloadTarget.USER -> userName.ifBlank { "user" }
            RecordedTrackDownloadTarget.GROUP -> group.ifBlank { "group" }
        }
    val safeName =
        rawName
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { target.name.lowercase() }
    return "$day-$safeName.gpx"
}

@Suppress("ReturnCount")
internal fun contentDispositionFileName(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val extendedValue =
        Regex("""(?i)(?:^|;)\s*filename\*\s*=\s*("[^"]*"|[^;]*)""")
            .find(header)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.removeSurrounding("\"")
    val extendedFileName =
        extendedValue
            ?.split('\'', limit = 3)
            ?.takeIf { it.size == 3 && it[0].equals("UTF-8", ignoreCase = true) }
            ?.get(2)
            ?.decodeRfc5987Utf8()
            ?.safeDownloadFileName()
    if (!extendedFileName.isNullOrBlank()) return extendedFileName

    return Regex("""(?i)(?:^|;)\s*filename\s*=\s*("(?:\\.|[^"])*"|[^;]*)""")
        .find(header)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.removeSurrounding("\"")
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")
        ?.safeDownloadFileName()
        ?.takeIf { it.isNotBlank() }
}

private fun String.decodeRfc5987Utf8(): String? =
    runCatching {
        val bytes = ByteArrayOutputStream(length)
        var index = 0
        while (index < length) {
            if (this[index] == '%' && index + 2 < length) {
                val value = substring(index + 1, index + 3).toIntOrNull(16)
                if (value != null) {
                    bytes.write(value)
                    index += 3
                    continue
                }
            }
            this[index]
                .toString()
                .toByteArray(Charsets.UTF_8)
                .forEach { byte -> bytes.write(byte.toInt()) }
            index += 1
        }
        bytes.toByteArray().toString(Charsets.UTF_8)
    }.getOrNull()

private fun String.safeDownloadFileName(): String =
    substringAfterLast('/')
        .substringAfterLast('\\')
        .replace("\r", "")
        .replace("\n", "")
        .trim()

internal enum class LiveTrackingPermissionOutcome {
    CONTINUE,
    LOCATION_REQUIRED,
    NOTIFICATION_WARNING,
}

internal fun liveTrackingPermissionOutcome(
    locationGranted: Boolean,
    notificationGranted: Boolean,
): LiveTrackingPermissionOutcome =
    when {
        !locationGranted -> LiveTrackingPermissionOutcome.LOCATION_REQUIRED
        !notificationGranted -> LiveTrackingPermissionOutcome.NOTIFICATION_WARNING
        else -> LiveTrackingPermissionOutcome.CONTINUE
    }

internal fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

internal fun hasBackgroundLocationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

internal fun needsBackgroundLocationPermission(context: Context): Boolean =
    backgroundLocationProtectionRequired(
        supportsBackgroundLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        foregroundLocationGranted = hasLocationPermission(context),
        backgroundLocationGranted = hasBackgroundLocationPermission(context),
    )

internal fun backgroundLocationProtectionRequired(
    supportsBackgroundLocation: Boolean,
    foregroundLocationGranted: Boolean,
    backgroundLocationGranted: Boolean,
): Boolean = supportsBackgroundLocation && foregroundLocationGranted && !backgroundLocationGranted

internal fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        true
    } else {
        context
            .getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName)
            ?: false
    }

internal fun hasLiveTrackingNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

internal fun missingLiveTrackingRuntimePermissions(context: Context): Array<String> =
    buildList {
        if (!hasLocationPermission(context)) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!hasLiveTrackingNotificationPermission(context)) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

internal fun needsLiveTrackingLocationDisclosure(missingPermissions: Array<String>): Boolean =
    missingPermissions.any { permission ->
        permission == Manifest.permission.ACCESS_FINE_LOCATION ||
            permission == Manifest.permission.ACCESS_COARSE_LOCATION
    }

internal fun sessionStatusText(
    text: (Int) -> String,
    state: LiveTrackingUiState,
): String {
    val lastUpdate = state.lastSuccessfulUpdateEpochMs
    val lastUpdateText =
        if (lastUpdate == null) {
            text(R.string.live_tracking_none)
        } else {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastUpdate))
        }
    return text(
        R.string.live_tracking_value_last_successful_update_value,
    ).format(localizedSessionStatus(state.status, text), lastUpdateText)
}

internal fun arkluzTrackUrl(
    baseUrl: String,
    group: String,
    followerPassword: String,
    user: String?,
    selectedUser: String?,
): String {
    val cleanGroup = group.trim()
    val cleanPassword = followerPassword.trim()
    if (cleanGroup.isBlank() || cleanPassword.isBlank()) return ""

    val builder =
        Uri
            .parse(baseUrl)
            .buildUpon()
            .appendQueryParameter("q", "track")
            .appendQueryParameter("group", cleanGroup)
            .appendQueryParameter("p", cleanPassword)
    user
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { builder.appendQueryParameter("user", it) }
    selectedUser
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { builder.appendQueryParameter("select", it) }
    return builder.build().toString()
}

internal fun openUrl(
    context: Context,
    url: String,
) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

internal fun contactEmailPickerIntent(): Intent =
    Intent(
        Intent.ACTION_PICK,
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
    )

internal fun contactPhonePickerIntent(): Intent =
    Intent(
        Intent.ACTION_PICK,
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
    )

internal fun resolveSelectedContactEmail(
    context: Context,
    uri: Uri,
): String? =
    context.contentResolver
        .query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor
                .getString(0)
                ?.trim()
                ?.lowercase()
                ?.takeIf(String::isValidEmailAddress)
        }

internal fun resolveSelectedContactPhone(
    context: Context,
    uri: Uri,
): String? =
    context.contentResolver
        .query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor
                .getString(0)
                ?.let(::normalizedAlertRecipient)
                ?.takeIf { it.type == AlertRecipientType.SMS }
                ?.value
        }

internal fun shareUrl(
    context: Context,
    url: String,
) {
    if (url.isBlank()) return
    val intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.live_tracking_share_track_link)))
}

internal fun emailArkluzSupport(
    context: Context,
    errorMessage: String,
) {
    val body =
        buildString {
            appendLine("Hello,")
            appendLine()
            appendLine("I got this Live Tracking error from GlanceMap Companion:")
            appendLine(errorMessage.ifBlank { "Unknown error" })
            appendLine()
            appendLine("Thanks")
        }
    val intent =
        Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("mailto:arkluz@arkluz.com"),
        ).apply {
            putExtra(Intent.EXTRA_SUBJECT, "GlanceMap Live Tracking error")
            putExtra(Intent.EXTRA_TEXT, body)
        }
    runCatching {
        context.startActivity(intent)
    }
}

private fun localizedSessionStatus(
    status: String,
    text: (Int) -> String,
): String =
    when (status) {
        "Not started" -> text(R.string.live_tracking_status_not_started)
        "Starting" -> text(R.string.live_tracking_action_starting)
        "Position sent" -> text(R.string.tracking_notification_position_sent)
        "Paused" -> text(R.string.transfer_status_paused)
        "Paused on phone. Arkluz has not received the pause yet; a no-movement alert may still be sent." ->
            text(
                R.string.live_tracking_status_paused_on_phone_arkluz_has_not_received,
            )
        "Waiting for network to start tracking" ->
            text(
                R.string.tracking_notification_waiting_for_network_to_start_tracking,
            )
        "Tracking resumed — Arkluz notification pending" ->
            text(
                R.string.live_tracking_status_tracking_resumed_arkluz_notification_pending,
            )
        "Tracking with errors" -> text(R.string.live_tracking_status_tracking_with_errors)
        "Stopped" -> text(R.string.live_tracking_status_stopped)
        else -> status
    }
