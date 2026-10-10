# Performance Benchmarking

Use these benchmarks before and after performance changes so improvements are measured on the same device and data set.

## Device Setup

- Use the same physical Wear OS device for baseline and after measurements.
- Use the same selected offline map, theme, GPX/POI overlays, and relief overlay setting.
- For relief measurements, make sure DEM files are installed and the relief overlay is enabled before running.
- Keep battery level, charging state, screen brightness, and thermal state as consistent as possible.

## Commands

Run all watch macrobenchmarks:

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

Run all phone companion macrobenchmarks:

```sh
./gradlew :companionmacrobenchmark:connectedBenchmarkAndroidTest
```

In Android Studio, use the `macrobenchmark` module/classes for the watch and the `companionmacrobenchmark`
module/classes for the phone. The watch benchmark APK declares the watch hardware feature so Android Studio
can install it on a Wear OS device.

Run only the phone companion idle baseline:

```sh
./gradlew :companionmacrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.glancemap.glancemapcompanionapp.macrobenchmark.PhoneCompanionBenchmarks#filePickerIdleBaseline
```

Run only the navigation recomposition/memory baseline:

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.glancemap.glancemapwearos.macrobenchmark.WatchNavigationBenchmarks#navigateActiveSessionBaseline
```

Run only the map load hot-path baseline:

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.glancemap.glancemapwearos.macrobenchmark.WatchNavigationBenchmarks#mapLoadHotPathBaseline
```

Run only the relief memory/DEM baseline:

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.glancemap.glancemapwearos.macrobenchmark.WatchNavigationBenchmarks#reliefPanMemoryBaseline
```

## Metrics To Compare

- Navigation: `navigateScreenRecomposeCount`, `navigateContentRecomposeCount`, heap/RSS memory. Run `navigateFrameTiming` separately if the device reports RenderThread slices.
- Map load: startup timing plus `mapLayerUpdateSumMs`, `mapFileOpenSumMs`, `renderThemeBuildSumMs`, `themeSelectionApplySumMs`, `dynamicThemeCreateSumMs`.
- Relief: `reliefDemReadDecodeSumMs`, `reliefTileBuildSumMs`, `reliefTileBuildCount`, heap/RSS memory. DEM input and sample decoding now stream together; the combined metric replaces the separate read/decode timings.

Watch benchmark JSON and trace artifacts are written under:

```text
macrobenchmark/build/outputs/connected_android_test_additional_output/
```

Phone companion benchmark artifacts are written under:

```text
companionmacrobenchmark/build/outputs/connected_android_test_additional_output/
```

Save the baseline artifacts before making optimization changes, then run the same benchmark command again after each coherent batch.

## Map Zoom and Configuration Regression Checks

Use a short Full diagnostics capture with recording and TBT off. Keep the map, rotation, theme,
DEM settings, and zoom gesture sequence matched to the baseline; downloaded data and viewport size
materially affect tile generation. The release Git SHA does not identify uncommitted APK changes.

- Reproduce the Bayern sequence: rapidly zoom 16 to 6, then return to 12 and stop. Compare the
  last zoom timestamp with the first drawable current-viewport tiles. `mapRenderer.zoomQueue`
  reports queue reprioritization on displayed zoom changes; pending jobs exclude running jobs.
  Existing worker limits and running jobs are retained. Startup prewarming stops after a zoom
  change, including when its delayed arm has not fired yet.
- Switch map/theme A to B to A. `mapRenderer.recreateTileCache` reports `diskRetained=true` when
  replacing a different configuration. Check the returned map and theme appearance for stale tiles.
  Explicit invalidation and rebuilding the same identity still purge rendered content. Map and
  theme signatures now include full-resolution modification times and file identity, so installing
  this build can require one initial render for the newly keyed buckets.
- Within one bundled theme, switch style or overlay configuration A to B to A. The return should
  report `generated_theme_cache_hit` and reuse A's tile-cache identity. Four generated XML variants
  are retained per theme using separate usage records; a fifth evicts the least recently used
  unselected variant. Confirm each configuration's appearance and that the current file is retained.
- After the first use of this fingerprint schema, reinstall/update an APK with unchanged bundled
  theme assets. `themeComposer.fingerprintForTheme` should report `asset_content` once per theme in
  the new process; resource preparation and generated XML should report cache hits, preserving the
  selected variant's tile-cache identity. The fingerprint uses APK entry names, sizes and checksums
  without extracting resource bytes. Changed XML, added/removed assets, or changed resource content
  must invalidate the theme. `bundle_fallback` retains installation-based invalidation when APK
  metadata is unreadable, required assets are missing, or split APK entries are ambiguous. Bump
  `THEME_COMPOSITION_VERSION` when XML composition or bundled rendering changes require invalidation.
  A genuinely uncached theme/viewport can still need tile rendering, especially during rapid zoom.
- During theme changes, compare `theme_viewport_ready` with `mapRenderer.visibleTiles` for the
  current layer. Cache notifications alone cannot complete this wait. The existing 4,500 ms
  appearance timeout remains bounded and reports `theme_viewport_timeout` if tiles are still absent;
  it is not proof of successful rendering. Rapid theme changes must cancel the previous wait.
- Enable relief with known DEM files and repeat pan/zoom. Download completion alone does not
  establish that `relief.demReadDecode` ran. Check terrain appearance and the retained elevation
  behavior before merging the streaming decoder changes.

Do not infer battery savings from these diagnostic captures. Compare battery separately with the
same workload and capture mode. Use the combined checks below for recording display and navigation
state delivery; measured frame-time and recomposition comparisons still require a watch profile.

## Final Performance Batch: PERF-001, PERF-004 and PERF-008

Map-file opening, persistent tile-cache opening/replacement, theme-file preparation and DEM
signature/coverage preparation now run on IO. Renderer configurations and layer updates remain
serialized across suspension; prepared resources are released if a request is cancelled before
delivery or the renderer is destroyed. Layer mutations remain on Main. Relief shutdown interrupts
its worker without waiting up to 200 ms on Main; late tile publication releases its bitmap rather
than adding it to a destroyed layer. Cache sizes, disk identities, retention, worker limits and
startup prewarming policy are unchanged.

Recording display preparation uses immutable views of canonical saved points. Sequential updates
rescan only the changed smoothing tail and rebuild safely after missed revisions or restoration.
One history layer draws those views on the Mapsforge drawing thread, replacing full-history
coordinate copying and Polyline synchronization on Main. The existing two-point hold, live tail,
manual pause bridges, segment boundaries, paints and projection are preserved. Display revision
metadata is neither persisted nor exported. Canonical point capture, smoothing, distance, cadence,
GPS source handling and GPX output are unchanged. Drawing still projects the displayed history;
this does not claim constant-time rendering for an arbitrarily long track.

Navigation collects a presentation flow that ignores retained-anchor coordinate-only updates.
The full state still retains every rendered position for wake handling. New fix timestamps,
accuracy, source epoch/mode, acceptance, startup state, zoom and navigation changes still reach the
screen. This removes a demonstrated state-delivery cause; it does not establish measured Compose
frame-time savings or eliminate every navigation recomposition.

Run **one combined Full diagnostics watch session** for this batch and the bundled theme fingerprint
fix, rather than a separate watch test for each edit:

1. With recording and TBT off, repeat rapid zoom and same-map screen wake, then force-close and
   reopen. Switch maps/themes A to B to A; also change a theme while another change is preparing.
   Confirm the final selection appears, controls remain responsive, and no stale/missing layer
   persists. Async map preparation spans include suspension; use a trace to distinguish elapsed
   preparation time from Main-thread CPU time. For the fingerprint fix, an APK update with
   unchanged assets should retain prepared resources and generated variants as described above.
2. With installed DEM data, enable hillshade and relief, pan/zoom, then disable them while tiles
   are building and return to the map. Confirm terrain and elevation still work when re-enabled,
   and teardown causes no visible stall or crash. Existing worker and cache budgets must remain.
3. Record a short moving route, pause/resume nearby and farther away, switch screens and wake the
   watch. Confirm the green saved history/live tail, pause bridges, segment gaps and marker remain
   correct; save and open the GPX. Unit fixtures cover all three smoothing modes, missed updates,
   restoration, a 20,000-point append and drawing equivalence. A long-recording profile remains
   useful for quantifying the benefit, rather than required to establish a claimed speedup.
4. Return to POI/GPX screens after successful loading in the same process, then reopen after the
   process restart. Reuse the cold/warm library checks below and confirm trust/zoom controls and
   retained positioning still behave correctly. Export the combined diagnostics capture.

Watch validation of this final batch and the theme fingerprint is pending. Unit tests do not prove
GPS/sensor continuity on hardware, smoother frame times or battery/runtime savings.

## Map Startup Position Regression Checks

With recording and TBT off, restart the process without clearing cache or app data. Capture startup
with Full diagnostics and the same map/theme and configured default zoom.

- When the service supplies a recent accepted fix before the live marker's wake hold releases,
  `navigate.startupMapPreview status=centered` should appear once. The map can show that area while
  the current-position marker retains its existing freshness/accuracy checks. The preview does not
  publish a marker or guidance anchor and does not change GPS requests, cadence, or wake thresholds.
- Compare the preview event with `mapRenderer.visibleTiles reason=startup_preview` and subsequent
  drawable tiles. The first renderer draw can precede location centering; its `.cold` label alone
  does not establish disk-cache loss or time until the user's area is visible.
- Pan or focus a POI before a fix arrives: startup preview must not recenter the chosen view. Warm
  screen returns with a retained position must also keep their current view. Confirm normal following
  resumes when a trusted marker update arrives, including with a noncentral marker anchor setting.
- Test offline mode, no usable fix, and location-source changes. Existing offline centering and the
  15-second no-position fallback remain in use; a previous-source or stale fix cannot preview the map.

Watch timing and appearance still require device validation. No persistent position store or new
polling/prewarming activity is added by startup preview.

## Cold POI Library Regression Checks

Use Full diagnostics and the same downloaded POI files and visibility selections. The first load
after installing the metadata cache still runs the existing database scans. Wait for loading to
complete before testing a process restart; screen wake alone may retain the in-memory cache.

- Restart the app process without clearing app data or cache. `metadata_cache` should report
  `source=DISK` for unchanged categories, coverage, and previously counted category selections.
  A forced refresh in the same process should report `source=MEMORY`. Compare `reload_complete`
  duration and confirm file names, category order, total/enabled counts, coverage, and markers.
- Change enabled categories and file visibility, then restart. Visibility preferences are read
  live. A new category selection may run one `source=DATABASE` count; previously counted selections
  can reuse disk results. Counts must remain unique point counts, including points in several categories.
- Replace/import a file, including an atomic replacement with the same size and modification time,
  and delete a file. Changed data must rescan and deleted rows must disappear. Rename a linked GPX
  file and confirm the POI folder label updates; the GPX link is deliberately read live.
- Clear only the app cache, retaining downloaded POI files. Loading must rebuild summaries and
  publish the same library. Missing, invalid, or obsolete records fall back to database reads.

Summaries live in the app's private cache directory, with at most 64 completed records per metadata
type and 256 KiB per record. They validate source path, size, full modification time and file identity,
schema version, and checksum. Files without a usable identity skip persistence. Cache reclamation
by Android can therefore make a later restart cold again.

`metadata_stage` identifies database opening, category rows/grouped counts, coverage, unique counts,
GPX links, file listing, and the user source read. `metadata_cache` reports a hashed file identifier,
file size, selection size, and source, without file names or coordinates. These events run only during
Full capture and occur per operation, without polling. `elapsedMs` includes device sleep;
`uptimeMs` excludes deep sleep. `cpuMs` is recorded only around synchronous work on one IO thread,
not around coroutine suspension. `reload_complete` also reports uptime for the whole library load.

Real-watch cold/warm timings, correctness after import/selection changes, and battery impact still
require device validation; unit tests do not establish those results.
