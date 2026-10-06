# Contributing To Compass

This guide is specific to watch compass behavior.

## Local Validation

Run before opening a compass PR:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "*domain.sensors*"
```

If you change navigation integration around compass start/stop behavior, also run:

```bash
./gradlew :app:testDebugUnitTest --tests "*presentation.features.navigate*"
```

For generation, freshness, or wake-gate changes, include a production-path regression that crosses
the registration or wake boundary. Do not replace it with a standalone generation comparison or a
synthetic gate-only held flag; the sample must flow through the adapter/processor/engine contract.

## Manual Device Checklist

Sanity check on watch:

- navigation screen open/close lifecycle,
- `COMPASS_FOLLOW` vs `NORTH_UP_FOLLOW` mode transitions,
- ambient on/off transitions,
- offline mode transitions,
- heading source mode switch (`AUTO`, `TYPE_HEADING`, `ROTATION_VECTOR`, `MAGNETOMETER`),
- north reference switch (`TRUE`, `MAGNETIC`),
- recalibration trigger behavior.
- moving acquisition and stationary wake, including a wake during wrist tilt;
- magnetic interference, magnetic-feed loss, and degraded recovery;
- custom sensor/source combinations where available.

## Where To Change Code

- Sensor pipeline, smoothing, quality, declination:
  - `SensorManagerOrientationProvider.kt`, `CompassHeadingProcessor.kt`, `CompassAlgorithms.kt`,
    `CompassManager.Support.kt`, `CompassRuntime.kt`

- Compass lifecycle and low-power orchestration in navigation:
  - `NavigateCompassEffects.kt`

- User settings and recalibration entry points:
  - `CompassSettingsScreen.kt`, `CompassRecalibrationDialog.kt`

- ViewModel bridge:
  - `CompassViewModel.kt`

## PR Expectations

For compass PRs, include:

1. Changed files and why.
2. Expected impact (`heading stability`, `responsiveness`, `battery`, or combinations).
3. Validation commands + results.
4. Device model + Wear OS version used for manual checks.
5. `CompassTelemetry` snippet when behavior changed.

## Suggested Compass Report Fields

Use these fields in issues/PRs:

- `requested` (requested source mode),
- `src` (active source),
- `ref` (north reference),
- `mode` (`HIGH` or `LOW` sensor rate),
- `acc` (combined heading accuracy),
- `magInterf` (magnetic interference flag),
- nav mode (`COMPASS_FOLLOW`, `NORTH_UP_FOLLOW`, `PANNING`).
