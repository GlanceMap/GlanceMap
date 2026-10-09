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

Save the baseline artifacts before making optimization changes, then run the same benchmark command again after each change.

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
- During theme changes, compare `theme_viewport_ready` with `mapRenderer.visibleTiles` for the
  current layer. Cache notifications alone cannot complete this wait. The existing 4,500 ms
  appearance timeout remains bounded and reports `theme_viewport_timeout` if tiles are still absent;
  it is not proof of successful rendering. Rapid theme changes must cancel the previous wait.
- Enable relief with known DEM files and repeat pan/zoom. Download completion alone does not
  establish that `relief.demReadDecode` ran. Check terrain appearance and the retained elevation
  behavior before merging the streaming decoder changes.

Do not infer battery savings from these diagnostic captures. Compare battery separately with the
same workload and capture mode. Cold POI preparation, recorded-trace display copying, and Compose
profiling remain separate follow-ups.
