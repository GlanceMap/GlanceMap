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

These uncertainties describe the input location, not the subsequently processed
elevation or speed. Unknown/future monotonic timestamps have no exported age.
Existing raw/effective horizontal accuracy, elevation source, speed, pressure,
sensor data, segment reasons and processing versions retain their meanings.
No satellite count, fix type or DOP is fabricated from accuracy in metres.

`exportedPointCount` and `exportedSegmentCount` describe the written geometry;
`recordingGeometry=canonical_processed` identifies the canonical saved track.
`recordingLocationCallbackCount` counts callbacks delivered to recording, including
paused/skipped callbacks. `recordingQualityRejectedFixCount` counts only explicit
quality-gate rejections, not every callback that failed to become a point.
`recordingCountersScope` is `session` or `since_recovery`, because diagnostic
counters reset after draft recovery. These counters are not a complete raw track.
Detailed lifecycle, provider and rejected-point events stay in diagnostic telemetry.

While automatically paused, recording retains a bounded window of fixes that satisfy
the existing resume-motion test. On confirmation it can replay a credible restart
through the normal motion and quality gates, preserving measurement times and the
auto-pause boundary; distance starts at the recovered segment, not at the pre-pause
point. Manual pauses and unconfirmed or abandoned starts are not replayed. Sensor
values without suitable historical measurements remain unknown. Auto-pause thresholds
and active-time backdating are unchanged.

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
the corresponding granular foreground/background health permissions declared for newer devices.
Physical-device checks must cover actual permission grants and screen-off sensor callbacks.
