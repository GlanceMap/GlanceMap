# Trail Core Architecture

`:trailcore` is a Kotlin/JVM module for trail calculations that must behave consistently on the
phone and watch. It deliberately has no Android, Compose, Mapsforge, or MapLibre dependency.

## Current Scope

The first extraction is `com.glancemap.trailcore.geo`:

- `GeoPoint`: a validated, platform-neutral coordinate.
- distance and initial-bearing calculations.
- cumulative route distances.
- route projection with continuity-aware matching at route crossings.

`com.glancemap.trailcore.profile` now adds:

- `TrailPoint` and `TrailRouteProfile`, which preserve GPX track-segment boundaries.
- distance, ascent, descent, and a transparent initial hiking-time estimate.
- a distance-anchored time window for planning the “Next 30 minutes” briefing.

The watch guidance layer adapts its Mapsforge `LatLong` values at its boundary and delegates
geometry calculations to `:trailcore`. The companion uses the profile layer while importing its
private GPX route library.

## Ownership Rules

- Add reusable geometry, GPX analysis, ETA, guidance, and trail-intelligence calculations here.
- Keep map rendering, Android services, storage, Data Layer transport, notifications, and UI in
  their platform feature modules.
- Do not expose Mapsforge or MapLibre types from this module.
- Keep public units explicit in names, for example `distanceMeters` and `initialBearingDegrees`.
- Preserve existing behavior with focused unit tests whenever extracting watch logic.

## Planned Extensions

The next additions should define platform-neutral GPX enrichment, shared guidance state, and the
inputs needed for live “Next 30 minutes” and turnaround advice. Phone and watch adapters remain
responsible for their own file I/O, storage, and platform lifecycles.
