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

References: [GPX 1.1](https://www.topografix.com/GPX/1/1/),
[Garmin extension schema](https://www8.garmin.com/xmlschemas/TrackPointExtensionv1.xsd).
