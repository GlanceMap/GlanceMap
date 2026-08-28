package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstruction
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.RouteInstructionCommand
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun TurnByTurnGuidanceVoiceEffect(
    context: Context,
    state: TurnByTurnGuidanceState,
    currentSpeedMps: Float?,
    activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
    voiceEnabled: Boolean,
    turnAlertsMode: String,
    offRouteAlertsEnabled: Boolean,
    offRouteRepeatSeconds: Int,
    paused: Boolean,
    isMetric: Boolean,
) {
    if (!voiceEnabled) return

    val appContext = context.applicationContext
    val applicationScope = remember(appContext) { (appContext as GlanceMapWearApp).applicationScope }
    val ttsLifecycle = remember(appContext) { TtsInstanceLifecycle() }
    var ttsReady by remember { mutableStateOf(false) }
    var alertedInstructionKey by remember { mutableStateOf<String?>(null) }
    var alertedStraightSectionKey by remember { mutableStateOf<String?>(null) }
    var arrivalAlertedTrack by remember { mutableStateOf<String?>(null) }
    var offRouteRecoveryPending by remember { mutableStateOf(false) }
    val latestState by rememberUpdatedState(state)
    val tts =
        remember(appContext) {
            TextToSpeech(appContext) { status ->
                ttsLifecycle.runIfActive {
                    val ready = status == TextToSpeech.SUCCESS
                    ttsReady = ready
                    DebugTelemetry.log("TurnByTurn", "voice=init status=$status ready=$ready")
                }
            }.also { engine ->
                engine.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            DebugTelemetry.log("TurnByTurn", "voice=utterance_start id=${utteranceId ?: "na"}")
                        }

                        override fun onDone(utteranceId: String?) {
                            DebugTelemetry.log("TurnByTurn", "voice=utterance_done id=${utteranceId ?: "na"}")
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            DebugTelemetry.log("TurnByTurn", "voice=utterance_error id=${utteranceId ?: "na"}")
                        }

                        override fun onError(
                            utteranceId: String?,
                            errorCode: Int,
                        ) {
                            DebugTelemetry.log(
                                "TurnByTurn",
                                "voice=utterance_error id=${utteranceId ?: "na"} code=$errorCode",
                            )
                        }
                    },
                )
            }
        }

    DisposableEffect(tts) {
        onDispose {
            ttsLifecycle.retire {
                applicationScope.launch {
                    try {
                        tts.stop()
                    } finally {
                        tts.shutdown()
                    }
                }
            }
        }
    }

    LaunchedEffect(ttsReady) {
        if (ttsReady) {
            ttsLifecycle.runIfActive {
                val locale = Locale.getDefault()
                val languageResult = tts.setLanguage(locale)
                val selectedVoice = selectBestLocalTtsVoice(tts.voices, locale)
                val voiceResult = selectedVoice?.let { tts.setVoice(it) }
                val speechRateResult = tts.setSpeechRate(TTS_SPEECH_RATE)
                val activeVoice = tts.voice ?: selectedVoice
                DebugTelemetry.log(
                    "TurnByTurn",
                    "voice=config languageResult=$languageResult voiceResult=${voiceResult ?: "na"} " +
                        "speechRate=$TTS_SPEECH_RATE speechRateResult=$speechRateResult " +
                        "locale=${locale.toLanguageTag()} " +
                        "engine=${tts.defaultEngine ?: "na"} selectedVoice=${activeVoice?.name ?: "na"} " +
                        "voiceLocale=${activeVoice?.locale?.toLanguageTag() ?: "na"} " +
                        "quality=${activeVoice?.quality ?: "na"} latency=${activeVoice?.latency ?: "na"} " +
                        "network=${activeVoice?.isNetworkConnectionRequired ?: "na"} " +
                        "availableVoices=${tts.voices?.size ?: 0} " +
                        "localLocaleVoices=${tts.voices.localVoiceCount(locale)}",
                )
            }
        }
    }

    LaunchedEffect(state.active, state.trackTitle) {
        if (!state.active) {
            alertedInstructionKey = null
            alertedStraightSectionKey = null
            arrivalAlertedTrack = null
            offRouteRecoveryPending = false
            ttsLifecycle.runIfActive { tts.stop() }
        }
    }

    LaunchedEffect(
        ttsReady,
        state.active,
        state.mode,
        state.offRoute,
        state.nextInstruction?.trackPointIndex,
        state.distanceToInstructionMeters,
        currentSpeedMps,
        activityProfile,
        voiceEnabled,
        turnAlertsMode,
        paused,
    ) {
        if (!ttsReady || paused) return@LaunchedEffect
        val instruction = state.nextInstruction ?: return@LaunchedEffect
        if (!shouldAlertForTurn(turnAlertsMode, instruction.command)) return@LaunchedEffect
        if (state.mode != GuidanceMode.FOLLOW_ROUTE) return@LaunchedEffect
        if (state.offRoute) return@LaunchedEffect
        val distanceMeters = state.distanceToInstructionMeters ?: return@LaunchedEffect
        val alertDistanceMeters = turnAlertDistanceMeters(currentSpeedMps, activityProfile)
        if (distanceMeters > alertDistanceMeters) return@LaunchedEffect

        val instructionKey = "${state.trackTitle}:${instruction.trackPointIndex}:${instruction.command}"
        if (alertedInstructionKey == instructionKey) return@LaunchedEffect
        val spokenText =
            spokenInstructionText(
                instruction = instruction,
                distanceMeters = distanceMeters,
                followingInstruction = state.followingInstruction,
                distanceToFollowingInstructionMeters = state.distanceToFollowingInstructionMeters,
                isMetric = isMetric,
            )
        DebugTelemetry.log(
            "TurnByTurn",
            "voice=turn command=${instruction.command} index=${instruction.trackPointIndex} " +
                "distanceM=${distanceMeters.toInt()} alertDistanceM=${alertDistanceMeters.toInt()} " +
                "profile=$activityProfile",
        )
        val speakResult =
            ttsLifecycle.runIfActive {
                tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, instructionKey)
            } ?: return@LaunchedEffect
        DebugTelemetry.log("TurnByTurn", "voice=speak_result id=$instructionKey result=$speakResult")
        if (speakResult == TextToSpeech.SUCCESS) {
            alertedInstructionKey = instructionKey
        }
    }

    LaunchedEffect(
        ttsReady,
        state.active,
        state.mode,
        state.offRoute,
        state.nextInstruction?.trackPointIndex,
        state.distanceToInstructionMeters,
        voiceEnabled,
        paused,
        isMetric,
    ) {
        if (!ttsReady || paused || !voiceEnabled) return@LaunchedEffect
        if (!shouldSpeakContinueStraightPrompt(state)) return@LaunchedEffect
        val instruction = state.nextInstruction ?: return@LaunchedEffect
        val distanceMeters = state.distanceToInstructionMeters ?: return@LaunchedEffect
        val straightKey = "${state.trackTitle}:straight:${instruction.trackPointIndex}"
        if (alertedStraightSectionKey == straightKey) return@LaunchedEffect

        val spokenText = spokenContinueStraightText(distanceMeters, isMetric)
        DebugTelemetry.log(
            "TurnByTurn",
            "voice=continue_straight index=${instruction.trackPointIndex} " +
                "distanceM=${distanceMeters.toInt()} thresholdM=${VOICE_CONTINUE_STRAIGHT_MIN_DISTANCE_METERS.toInt()}",
        )
        val speakResult =
            ttsLifecycle.runIfActive {
                tts.speak(spokenText, TextToSpeech.QUEUE_ADD, null, straightKey)
            } ?: return@LaunchedEffect
        DebugTelemetry.log("TurnByTurn", "voice=speak_result id=$straightKey result=$speakResult")
        if (speakResult == TextToSpeech.SUCCESS) {
            alertedStraightSectionKey = straightKey
        }
    }

    LaunchedEffect(ttsReady, state.active, state.mode, state.trackTitle, paused) {
        if (!ttsReady || paused || !state.active || state.mode != GuidanceMode.FINISHED) {
            return@LaunchedEffect
        }
        val trackKey = state.trackTitle ?: "active_route"
        if (arrivalAlertedTrack == trackKey) return@LaunchedEffect
        DebugTelemetry.log("TurnByTurn", "voice=arrival track=${state.trackTitle ?: "unknown"}")
        val utteranceId = "arrival:$trackKey"
        val speakResult =
            ttsLifecycle.runIfActive {
                tts.speak("You have arrived", TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } ?: return@LaunchedEffect
        DebugTelemetry.log("TurnByTurn", "voice=speak_result id=$utteranceId result=$speakResult")
        if (speakResult == TextToSpeech.SUCCESS) {
            arrivalAlertedTrack = trackKey
        }
    }

    LaunchedEffect(
        ttsReady,
        state.active,
        state.mode,
        state.offRoute,
        voiceEnabled,
        offRouteAlertsEnabled,
        offRouteRepeatSeconds,
        paused,
    ) {
        if (!ttsReady || paused || !voiceEnabled || !offRouteAlertsEnabled) return@LaunchedEffect
        if (!state.active || state.mode != GuidanceMode.FOLLOW_ROUTE || !state.offRoute) return@LaunchedEffect
        offRouteRecoveryPending = true

        while (isActive) {
            val currentState = latestState
            if (!currentState.active || currentState.mode != GuidanceMode.FOLLOW_ROUTE || !currentState.offRoute) {
                return@LaunchedEffect
            }
            val utteranceId = "off_route:${System.currentTimeMillis()}"
            val speakResult =
                ttsLifecycle.runIfActive {
                    tts.speak("You are off route.", TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                } ?: return@LaunchedEffect
            DebugTelemetry.log(
                "TurnByTurn",
                "voice=off_route distanceToRouteM=${currentState.distanceToRouteMeters?.toInt() ?: "na"} " +
                    "repeatSeconds=$offRouteRepeatSeconds result=$speakResult",
            )
            delay(offRouteRepeatSeconds.coerceAtLeast(VOICE_OFF_ROUTE_MIN_REPEAT_SECONDS) * 1_000L)
        }
    }

    LaunchedEffect(
        ttsReady,
        state.active,
        state.mode,
        state.offRoute,
        voiceEnabled,
        offRouteAlertsEnabled,
        paused,
        offRouteRecoveryPending,
    ) {
        if (!ttsReady || paused || !voiceEnabled || !offRouteAlertsEnabled) return@LaunchedEffect
        if (!offRouteRecoveryPending) return@LaunchedEffect
        if (!state.active || state.mode != GuidanceMode.FOLLOW_ROUTE || state.offRoute) return@LaunchedEffect

        val utteranceId = "back_on_route:${System.currentTimeMillis()}"
        val speakResult =
            ttsLifecycle.runIfActive {
                tts.speak("Back on route", TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } ?: return@LaunchedEffect
        DebugTelemetry.log(
            "TurnByTurn",
            "voice=back_on_route distanceToRouteM=${state.distanceToRouteMeters?.toInt() ?: "na"} result=$speakResult",
        )
        if (speakResult == TextToSpeech.SUCCESS) {
            offRouteRecoveryPending = false
        }
    }
}

private fun selectBestLocalTtsVoice(
    voices: Set<Voice>?,
    locale: Locale,
): Voice? {
    val availableVoices = voices.orEmpty()
    if (availableVoices.isEmpty()) return null
    return availableVoices
        .asSequence()
        .filterNot { it.isNetworkConnectionRequired }
        .filter { it.locale?.language == locale.language }
        .sortedWith(
            compareByDescending<Voice> { voiceLocaleScore(it.locale, locale) }
                .thenByDescending { it.quality }
                .thenBy { it.latency }
                .thenBy { it.name },
        ).firstOrNull()
        ?: availableVoices
            .asSequence()
            .filterNot { it.isNetworkConnectionRequired }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name },
            ).firstOrNull()
}

private fun Set<Voice>?.localVoiceCount(locale: Locale): Int =
    this
        .orEmpty()
        .count { voice ->
            !voice.isNetworkConnectionRequired &&
                voice.locale?.language == locale.language
        }

private fun voiceLocaleScore(
    voiceLocale: Locale?,
    targetLocale: Locale,
): Int =
    when {
        voiceLocale == null -> 0
        voiceLocale == targetLocale -> 3
        voiceLocale.language == targetLocale.language &&
            voiceLocale.country.isNotBlank() &&
            voiceLocale.country == targetLocale.country -> 2
        voiceLocale.language == targetLocale.language -> 1
        else -> 0
    }

internal fun shouldSpeakContinueStraightPrompt(state: TurnByTurnGuidanceState): Boolean {
    if (!state.active || state.mode != GuidanceMode.FOLLOW_ROUTE || state.offRoute) return false
    val distanceMeters = state.distanceToInstructionMeters ?: return false
    if (!distanceMeters.isFinite() || distanceMeters < VOICE_CONTINUE_STRAIGHT_MIN_DISTANCE_METERS) return false
    val instruction = state.nextInstruction ?: return false
    return instruction.command != RouteInstructionCommand.CONTINUE
}

internal fun spokenContinueStraightText(
    distanceMeters: Double,
    isMetric: Boolean,
): String = "Continue straight ${spokenStraightDistance(distanceMeters, isMetric)}"

private fun spokenInstructionText(
    instruction: RouteInstruction,
    distanceMeters: Double,
    followingInstruction: RouteInstruction?,
    distanceToFollowingInstructionMeters: Double?,
    isMetric: Boolean,
): String {
    val action = spokenTurnAction(instruction.command)
    val distance = spokenDistancePrefix(distanceMeters, isMetric)
    val primary = if (distance == null) action else "$distance, $action"
    val followingGapMeters =
        distanceToFollowingInstructionMeters
            ?.let { it - distanceMeters }
            ?.takeIf { it in 0.0..VOICE_FOLLOWING_TURN_MAX_GAP_METERS }
    val followingAction =
        if (followingGapMeters != null && followingInstruction != null) {
            spokenTurnAction(followingInstruction.command)
        } else {
            null
        }
    return if (followingAction != null) "$primary, then $followingAction" else primary
}

private fun spokenStraightDistance(
    distanceMeters: Double,
    isMetric: Boolean,
): String {
    if (!distanceMeters.isFinite() || distanceMeters <= 0.0) return ""
    if (!isMetric) {
        val feet = distanceMeters * METERS_TO_FEET
        return if (feet < FEET_PER_HALF_MILE) {
            val roundedFeet = ((feet / 25.0).roundToInt() * 25).coerceAtLeast(50)
            "for $roundedFeet feet"
        } else {
            val miles = distanceMeters * METERS_TO_MILES
            val roundedTenths = (miles * 10.0).roundToInt().coerceAtLeast(1)
            val spokenMiles = String.format(Locale.US, "%.1f", roundedTenths / 10.0)
            "for $spokenMiles miles"
        }
    }
    return if (distanceMeters < 1000.0) {
        val roundedMeters = ((distanceMeters / 25.0).roundToInt() * 25).coerceAtLeast(50)
        "for $roundedMeters meters"
    } else {
        val kilometers = distanceMeters / 1000.0
        val spokenKilometers =
            if (kilometers >= 10.0) {
                kilometers.roundToInt().toString()
            } else {
                String.format(Locale.US, "%.1f", kilometers)
            }
        "for $spokenKilometers kilometers"
    }
}

private fun spokenTurnAction(command: RouteInstructionCommand): String =
    when (command) {
        RouteInstructionCommand.SLIGHT_LEFT -> "slight left"
        RouteInstructionCommand.LEFT -> "turn left"
        RouteInstructionCommand.SHARP_LEFT -> "sharp left"
        RouteInstructionCommand.SLIGHT_RIGHT -> "slight right"
        RouteInstructionCommand.RIGHT -> "turn right"
        RouteInstructionCommand.SHARP_RIGHT -> "sharp right"
        RouteInstructionCommand.CONTINUE -> "continue"
        RouteInstructionCommand.FINISH -> "finish"
    }

private fun spokenDistancePrefix(
    distanceMeters: Double,
    isMetric: Boolean,
): String? {
    if (!distanceMeters.isFinite()) return null
    if (distanceMeters < 15.0) return "now"
    if (!isMetric) {
        val feet = distanceMeters * METERS_TO_FEET
        return if (feet < FEET_PER_HALF_MILE) {
            val roundedFeet = ((feet / 25.0).roundToInt() * 25).coerceAtLeast(50)
            "in $roundedFeet feet"
        } else {
            val miles = distanceMeters * METERS_TO_MILES
            val roundedTenths = (miles * 10.0).roundToInt().coerceAtLeast(1)
            val spokenMiles = String.format(Locale.US, "%.1f", roundedTenths / 10.0)
            "in $spokenMiles miles"
        }
    }
    val roundedMeters = ((distanceMeters / 5.0).roundToInt() * 5).coerceAtLeast(15)
    return "in $roundedMeters meters"
}

private const val VOICE_FOLLOWING_TURN_MAX_GAP_METERS = 120.0
internal const val VOICE_CONTINUE_STRAIGHT_MIN_DISTANCE_METERS = 450.0
private const val METERS_TO_FEET = 3.28084
private const val METERS_TO_MILES = 0.000621371
private const val FEET_PER_HALF_MILE = 2_640.0
private const val VOICE_OFF_ROUTE_MIN_REPEAT_SECONDS = 15
private const val TTS_SPEECH_RATE = 0.92f
