# Trail Core Architecture

`:trailcore` is a Kotlin/JVM module for trail calculations that must behave consistently on the
phone and watch. It deliberately has no Android, Compose, Mapsforge, or MapLibre dependency.

## Current Scope

The first extraction is `com.glancemap.trailcore.geo`:

- `GeoPoint`: a validated, platform-neutral coordinate.
- distance and initial-bearing calculations.
- cumulative route distances.
- route projection with continuity-aware matching at route crossings.

The watch guidance layer adapts its Mapsforge `LatLong` values at its boundary and delegates these
calculations to `:trailcore`. The companion depends on the same module so route planning can use
the exact calculations when its route library is introduced.

## Ownership Rules

- Add reusable geometry, GPX analysis, ETA, guidance, and trail-intelligence calculations here.
- Keep map rendering, Android services, storage, Data Layer transport, notifications, and UI in
  their platform feature modules.
- Do not expose Mapsforge or MapLibre types from this module.
- Keep public units explicit in names, for example `distanceMeters` and `initialBearingDegrees`.
- Preserve existing behavior with focused unit tests whenever extracting watch logic.

## Planned Extensions

The next additions will define platform-neutral GPX route/profile models, route windows for
“Next 30 minutes”, and shared guidance state. Phone and watch adapters will remain responsible for
their own file I/O and platform lifecycles.
