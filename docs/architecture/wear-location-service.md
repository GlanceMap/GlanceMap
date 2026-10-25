# Wear Location Service Architecture

This document describes the watch location stack in `:app`.

## Scope

Main service:

- `app/src/main/java/com/glancemap/glancemapwearos/core/service/location/service/LocationService.kt`

The service is responsible for:

- lifecycle-aware location updates,
- foreground pinning (`keep app open`),
- immediate high-accuracy bursts,
- battery-aware request reconfiguration.

## Split Components

The service package is intentionally split into focused helpers:

- `LocationRequestCoordinator.kt`
  - Computes and applies update requests.
  - Handles no-permission/no-request transitions.

- `ImmediateLocationCoordinator.kt`
  - Manages immediate high-accuracy burst lifecycle and `getCurrentLocation`.

- `LocationCallbackProcessor.kt`
  - Processes callback batches and candidate acceptance path.

- `SelfHealFailoverCoordinator.kt`
  - Handles self-heal and auto-fused failover state machine.

- `LocationServiceTelemetry.kt`
  - Owns debug counters, summary windows, and telemetry log formatting.

## Runtime Flow

1. `LocationService` observes settings and bind state.
2. `LocationRequestCoordinator` resolves desired request config.
3. Service applies request config through selected gateway (`fused` or `watch GPS`).
4. On each accepted fix:
   - activity state may transition,
   - telemetry counters update,
   - downstream UI-facing state (`currentLocation`) is emitted.
5. Foreground pinning toggles notification behavior via `LocationNotificationFactory`.

## Global diagnostics capture

The application scope observes the persisted capture enable/mode and owns the global DebugTelemetry
buffer. FULL capture works for downloads and settings without creating or binding LocationService;
BATTERY mode and disabled capture do not enable that event buffer. Export freezes the buffer until
the persisted disabled setting arrives, then a later capture can start independently of GPS.
LocationService still owns its location-specific diagnostics, counters and energy sampling, but
cannot enable or disable the global buffer when its lifecycle or settings collector changes.

## Contributor Rules

- Keep `LocationService.kt` orchestration-focused.
- Add request-application behavior to `LocationRequestCoordinator.kt`.
- Add immediate burst behavior to `ImmediateLocationCoordinator.kt`.
- Add callback acceptance behavior to `LocationCallbackProcessor.kt`.
- Add failover/self-heal behavior to `SelfHealFailoverCoordinator.kt`.
- Add debug counters/log shaping to `LocationServiceTelemetry.kt`.

## Additional Contributor Docs

- `docs/location/ARCHITECTURE.md`
- `docs/location/CONTRIBUTING.md`
- `docs/location/THRESHOLDS.md`
