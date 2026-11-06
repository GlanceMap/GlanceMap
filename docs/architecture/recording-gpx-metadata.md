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

Valid heart rates (1–255 bpm) are also written as Garmin TrackPointExtension v1
`gpxtpx:hr`, retaining `gmap:heartRateBpm` for existing readers. Steps/minute remain
`gmap:cadenceSpm`; they are not copied into Garmin's revolutions/minute field.
Activity timing, pause bridging, segmentation, filtering, distance and elevation
algorithms are unchanged.

`fastestSpeedMps` is the fastest adjacent-segment geometric speed inside one
continuous segment. Since `fastestSpeedMethod=continuous_segment_geometry_v2`, a
segment whose geometric speed exceeds `max(endpoint Doppler speed) * 1.3 + 0.5 m/s`
is treated as position error (sparse fixes cutting corners, 1 Hz jitter) and
cannot become the maximum; Doppler speed only bounds the value and is never
reported itself. Segments without endpoint Doppler speed are unguarded, so
imported files without `speedMps` keep the `v1` result. Readers still accept
persisted `v1` values.

`recordingDistanceSource` is `WATCH_GPS` or `POD` for watch recordings and
`PHONE_GPS` for companion recordings, which always measure distance from the
phone's fused location.

Watch recordings are titled with the start date, start time and end time
(`yyyy-MM-dd HH:mm HH:mm`, local time) unless the user enters a title. Companion
recordings use the same shared format prefixed with the activity type, for
example `Hike 2026-11-04 09:12 11:40`; this title is both the GPX `<name>` and
the library display name.

The phone REC menu displays a live view of the recap metrics. Track summaries are
calculated from recorded points off the UI thread using the same elevation,
speed and calorie helpers as the saved recap. The service publishes its existing
session step counter separately for presentation, tagged with the recording
generation and restored from the draft; it does not change sensor acquisition,
recording filters or saved statistics. Unsupported sensor metrics remain unknown.

Stopping a phone recording follows the watch flow: Stop pauses an actively
recording session and shows the recap with Save, Discard and Cancel. Save writes
the GPX to the Library (disabled until two points exist), Discard asks for
confirmation before deleting the session, and Cancel resumes only a session that
the prompt itself paused. No second recap is shown after saving.

Phone auto-pause confirms a stop from GPS stillness: accurate fixes within the
stop radius of the last track point and below the stop speed. The regular window
needs 20 s (bike) of such fixes and restarts after a 15 s gap. Once still, the
fused provider often slows or stops delivering fixes, and indoors it loses the sky,
so a bike stop is also confirmed after 30 s of stillness evidence from the first
still fix: only an accurate fix showing movement clears that evidence, while
inaccurate fixes and silence neither clear nor extend it. A timer confirms the
stop when no fix arrives at all. GPS loss while moving never starts the evidence,
so it does not pause. Both paths backdate the pause to the first still fix. Hike
auto-pause is unchanged. Diagnostics log `auto_pause_still_started`,
`auto_pause_still_cleared`, `auto_pause_fix_ignored`, `auto_pause_window_gap_restart`
and the pause `reason` (`window`, `stillness` or `silence`).

References: [GPX 1.1](https://www.topografix.com/GPX/1/1/),
[Garmin extension schema](https://www8.garmin.com/xmlschemas/TrackPointExtensionv1.xsd).
