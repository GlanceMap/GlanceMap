# Recorded GPX metadata

Recorded activities use GPX 1.1. The root `creator` identifies the exporting
GlanceMap version. Track `src` contains the recording device manufacturer/model
when known; `type` carries the activity profile. Coordinates, elevation and UTC
timestamps remain standard GPX point fields.

`RecordingCaptureMetadata` is captured when recording starts, stored in the
recording state and JSON draft, and passed unchanged to the exporter. It includes
the recording app version/build, device manufacturer/model, Android version/API
level/build, and the location source mode, requested sampling interval, effective
location interval, elevation source and power-saving state observed at start.
Settings can change during recording; fields ending in `AtStart` describe only
the initial snapshot. Old drafts leave missing identity/configuration unknown.
No account identifiers, device serials, personal device names or Bluetooth
addresses are exported.

These values use the existing `gmap` namespace:
`https://glancemap.app/gpx/extensions/1`. `RecordingCaptureMetadata.extensionValues`
defines the metadata tag names. Missing/blank values and invalid numeric values
are omitted; a known `false` power-saving value is preserved.

Per-point `RecordedLocationQuality` is captured before asynchronous processing,
preserved by track copies and draft persistence, and exported as:

| Extension | Meaning |
| --- | --- |
| `locationProvider` | Android provider name, when supplied |
| `locationSourceMode` | Active location source mode at capture |
| `gpsVerticalAccuracyMeters` | Vertical uncertainty of the input location, metres |
| `gpsSpeedAccuracyMps` | Speed uncertainty of the input location, metres/second |
| `locationAgeMillis` | Delivery age calculated from monotonic timestamps, milliseconds |
| `gnssSatellitesUsed` | Satellites the GNSS chip used for a direct watch-GPS fix |
| `gnssUsedCn0AvgDbHz` | Mean C/N0 of those satellites, dB-Hz |

These uncertainties describe the input location, not the subsequently processed
elevation or speed. The GNSS values come from the latest `GnssStatus` report taken within 3 s of
the fix. They are written only when diagnostics capture was on during the recording,
and are omitted for fused/phone fixes and when no matching report exists.
Some watches (OnePlus OPWWE251) report fixed placeholder uncertainties for direct
watch GPS (125 m horizontal, 250 m vertical, 25 m/s speed), and recording currently
filters those fixes with an assumed 18 m. The satellite values are recorded to
calibrate a measured replacement, for example from a walk with a phone recording in
parallel; no recording decision reads them yet. Unknown/future monotonic timestamps have no exported age.
Existing raw/effective horizontal accuracy, elevation source, speed, pressure,
sensor data, segment reasons and processing versions retain their meanings.
No satellite count, fix type or DOP is fabricated from accuracy in metres.

`exportedPointCount` and `exportedSegmentCount` describe the written geometry;
`recordingGeometry=canonical_processed` identifies the canonical saved track.
`recordingLocationCallbackCount` counts callbacks delivered to recording, including
paused/skipped callbacks. `recordingQualityRejectedFixCount` counts only explicit
quality-gate rejections, not every callback that failed to become a point.
`recordingQualityHeldFixCount` counts fixes the quality gate held as possible jumps
(a held fix may later be confirmed, so it is an event count, not lost points).
`recordingMotionSuppressedFixCount` counts fixes the movement gate kept back as
stationary jitter or unconfirmed slow progress. Together they show whether gaps came from
fixes that never arrived or from fixes recording chose not to store. Watches reporting a
fixed 125 m accuracy are filtered with an assumed 18 m, so a high held count on such a
watch suggests that assumption is too tight.
`recordingCountersScope` is `session` or `since_recovery`, because diagnostic
counters reset after draft recovery. These counters are not a complete raw track.
Detailed lifecycle, provider and rejected-point events stay in diagnostic telemetry.

While automatically paused, recording retains a bounded window of fixes that satisfy
the existing resume-motion test. On confirmation it can replay a credible restart
through the normal motion and quality gates, preserving measurement times and the
auto-pause boundary; distance starts at the recovered segment, not at the pre-pause
point, unless the pause is joined into one continuous line (see below). Manual pauses and unconfirmed or abandoned starts are not replayed. Sensor
values without suitable historical measurements remain unknown. Auto-pause thresholds
and active-time backdating are unchanged.

## GPS-gap segments

A recovered fix is a GPS-gap candidate when the larger of the delivery gap and the time
since the last stored point reaches the continuity threshold (two sample intervals,
15 s, or 2× the median callback interval). Because that clock starts at the last
stored point, held or rejected fixes count toward it. A candidate starts a new
`<trkseg>` with `segmentStartReason=GPS_GAP` only when all of these hold:

| Check | Hike | Bike |
| --- | --- | --- |
| Gap at least | 60 s | 30 s |
| Straight-line displacement above `max(base, accuracy before + after)`, capped | 40–80 m | 80–150 m |
| Gap longer than the bridge limit, **or** progress implausible | 180 s | 90 s |

Progress is plausible when the live distance recovery cap would not shorten it: reported
speed (hike at most 3 m/s, bike 12 m/s; fallback 1.4 or 5.5 m/s) × elapsed time × 1.35,
plus the summed effective accuracy (at least 8 m). Effective accuracy is used, so the fixed
125 m watch-GPS value does not widen the check. A plausible moderate gap stays in the
same segment and is drawn in the normal track colour, like other trackers; its full
distance counts both live and in the saved track. Long or implausible gaps keep the
separate segment and grey connector. Across them, live distance and the saved track
count the same capped straight line, stored on the first point of the new segment as
`gmap:gapDistanceMeters`; saved-activity distance, the elevation profile, ETA and
route-tool lengths read it back. A manual or automatic pause that resumes within the
existing 30–60 m join distance is written as one continuous segment, so the saved track
counts the straight line walked during the pause; live distance now adds the same line
(stored as `gapDistanceMeters` on the resumed point). A pause shown with a grey connector
and session-recovery boundaries count no distance. Files recorded before this field count
none for their GPS gaps. In the 2026-11-09 OnePlus OPWWE251 walk, two joined pauses (38 m
and 14 m) made the saved track about 20 m longer than the live distance.

This replaces the earlier rule that split every qualifying gap after 60 s (hike) or 30 s
(bike). While moving, that rule depended on time alone: walking covers 66–84 m in 60 s,
above the 40–80 m allowance. The 2026-10-27 OnePlus OPWWE251 evening recording split on
57 m in 60 s at about 1 m/s. A walk with a screen-off dropout must still confirm the
visual result on a watch.

Pressure fusion currently checks its latest pressure snapshot against the GPS
measurement time. For delayed GPS batches, a newer pressure sample cannot be used
as a fresh historical sample; timestamp-matched pressure history is a separate
investigation and is not changed by restart recovery.

Valid heart rates (1–255 bpm) are also written as Garmin TrackPointExtension v1
`gpxtpx:hr`, retaining `gmap:heartRateBpm` for existing readers. Steps/minute remain
`gmap:cadenceSpm`; they are not copied into Garmin's revolutions/minute field.
Activity timing, pause bridging, segmentation, filtering, distance and elevation
algorithms are unchanged.

New recording summaries derive watch walking average cadence from recording steps
and Time (Active); a known zero is exported as zero. Their HR mean is weighted by
covered active sensor time, independent of GPS points. `heartRateCoveredSeconds`
reports coverage and `heartRateAverageMethod=active_time_weighted_v1` identifies
this method. Missing HR intervals are excluded, never replaced with zero or an
indefinitely carried reading. Legacy recovered drafts without a sensor checkpoint
retain their older point-based HR mean and omit these coverage/method tags.
`cadenceAverageMethod=active_time_steps_v1` marks the watch step/time mean. Readers
preserve an explicitly unknown session mean rather than deriving a substitute
from sparse GPX point sensor values; unmarked older files retain their fallback.

References: [GPX 1.1](https://www.topografix.com/GPX/1/1/),
[Garmin extension schema](https://www8.garmin.com/xmlschemas/TrackPointExtensionv1.xsd).

## Watch heart-rate permission compatibility

Recording heart-rate permissions are selected by device API: `BODY_SENSORS` through API 35,
`android.permission.health.READ_HEART_RATE` on API 36+. Request, registration, and diagnostic
grant checks share the same selector. Legacy manifest declarations are capped at API 35, with
the granular heart-rate permission declared for newer devices. Background sensor permissions are
not requested; recording uses a user-started foreground service.
Physical-device checks must cover actual permission grants and screen-off sensor callbacks.
