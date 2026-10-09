# Compass Module Architecture

This document is the contributor entry point for watch compass behavior.

## Scope

Main source roots:

- `app/src/main/java/com/glancemap/glancemapwearos/domain/sensors`
- `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/navigate/effects/NavigateCompassEffects.kt`
- `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/settings/CompassSettingsScreen.kt`

This module owns:

- heading source selection (`TYPE_HEADING`, `ROTATION_VECTOR`, `MAGNETOMETER` fallback),
- north reference handling (`TRUE` vs `MAGNETIC`),
- heading smoothing/jump guard/accuracy inference,
- magnetic interference detection,
- low-power cadence coordination from navigation lifecycle.

## Runtime Pipeline

1. `NavigateCompassEffects` starts/stops compass based on lifecycle, ambient state, and nav mode.
2. `CompassViewModel` forwards calls and exposes state flows from `CompassManager`.
3. `CompassManager` resolves sensor pipeline and registers listeners at current rate mode.
4. Sensor callbacks produce raw azimuth/heading, then apply display rotation + north reference handling.
5. Smoothing and quality logic computes final heading, accuracy, source status, and interference state.
6. Navigation UI consumes heading and quality state to rotate map/cone behavior.

## Reliability Contracts

- Sensor and Google fused samples carry both monotonic source-measurement time and callback-arrival
  time. Freshness is based on source time; callback time is diagnostic context only.
- Fused samples are accepted only when their source timestamps are strictly newer than the last
  accepted sample, are not stale, and are not in the future of callback elapsed time. Duplicate,
  out-of-order, stale, and future callbacks are recorded but cannot refresh freshness, integrity,
  or rendering. An accepted sample expires at its source time plus the stale window.
- An unusable Fused sample still belongs to the active request and advances source identity, but it
  does not refresh confirmed usable-sample freshness or clear the repeated-unusable fallback
  streak. A usable sample clears that streak and restores the normal confirmed-sample path.
- Every published heading carries a sequence identity and provider generation when available.
  Reference diagnostics reject marks whose provider sample and rendered sample do not share the same
  provenance.
- SensorManager registration generation is carried through raw samples and smoothing mutations.
  Re-registration is serialized with state mutation, so an old callback cannot consume bootstrap
  budgets, update inferred accuracy, reset new smoothing, or publish under the new registration.
  Each Fused integrity-monitor registration has its own listener, fixed callbacks, and rotation
  scratch state. Old events waiting across a restart are rejected using their original generation;
  events already past that check still belong to the original adapter request.
- During acquisition, one-shot or implausible absolute changes remain held. When no usable relative
  witness exists, two coherent, nonzero, bounded same-direction absolute steps allow the existing
  bounded correction path to follow genuine movement at normal or low-power cadence. Actual
  independent contradictions still block this path; the absolute-only disagreement threshold does
  not establish an independent contradiction. This responsiveness path does not establish trust or
  corroboration, and unchanged samples still use the stable acquisition window.
- With fresh, unsuppressed relative evidence, acquisition history uses its configured startup
  duration plus the existing sample-retention slack. It retains the minimum sample count at lower
  sensor rates. Acquisition without that evidence, tracking, and recovery keep the longer history;
  faster corroborated acquisition does not shorten magnetic or disagreement recovery.
- A contradictory fused jump is quarantined at the existing confidence-dependent disagreement
  threshold. Strong provider confidence cannot override independent contradictory motion. Repeating
  the same suspect sample or entering magnetic degradation does not release its preserved anchor;
  the degraded path uses the same jump guard as tracking. Bounded degraded motion remains available
  when no suspect jump is held. A meaningful relative correction must corroborate the provider before
  a held heading can move. A corrected provider may also clear quarantine by returning near the
  preserved pre-quarantine heading while the watch is stationary; quarantine is never trusted.
- The optional Compass Deep Trace stores a bounded ordered decision-event ring. It records provider
  timing, integrity decisions, held output, render provenance, and explicit user reports while the
  trace is active. Consecutive unchanged render records are coalesced. The first quarantine or
  unresolved independent disagreement automatically preserves the currently retained pre-history
  plus a bounded two-second post-marker tail, labelled `automatic_integrity_incident`, even after
  the live ring rotates. The first explicit `heading_looks_wrong` report takes priority over an
  automatic capture and preserves history around the user-reported failure instead. Later reports
  do not replace that first explicit report. Capture remains opt-in and bounded.

Navigation enters panning only after touch movement exceeds Android's configured touch slop.
Sub-threshold finger jitter before a blocked multi-touch gesture does not disable compass-follow.
Intentional dragging still enters panning and requires recentering to restore follow.

SensorManager fallback silence becomes stale after the documented source-time window. Stale output
cannot drive map-follow rotation. Severe F3 contradictions are held by the integrity engine before
navigation sees them, while fresh renderable degraded or untrusted Fused output may still drive
bounded map-follow motion. The selected Fused provider keeps its normal green cone for ordinary
weak confidence; cone color is not a Fused trust indicator.
During wake, missing magnetic evidence may allow the existing bounded timeout to release a degraded
target, but it never becomes GOOD or trusted; active magnetic interference and recovery obligations
continue to hold the target. The recovery obligation is armed before held-output checks, and only
the exact unavailable-evidence timeout path may release an otherwise held degraded render.

Significant Fused provider-step diagnostics distinguish acquisition-held output, quarantine,
degraded or unresolved output, and accepted movement with actual relative corroboration. An
unsuppressed witness alone is not reported as corroboration.

## Temporary motion during magnetic holds

`NavigateMagneticMotionFallback` preserves the visible map angle when the existing wake gate
blocks Google Fused absolute heading for magnetic interference, or the integrity engine holds a
quarantined contradictory jump, then applies only fresh game-rotation turns. Current magnetic
evidence beyond the engine's hard validity limits also starts backup during active navigation,
even after the wake gate has settled. The engine owns this severity classification and publishes
it with the render state; stale or unavailable magnetic evidence cannot retain the severe flag.
Mild magnetic warnings still allow absolute heading movement. Jump holds use the engine's explicit
preserved-jump-anchor flag. General quarantine or a weak confidence/disagreement label is insufficient.
It requires a previously accepted stable absolute anchor; a disturbed cold start cannot invent
north. It never changes the absolute provider heading, uncertainty, trust, or quarantine decision.
Relative samples carry source time, request generation, horizontal projection, and display rotation.
Missing, old, future, mismatched, steeply tilted, or implausible samples cannot drive rotation.
Gaps, registrations, display-frame changes, and wake/panning sessions re-establish the relative
origin at the current visible heading without applying missed turns. The motion budget does not
renew on wake and expires after 60 seconds without absolute recovery. Expiry stops relative motion;
it cannot itself hide the cone.

The cone hides after 500 ms of a continuous magnetic wake hold, jump quarantine, or severe magnetic
episode, retaining the location dot. Brief spikes and ordinary weak confidence do not hide it.
Once the navigation gate allows a fresh, stable, unheld absolute heading, navigation reconnects
with a four-degree visual step cap.
Recovery in the same interactive session requires one second of stable tracking. A later wake uses
the existing wake gate's validation without adding another recovery delay for the old episode.
A new hold during reconnection requires the stable recovery window again.
Historical unresolved-disagreement metadata still qualifies provider trust, but cannot prolong
navigation backup after the actual hold releases. Cached samples and current jump holds cannot
establish recovery.
An already hidden cone returns only when the visible heading is within five degrees of that target.
There is no additional uncertainty message. Existing north-up and panning behavior is preserved.
Witness suppression caused by disagreement with the disturbed absolute source does not itself
invalidate physical relative motion; freshness, projection, frame continuity, and plausible-step
checks remain mandatory. This is temporary estimated orientation, never proof of north accuracy.

Deep Trace schema 5 records the actual relative sample/time/generation/frame used by each coasting
render, cone suppression, and mode transitions. Capture remains opt-in and bounded.

## Ownership

- `SensorManagerOrientationProvider.kt`, `CompassHeadingProcessor.kt`, `CompassAlgorithms.kt`,
  `CompassManager.Support.kt`, and `CompassRuntime.kt`
  - Sensor registration, source pipeline resolution, declination handling, smoothing, freshness,
    accuracy, and diagnostics inputs.

- `CompassViewModel.kt`
  - UI bridge to manager start/stop/settings actions.

- `NavigateCompassEffects.kt`
  - Compose lifecycle wiring and low-power mode transitions.

- `CompassSettingsScreen.kt` and `CompassRecalibrationDialog.kt`
  - User-facing compass configuration and recalibration trigger.

- `CompassHeadingSourceMode.kt` and `NorthReferenceMode.kt`
  - Configuration enums shared by settings, runtime, and diagnostics.

## Guardrails

- Keep Android sensor API handling inside the orientation-provider and compass-manager support
  files under `domain/sensors`.
- Keep Compose lifecycle side effects in `NavigateCompassEffects.kt`.
- Keep pure math helpers in testable `internal` functions.
- Any change affecting heading stability or power must include before/after evidence in PR.

## Tests

Current compass unit tests live under:

- `app/src/test/java/com/glancemap/glancemapwearos/domain/sensors`

When adjusting heading math or thresholds, update/add tests in:

- `CompassManagerMathTest.kt`
