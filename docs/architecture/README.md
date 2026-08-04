# Architecture Index

This folder documents the watch-side architecture and major runtime flows.

## Documents

- `wear-navigation.md`
  - Navigation screen orchestration, map overlays/effects, and state flow.

- `wear-location-service.md`
  - Watch location service, update policy, activity tracking, telemetry, notifications.
  - High-level service/runtime flow and package boundaries.

- `wear-transfer-service.md`
  - Watch transfer handling (Data Layer + channel + LAN HTTP), session state, lock policy.

- `trailcore.md`
  - Shared, platform-neutral trail geometry boundary used by the phone and watch.

- `active-hike-sync.md`
  - Watch-owned live navigation snapshot shared with the companion dashboard.

## Location Contributor Docs

- `docs/location/ARCHITECTURE.md`
  - Contributor entry point for location package ownership and runtime pipeline.

- `docs/location/CONTRIBUTING.md`
  - Location-specific validation commands and PR evidence checklist.

- `docs/location/THRESHOLDS.md`
  - Rationale for battery/accuracy-sensitive constants and change template.

## Compass Contributor Docs

- `docs/compass/ARCHITECTURE.md`
  - Contributor entry point for compass runtime flow and ownership boundaries.

- `docs/compass/CONTRIBUTING.md`
  - Compass-specific validation checklist and PR evidence expectations.

- `docs/compass/THRESHOLDS.md`
  - Rationale for heading-quality and power-sensitive compass constants.

## Source Anchors

- Watch app module: `app/src/main/java/com/glancemap/glancemapwearos`
- Core services: `app/src/main/java/com/glancemap/glancemapwearos/core/service`
- Feature UI: `app/src/main/java/com/glancemap/glancemapwearos/presentation/features`
- Shared transfer contract: `transfercontract/src/main/kotlin/com/glancemap/shared/transfer`
- Shared trail core: `trailcore/src/main/kotlin/com/glancemap/trailcore`

## Contribution Rule

If you change architecture (new package boundaries, major flow changes, protocol responsibilities), update the matching document in this folder in the same PR.
