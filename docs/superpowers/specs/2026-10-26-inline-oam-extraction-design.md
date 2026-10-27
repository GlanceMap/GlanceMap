# Inline OAM Extraction Design

## Intent

Prefer slow, resumable foreground-service extraction to a JobScheduler device-state stop on the
OnePlus OPWWE251. Keep the bounded Downloads-screen hold as an optional aid when the user has
returned to the app.

## Decision

Run the existing map and POI ZIP writer directly in the download foreground-service coroutine on
all API levels. Remove the API-31+ expedited `JobService` bridge and its manifest registration.
The persisted plan, retained ZIPs, atomic writer, single extraction lane, pause/cancel semantics,
network handoff, notifications and `isExtracting` lifecycle remain unchanged.

## Trade-off

When OnePlus Balance Mode suppresses the application wake lock, direct work can progress very
slowly or wait until the device wakes; it must not be converted into an automatic retry loop. This
trades the observed three-minute JobScheduler `DEVICE_STATE` failure for the earlier slow-continuation
behaviour. The screen-on assist remains bounded to a resumed Downloads screen and actual extraction.

## Out of Scope

No battery exemption, wake-lock reacquisition, Activity launch, forced screen wake, ZIP tuning,
or average-speed changes. The separate user-facing guidance copy will be proposed for approval and
implemented only afterward.

## Validation

Existing extraction-lifecycle tests must continue to prove cleanup on completion, cancellation and
failure. Compile/lint must confirm the removed JobService is no longer declared or referenced.
Real-watch validation must show that a screen-off, unplugged Bayern extraction no longer receives a
JobScheduler stop; it may take longer until the watch becomes awake again.
