# Location Module Architecture

This document is the contributor entry point for watch location behavior.

## Scope

Main source root:

- `app/src/main/java/com/glancemap/glancemapwearos/core/service/location`

This module owns:

- source selection (`AUTO_FUSED` vs `WATCH_GPS`),
- request cadence and runtime modes,
- callback candidate acceptance and filtering,
- immediate burst requests,
- GPS signal diagnostics and telemetry.

## Runtime Pipeline

1. `LocationService` receives lifecycle/settings/bind events.
2. `LocationRequestCoordinator` computes and applies update requests.
3. Gateway emits callback batches (`FusedLocationGateway` or `WatchGpsLocationGateway`).
4. `LocationCallbackProcessor` validates source + acceptance policy and forwards accepted fixes.
5. `LocationEngine` updates signal state, activity state, and output filtering.
6. Service publishes `currentLocation` and signal snapshots.

## Service Package Ownership

- `LocationService.kt`
  - Android service lifecycle, wiring, bind behavior, foreground notification behavior.

- `LocationRequestCoordinator.kt`
  - Applies/removes location update requests.
  - Handles no-permission/no-request states and request telemetry.

- `ImmediateLocationCoordinator.kt`
  - High-accuracy burst lifecycle and `getCurrentLocation` flow.

- `LocationCallbackProcessor.kt`
  - Callback batch normalization and per-candidate acceptance path.

- `SelfHealFailoverCoordinator.kt`
  - Interactive self-heal and auto-fused failover state machine.

- `GnssDiagnosticsCoordinator.kt`
  - GNSS status callback collection for debug telemetry.

## Guardrails

- Keep policy decisions in `policy/` (`LocationUpdatePolicy`, `LocationFixPolicy`).
- Keep Android API request details in `adapters/`.
- Keep service coordinators side-effect focused.
- Keep telemetry formatting in `LocationServiceTelemetry`.
- GPS phone availability means a directly nearby Data Layer node (`Node.isNearby`), not a
  remote node reachable through the cloud. Remote-only connectivity must leave onboard-GPS
  fallback available. Existing nearby-phone recovery gates and location-quality thresholds remain.
- If a behavior change affects battery or accuracy, include before/after evidence in PR.

## Tests

Current location tests live under:

- `app/src/test/java/com/glancemap/glancemapwearos/core/service/location`

Preferred subfolders:

- `policy/`, `filter/`, `model/`, `runtime/`, `service/`.

When adding service behavior, prefer adding tests in `service/` with one coordinator per test file.
