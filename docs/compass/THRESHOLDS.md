# Compass Threshold Rationale

This file documents intent for important compass constants.

Source constants file:

- `app/src/main/java/com/glancemap/glancemapwearos/domain/sensors/CompassManager.kt`
- `app/src/main/java/com/glancemap/glancemapwearos/domain/sensors/CompassAlgorithms.kt`
- `app/src/main/java/com/glancemap/glancemapwearos/domain/sensors/FusedOrientationProviderAdapter.kt`
- `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/navigate/effects/NavigateCompassEffects.kt`
- `app/src/main/java/com/glancemap/glancemapwearos/presentation/features/navigate/effects/NavigateEffects.kt`

## How To Use This File

- When you change a threshold, update the matching row.
- Include expected effect on both heading quality and battery.
- Reference measurement context (device model, environment, movement pattern).

## Key Thresholds

| Constant | Current value | Purpose | Heading impact | Battery impact |
|---|---:|---|---|---|
| `MODERATE_TURN_RATE_DEG_PER_SEC` | `20 deg/s` | Detect moderate turn behavior | Improves follow responsiveness in turns | More processing during motion |
| `FAST_TURN_RATE_DEG_PER_SEC` | `68 deg/s` | Detect fast turn behavior | Allows faster heading catch-up | More processing during sharp movement |
| `HEADING_RELOCK_WINDOW_MS` | `900 ms` | Grace window after sensor re-register | Reduces restart flip/jump artifacts | Neutral |
| `HEADING_LARGE_JUMP_REJECT_DEG` | `120 deg` | Reject implausible one-shot heading jumps | Reduces sudden heading spikes | Neutral |
| `HEADING_LARGE_JUMP_CONFIRM_WINDOW_MS` | `350 ms` | Confirm large jump with second coherent sample | Balances jump rejection vs recovery speed | Neutral |
| `HEADING_NOISE_GOOD_DEG` | `3.0 deg` | High-quality noise bound | Stable heading confidence | Neutral |
| `HEADING_NOISE_IMPROVING_DEG` | `5.4 deg` | Medium-quality noise bound | Avoids over-reporting high confidence | Neutral |
| `HEADING_NOISE_POOR_DEG` | `8.8 deg` | Low-quality noise bound | Flags unstable heading sooner | Neutral |
| `FUSED_ORIENTATION_HIGH_POWER_SAMPLING_MICROS` | `50000 us` (`20 Hz`) | Interactive Google fused request rate | Matches the useful display/redraw cadence without oversampling | Reduces callbacks compared with the previous 50 Hz request |
| `FUSED_ORIENTATION_LOW_POWER_SAMPLING_MICROS` | `200000 us` (`5 Hz`) | Ambient and other low-power request rate | Retains a warm heading during the short ambient grace | Reduces ambient sensor cost |
| `FUSED_HIGH_POWER_PUBLISH_MIN_INTERVAL_MS` | `33 ms` | Coalesce devices that ignore the requested fused rate | Keeps the latest heading at up to 30 Hz | Prevents 50 Hz callbacks from driving UI state at 50 Hz |
| `FUSED_FAST_TURN_CONFIRM_MIN_SAMPLES` | `3 samples` | Accept a sustained large turn without the normal weak-confidence delay | Reduces measured 360-degree turn lag while retaining one-shot spike rejection | Neutral |
| `FUSED_UNSTABLE_STARTUP_FINAL_OVERLAP_DELTA_DEG` | `120 deg` | Retry fused startup only when the backup and fused providers still disagree | Avoids restarting after transient wake motion has already converged | Removes redundant sensor restarts |
| `FAST_TURN_MIN_RATE_DEG_PER_SEC` | `55 deg/s` | Enable faster visual convergence during an active turn | Reduces double-smoothing lag on full turns | Briefly increases interpolation work, within the existing render cap |
| `MAP_ROTATION_MIN_APPLY_INTERVAL_MS` | `33 ms` | Cap Mapsforge map rotation at 30 Hz | Preserves smooth rotation without redundant map work | Reduces redraw cost |
| `GOOGLE_FUSED_TRANSIENT_STOP_GRACE_MS` | `2500 ms` | Keep Google fused warm briefly after entering ambient | Improves quick wake continuity | Low-power mode is applied immediately, then the provider stops |
| `FUSED_UNUSABLE_HEADING_FALLBACK_MIN_SAMPLES` | `5 samples` | Require repeated unusable Google fused uncertainty before fallback | Avoids publishing streams that report `180 deg` heading uncertainty | May keep SensorManager fallback active when Google fused is unusable |
| `FUSED_UNUSABLE_HEADING_FALLBACK_MIN_DURATION_MS` | `1200 ms` | Require unusable Google fused state to persist before fallback | Filters startup blips while catching sustained bad fused streams on SM-L505F | Neutral unless fallback stays active |
| `SENSOR_HEADING_SAMPLE_STALE_MS` | `1500 ms` | Mark SensorManager output stale when its source measurement is too old | Prevents silent fallback output from driving map rotation | Negligible polling cost |
| `SENSOR_HEADING_FRESHNESS_POLL_MS` | `250 ms` | Poll active fallback freshness between sensor callbacks | Bounds stale-state detection latency without waiting for another event | Small fixed coroutine wakeup while active |
| `COMPASS_DEEP_TRACE_DECISION_EVENT_CAPACITY` | `2048 events` | Bound ordered provider/integrity/render diagnostics retained per trace session | Preserves recent causal evidence without unbounded memory growth | Memory use is capped and trace is opt-in |
| `MAG_FIELD_MIN_VALID_UT` | `15 uT` | Lower bound for plausible magnetic field | Detects abnormal environment | Neutral |
| `MAG_FIELD_MAX_VALID_UT` | `85 uT` | Upper bound for plausible magnetic field | Detects interference/saturation | Neutral |
| `MAG_FIELD_SPIKE_THRESHOLD_UT` | `18 uT` | Spike detector for sudden interference | Captures abrupt disturbances | Neutral |
| `MAG_INTERFERENCE_HOLD_MS` | `3000 ms` | Hold interference state after trigger | Avoids rapid quality flapping | Neutral |

## Change Template

When adjusting a threshold, add this block to PR description:

```text
Threshold changed:
- Name:
- Old -> New:
- Why:
- Expected heading impact:
- Expected battery impact:
- Validation:
```
