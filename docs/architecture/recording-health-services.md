# Recording Health Services experiment

This backend is opt-in with `-PrecordingHealthServicesExperiment=true`; normal
builds retain raw sensors with the cadence fix. Physical capability, screen-off,
pause/resume, failure and battery validation is required before enabling it by
default. The wake-lock experiment is independent and must remain false when
evaluating Health Services. Do not change both variables in a raw-sensor A/B test.

## Ownership and capabilities

`RecordingHealthRuntime` is application-owned. It observes recording state,
selected watch/strap/pod sources and granted permissions, and starts an
unexported health foreground service. The service owns the SDK controller and
serialized session lifecycle; Activity destruction does not own that session.
The experimental service has its own REC notification, leaving the validated
location service and its notification ownership unchanged.

At session startup the controller checks current exercise ownership and queries
per-exercise capabilities. Hike prefers HIKING, then WALKING; bike uses BIKING.
It requests only supported selected HR, cumulative steps and native steps/minute.
It never silently replaces an existing exercise, including a stale own exercise.
An existing session is reported in diagnostics and recording retains raw fallback.
There is no atomic check-and-start API, so ownership is checked again immediately
before starting. Actual capabilities must not be inferred from the device model.

Health Services GPS and auto-pause are disabled. GlanceMap continues owning GPS
source failover, motion filtering, activity timing, pressure, DEM, hybrid elevation,
distance and GPX geometry. Strap HR and pod cadence/power/speed selections remain
outside Health Services. Unsupported metrics remain raw; per-metric raw
suppression starts only after the platform confirms an active owned session.

The dependency is `androidx.health:health-services-client:1.1.0`. Its minSdk is 30,
while GlanceMap retains minSdk 28. A deliberate manifest override allows packaging;
SDK-free models isolate the dependency and API >= 30 guarded service construction
prevents SDK initialization on API 28/29. Those devices use raw recording sensors.
Legacy runtime loading still requires device validation.

The SDK ownership gateway has a function-scoped `RestrictedApi` lint exception:
1.1.0 restricts the `ExerciseTrackedStatus` IntDef container even though the
documented public `ExerciseInfo` contract uses its constants. Keep that exception
at this gateway; do not replace the constants with magic integers. Serialized
IPC orchestration and the asynchronous SDK callback have narrow exception-handler
lint annotations so failures cannot abort the separate GPX recorder. Cancellation
still propagates. No global lint rule or baseline is weakened.

## Data and delivery

`HEART_RATE_BPM` and `STEPS_PER_MINUTE` carry monotonic measurement times.
`STEPS_TOTAL` is an exercise cumulative aggregate with wall-time boundaries;
`STEPS` is an interval delta. Interval deltas are never added to the cumulative
total a second time, nor converted into cadence across uncertain pause intervals.
A new owned exercise adds the existing recording step offset for recovery/source
handoff. A genuine newer raw measurement is required to release a raw fallback
step carry-forward.

The platform can batch screen-off deliveries for approximately 150 seconds.
Arrival time never refreshes measurement freshness. `RecordingHealthTimeline`
deduplicates kind/source-time pairs and admits only newer samples within the
existing 15-second window to live metrics. If supported, selected watch HR requests
`HEART_RATE_5_SECONDS`; that override does not force steps or cadence into the same
delivery schedule. There is no periodic flush loop.

Late valid measurements can fill missing sensor fields in existing points using
their measurement time, with a maximum 15-second carry into a point. Ownership
and recording pause windows constrain both live overlay and historical fill.
They cannot bridge a pause or a switch to another selected source. Existing
populated point fields, coordinates, timing, pressure and elevation are preserved.
Historical association retains ten minutes of samples after backfilling, while
the recording's populated points/draft carry older data. Unknown data remains
absent rather than being interpolated or marked stationary.

Before GPX encoding, save preparation ends the owned session and consumes its
final delivered batch. Calls are bounded; timeout/failure is logged and does not
prevent saving already available recording data. Incomplete SDK cleanup retains
its controller for retry, and cannot start a replacement over an unresolved
session. A failed disk save permits a new desired session to be emitted when the
recording resumes. The original GPS and SmartTrack thresholds are unchanged.
Failed SDK commands retire the uncertain session before raw fallback; a later
ACTIVE callback cannot silently reclaim its old metric ownership.

## Physical validation

The capability-only Android test `RecordingHealthCapabilitiesTest` reads exercise
capabilities, batching support and current exercise ownership. It never registers
a listener, requests runtime permissions or starts/ends an exercise. It can run
without UI interaction on an attached watch. Temporary isolated probe packages
must be verified by APK identity and removed after the query.

Compare raw baseline and raw wake-lock builds with identical settings, route and
screen-off intervals; inspect actual lock state, elapsed/uptime growth, raw source
callbacks, delivery ages, valid measurements, publication delays and saved GPX.
Then test Health Services separately, including other-app exercise conflicts,
denied permissions, source changes, pause/resume, final batches and Activity loss.
No physical acceptance or battery improvement is claimed from unit tests.

References: [ExerciseClient](https://developer.android.com/health-and-fitness/health-services/active-data),
[SDK API](https://developer.android.com/reference/androidx/health/services/client/ExerciseClient),
[wake-lock sensor guidance](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/identify-wls).
