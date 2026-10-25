# Wear Navigation Architecture

This document describes the watch-side navigation feature in `:app`.

## Goal

`NavigateScreen` is the entry experience on Wear. It combines:

- live location tracking,
- compass-based orientation,
- offline map rendering,
- GPX overlays and inspection,
- battery-sensitive behavior (ambient mode, tracking controls).

## Package Layout

Main package:

- `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/navigate`

Key files:

- `NavigateScreen.kt`: top-level orchestration and UI wiring.
- `NavigateContent.kt`: on-screen controls and `MapView` interaction layer.
- `MapOverlays.kt`: GPX lines/markers and inspection overlays.
- `NavigateEffects.kt`: orientation/rotation map effects.
- `effects/NavigateCompassEffects.kt`: compass lifecycle + low-power mode coordination.
- `effects/NavigateCalibrationEffects.kt`: calibration prompt state machine.
- `effects/NavigateLocationEffects.kt`: location fusion, marker updates, GPS indicator state.
- `NavigateViewModel.kt`: UI mode/zoom/calibration state.
- `LocationViewModel.kt`: binding bridge to `LocationService`.
- `motion/LocationFusionEngine.kt`: extrapolation and smoothing engine.

## Data Flow

1. `NavigateScreen` subscribes to settings and feature state (`SettingsViewModel`, `NavigateViewModel`, `GpxViewModel`, `MapViewModel`).
2. `LocationViewModel` exposes location updates from `LocationService`.
3. `effects/NavigateLocationEffects.kt` fuses GPS + heading and drives marker/map centering state.
4. `MapOverlays.kt` renders GPX + inspection UI on top of `MapView`.
5. `NavigateContent.kt` handles user gestures (pan/zoom/recenter/menu/toggles) and sends intent callbacks to viewmodels.

Map focus mode is retained by the Navigate destination's `NavigateViewModel`, so returning from the menu restores the focused map. Full-screen recording and guidance dashboards temporarily take visual precedence without clearing map focus; the mode ends only when the user explicitly exits it.

## Responsibilities

`NavigateScreen.kt` should remain an orchestrator:

- collect state,
- call side-effect composables,
- pass final state/callbacks into `NavigateContent`.

Effect files under `navigate/effects` should remain focused and side-effect-only.  
Domain math and geometry should stay outside UI composables where possible (`motion/`, overlay helpers).

## Extension Guidelines

- Add new side-effect behavior to `navigate/effects` instead of growing `NavigateScreen.kt`.
- Keep map drawing code in `MapOverlays.kt` (or dedicated overlay helper files).
- Avoid service calls directly from UI widgets; route through viewmodels/callbacks.
- Maintain compile-time safety by centralizing route/state constants and avoiding string duplication.

## Additional Contributor Docs

- `docs/compass/ARCHITECTURE.md`
- `docs/compass/CONTRIBUTING.md`
- `docs/compass/THRESHOLDS.md`
