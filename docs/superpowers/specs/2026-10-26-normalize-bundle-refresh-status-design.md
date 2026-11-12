# Normalize Bundle Refresh Status Design

## Intent

Remove the user-visible `Repair needed` state for installed offline bundles. A bundle whose local
file is missing or fails the existing integrity validation must instead appear as a normal available
refresh, so a user-initiated refresh still re-downloads only the affected files.

## Goals

- Remove `Repair needed` wording and warning styling from bundle rows and refresh dialogs/summaries.
- Preserve the existing file-level force list: locally damaged map, POI, routing and DEM files are
  refreshed without redownloading healthy components.
- Preserve the known-missing DEM-tile marker exclusion. A tile previously recorded as unavailable
  must not create an available-refresh result.

## Non-goals

- Do not change integrity validation, network metadata requests, download sequencing or retry policy.
- Do not add concurrency, cache metadata, or retune network timeouts.
- Do not alter permanent missing-tile handling.

## Approaches considered

1. Hide only the installed-row warning. This leaves `Repair needed` in update dialogs and summaries,
   so it does not remove the status consistently.
2. Convert a local repair result to `UPDATE_AVAILABLE`, retaining `repairFileNames` as an internal
   force list. This removes the status everywhere while preserving targeted recovery. **Selected.**
3. Stop collecting local health altogether. This would make missing content invisible until a later
   failure and would discard targeted recovery.

## Design

Remove `REPAIR_NEEDED` from `OamBundleUpdateStatus`. The update-status decision returns
`UPDATE_AVAILABLE` when either remote metadata changed or local integrity identified affected
files. `repairFileNames` remains internal data consumed by `refreshForces`, where it is combined
with changed remote files.

The installed-bundle row always uses its normal subtitle and non-warning styling. The update
dialog and multi-bundle summary therefore use their existing normal-update paths. Local health
continues to skip DEM tiles with an existing known-missing marker before it creates the force list.

## Validation

Unit tests prove that a local integrity result becomes `UPDATE_AVAILABLE`, its file names still
force only the affected refresh components, and known-missing DEM tiles remain absent from remote
requests. Existing refresh-summary tests cover the normal-update dialog/summary path.
