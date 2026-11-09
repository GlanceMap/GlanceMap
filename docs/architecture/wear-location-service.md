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

## Recording and guidance protection

`locationKeepAliveNotificationMode` keeps the location foreground service active for recording,
recording auto-pause GPS monitoring, combined recording/guidance, and standalone guidance
whenever tracking and the existing interactive/background GPS demand permit location work.
This session protection is independent of the manual **Stay** preference. Turning Stay off
cannot demote a location service that an active session still needs. Ending or manually
pausing a session restores the usual policy unless another session still requires GPS;
the saved Stay preference is never changed.

The existing location notification provides that foreground protection. No screen-on flag,
partial wake lock, location interval or GPS acceptance threshold is changed by this policy.
In the OnePlus OPWWE251 evening test on 2026-10-25, Stay was disabled before 72.519-second
and 51.400-second stored-point gaps. Reconnection overlapped those gaps, so foreground
retention fixes a demonstrated policy defect without proving it is their sole cause.
A comparable screen-off recording with Stay off remains required for physical validation.

## Recording source handover

Auto mode falls back from fused location to watch GPS when the nearby phone is
reported disconnected, and returns when it is reported connected again. The phone link
is polled (`NodeClient.connectedNodes`), not pushed, so a silent drop such as Bluetooth
turned off is noticed only at the next check. Switching backends removes the old
request before the new one delivers, so every switch loses fixes until the new backend's
first fix. In the OnePlus OPWWE251 evening capture on 2026-10-25, the disconnect was
noticed 13 s after the Data Layer peer dropped, and watch GPS delivered 28 ms after the
switch; in between, fused location reported the watch's fixed 125 m value and those fixes
were dropped by the 50 m fused acceptance limit. The 72.5 s gap overlapping the reconnect
was mostly silence before and after the switch with the screen off and Stay off (34 s
on watch GPS, then 39 s on fused until the screen woke), not the switch itself.

## Watch-chip placeholder accuracy

Some watch GNSS chips report one fixed horizontal accuracy instead of a measurement
(OnePlus OPWWE251: 125 m, with 250 m vertical and 25 m/s speed placeholders). The known
125 m ± 3 m value is recognised immediately. Because another brand may use a different
number, `WatchGpsConstantAccuracy` also learns a value from raw direct watch-GPS (`gps`
provider) fixes, before any acceptance filter: at least 5 consecutive fixes with the same
accuracy (±0.05 m), at least 50 m, spread over at least 20 s. The learned value lasts for the
process lifetime. Fused fixes are not learned from, because a coarse network fix can repeat
while standing still indoors. A recognised placeholder is:

- accepted from watch GPS, and from fused location while the phone is not confirmed
  connected, up to 250 m instead of the 130 m / 50 m limits;
- excluded from the degraded-watch-GPS streak, and filtered by recording with the assumed
  18 m accuracy (`suspect_constant_watch_gps` in GPX);
- a trigger, when it arrives through fused location while the phone still reads connected,
  for an immediate phone-link check (at most every 3 s). Fused location falls back to the
  watch chip when the phone is gone, so this usually precedes the switch to watch GPS. In the
  2026-11-09 capture, such a fix arrived 9 s after Bluetooth was turned off and was dropped
  while the cached state still read connected.

Learning has only been checked against the OnePlus 125 m behaviour; no other brand's
watch-GPS data is available yet.

During an active recording (including automatic pause), the phone link is rechecked
every 10 s instead of 30 s, so a silent disconnect fails over to watch GPS sooner; the
screen-off monitor still runs every 15 s. In the 2026-11-09 capture the switch came 10 s
after Bluetooth was turned off. A phone reconnect returns to fused location at the next
check, for battery life and accuracy, as outside recordings; with Watch GPS only enabled
the phone is never used. The extra checks' battery cost has not been measured.

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
