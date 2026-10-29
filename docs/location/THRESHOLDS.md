# Location Threshold Rationale

This file documents intent for important location constants.

Source constants files:

- `app/src/main/java/com/glancemap/glancemapwearos/core/service/location/config/LocationServiceConstants.kt`
- `app/src/main/java/com/glancemap/glancemapwearos/core/service/location/service/SelfHealFailoverCoordinator.kt`

## How To Use This File

- When you change a threshold, update the matching row.
- Include expected effect on both accuracy and battery.
- Reference measurement context (device model, route, environment).

## Key Thresholds

| Constant | Current value | Purpose | Accuracy impact | Battery impact |
|---|---:|---|---|---|
| `HIGH_ACCURACY_BURST_DURATION` | `8000 ms` | Max burst window for immediate requests | Faster convergence for fresh fix | Short high-power window |
| `HIGH_ACCURACY_BURST_INTERVAL` | `1000 ms` | Update interval during burst | Dense burst sampling | Higher short-term GPS cost |
| `STATIONARY_INTERVAL_BACKGROUND_MS` | `60000 ms` | Passive/background floor interval | May reduce reactivity while still | Lower continuous drain |
| `FOREGROUND_MIN_DISTANCE_M` | `1 m` | Interactive min distance gate | Better path continuity | More callbacks when moving |
| `BACKGROUND_MIN_DISTANCE_M` | `5 m` | Passive min distance gate | Less detail in passive mode | Fewer callbacks |
| `IMMEDIATE_COOLDOWN_MS` | `2500 ms` | Prevent rapid repeated bursts | Limits redundant bursts | Protects battery |
| `WATCH_GPS_DEGRADED_ACCURACY_M` | `100 m` | Watch GPS degraded threshold | Detect persistent poor fixes | Triggers follow-up logic |
| `WATCH_GPS_DEGRADED_STREAK_THRESHOLD` | `4` | Consecutive degraded samples to flag | Avoids single-sample false alarm | Avoids frequent mode churn |
| `WATCH_GPS_MAX_ACCEPTED_ACCURACY_M` | `130 m` | Relaxed acceptance cap for watch-GPS source mode, including Auto-mode fallback when fused/phone-assisted location is unusable | Prevents blank marker when watch GPS is coarse or reports the known 125 m floor | Slightly more noisy fixes accepted in watch mode |
| `WATCH_GPS_AUTO_FALLBACK_INTERACTIVE_MAX_ACCURACY_M` | `65 m` | Historical target for good Auto-mode fallback quality; effective fallback acceptance is never stricter than `WATCH_GPS_MAX_ACCEPTED_ACCURACY_M` | Keeps fallback usable while still distinguishing poor vs good in UI/recovery logic | Avoids forcing repeated reacquisition when onboard GPS is the only live source |
| `IMMEDIATE_GET_CURRENT_TIMEOUT_MS` | `12000 ms` | Timeout for immediate `getCurrentLocation` during startup/burst | Gives slow first fix more time before returning null | Slightly longer bounded high-accuracy attempt |
| `AUTO_FUSED_FAILOVER_ACCURACY_M` | `120 m` | Auto-fused poor accuracy threshold for fallback | Escalates when fused quality is persistently weak | May switch to higher-power watch GPS |
| `AUTO_FUSED_FAILOVER_STREAK` | `4` | Consecutive poor fused fixes before fallback | Reduces false fallback triggers | Avoids frequent source churn |
| `AUTO_FUSED_RECOVERY_ACCURACY_M` | `65 m` | Watch GPS quality threshold to recover back to auto-fused; the known 125 m watch GPS floor does not qualify | Requires genuinely better watch GPS quality before switching back | Helps avoid sticky high-power mode without bouncing away from usable onboard GPS too early |
| `AUTO_FUSED_RECOVERY_STREAK` | `4` | Consecutive good watch GPS fixes to recover | Adds hysteresis for stable recovery | Avoids oscillation between sources |
| `AUTO_FUSED_RECOVERY_MIN_FALLBACK_MS` | `20000 ms` | Minimum fallback dwell before recovery | Prevents immediate back-switch churn | Reduces request re-registration churn |
| `AUTO_FUSED_RECOVERY_PROBE_MIN_FALLBACK_MS` | `30000 ms` | Minimum fallback time before periodic probe back to auto-fused | Ensures fallback has meaningful runtime before probing | Limits probe overhead |
| `AUTO_FUSED_RECOVERY_PROBE_COOLDOWN_MS` | `45000 ms` | Cooldown between periodic recovery probes | Avoids rapid repeated source probes | Reduces battery waste from churn |

## Companion Live Tracking Refinement

Source: `glancemapcompanionapp/src/main/java/com/glancemap/glancemapcompanionapp/livetracking/LiveTrackingAcquisitionPolicy.kt`.

- `ACCURACY_BURST_TRIGGER_METERS = 50 m`: request refinement for otherwise accepted periodic fixes with reported accuracy of 50 m or worse, when the selected interval is at least 60 seconds and fine location permission is available.
- `ACCURACY_BURST_TARGET_METERS = 25 m`: stop refinement early when an accepted additional fix reaches this target.
- Refinement remains limited to two additional fixes within eight seconds; the selected normal cadence is unchanged.
- Context: Jérôme reported many 95–99 m fixes during a build 2035 walk at a one-minute interval. The former 100 m trigger skipped refinement for these fixes.
- Expected effect: more opportunities to improve uncertain positions, with more frequent temporary GPS acquisition. Accuracy gains and battery cost at the 50 m trigger still need device measurements.

## Change Template

When adjusting a threshold, add this block to PR description:

```text
Threshold changed:
- Name:
- Old -> New:
- Why:
- Expected accuracy impact:
- Expected battery impact:
- Validation:
```
