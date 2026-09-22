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
  accepted sample. Duplicate and out-of-order callbacks are recorded but cannot refresh freshness,
  integrity, or rendering.
- Every published heading carries a sequence identity and provider generation when available.
  Reference diagnostics reject marks whose provider sample and rendered sample do not share the same
  provenance.
- A weak, contradictory fused jump is quarantined. Repeating the same contradictory sample does not
  release it; a meaningful relative correction must corroborate the provider before the held heading
  can move.
- The optional Compass Deep Trace stores a bounded ordered decision-event ring. It records provider
  timing, integrity decisions, held output, render provenance, and explicit user reports while the
  trace is active. Consecutive unchanged render records are coalesced.

SensorManager fallback silence becomes stale after the documented source-time window, and stale or
untrusted fused output cannot drive map-follow rotation or display a green compass-quality state.

## Ownership

- `CompassManager.kt`
  - Sensor registration, source pipeline resolution, declination handling, smoothing, diagnostics.

- `CompassViewModel.kt`
  - UI bridge to manager start/stop/settings actions.

- `NavigateCompassEffects.kt`
  - Compose lifecycle wiring and low-power mode transitions.

- `CompassSettingsScreen.kt` and `CompassRecalibrationDialog.kt`
  - User-facing compass configuration and recalibration trigger.

- `CompassHeadingSourceMode.kt` and `NorthReferenceMode.kt`
  - Configuration enums shared by settings, runtime, and diagnostics.

## Guardrails

- Keep Android sensor API handling inside `CompassManager.kt`.
- Keep Compose lifecycle side effects in `NavigateCompassEffects.kt`.
- Keep pure math helpers in testable `internal` functions.
- Any change affecting heading stability or power must include before/after evidence in PR.

## Tests

Current compass unit tests live under:

- `app/src/test/java/com/glancemap/glancemapwearos/domain/sensors`

When adjusting heading math or thresholds, update/add tests in:

- `CompassManagerMathTest.kt`
