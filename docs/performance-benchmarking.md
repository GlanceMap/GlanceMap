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
