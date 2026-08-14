# Licenses Folder

This folder tracks third-party licensing and attribution for `GlanceMap` (watch app + companion app).

## Files

- `SAFETY_AND_LIMITATIONS.md`
  - User-facing warning about map/theme/data limitations and personal responsibility.
- `CREDITS_AND_THANKS.md`
  - User-friendly credits for major projects/services.
- `AI_ACKNOWLEDGEMENT.md`
  - Project statement on AI-assisted development, creator concerns, and transparency references.
- `COMPLIANCE_STATUS.md`
  - Release checklist with blockers/pending legal items.
- `THIRD_PARTY_NOTICES.md`
  - Open-source components used by this project (grouped by project/family), with license type and source links.
- `DATA_AND_ASSET_ATTRIBUTION.md`
  - Attribution and usage notes for map/theme/data/icon assets and imported POI data.
- `OPENHIKING_THEME.md`
  - Dedicated note for the bundled OpenHiking theme snapshot and approval/license status.
- `FRENCH_KISS_THEME.md`
  - Dedicated note for the bundled French Kiss theme snapshot sourced from XCTrack and its approval status.
- `TIRAMISU_THEME.md`
  - Dedicated note for the bundled Tiramisu theme snapshot and its noncommercial compatibility status.
- `HIKE_RIDE_SIGHT_THEME.md`
  - Dedicated note for the bundled Hike, Ride & Sight theme snapshot and its approval/noncommercial compatibility status.
- `VOLUNTARY_THEME.md`
  - Dedicated note for the bundled Voluntary theme snapshot and its approval/noncommercial compatibility status.
- `OS_MAP_THEME.md`
  - Dedicated note for the bundled OS Map theme snapshot and its noncommercial compatibility status.
- `SERVICE_TERMS_AND_API_USAGE.md`
  - Service/API usage terms and limits (OpenAndroMaps, Refuges.info, Overpass, Google Play services).
- `COMPANION_EXTERNAL_SOURCES.md`
  - External websites shown in companion app for map/GPX downloads.
- `RUNTIME_DEPENDENCIES_RESOLVED.txt`
  - Resolved runtime Maven coordinates from Gradle dependency graphs (`app` and `glancemapcompanionapp`, `releaseRuntimeClasspath`).

## Update Process

1. Refresh runtime dependency inventories:
   - `./gradlew --no-daemon :app:dependencies --configuration releaseRuntimeClasspath`
   - `./gradlew --no-daemon :glancemapcompanionapp:dependencies --configuration releaseRuntimeClasspath`
2. Regenerate `RUNTIME_DEPENDENCIES_RESOLVED.txt` (same parsing approach used in this commit).
3. Re-check license and usage-term changes in upstream repositories/services for any new dependency groups.
4. Update all user-facing and compliance docs listed above.
5. Keep `COMPLIANCE_STATUS.md` current before each public release.

## Notes

- Google Play services artifacts are runtime dependencies but are not open-source artifacts in the same way as Apache/LGPL OSS projects. They are covered by Android/Google SDK terms.
- OpenAndroMaps/Elevate redistribution terms must be reviewed carefully for every public release, including written-permission requirements where applicable.
