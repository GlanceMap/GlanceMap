# Phone map start centering

The phone map follows the watch startup-centering rules (`NavigateStartupCentering.kt`,
`OfflineStartCentering.kt` and the offline start-center bookkeeping in the watch `MapViewModel`).
The phone policy lives in `PhoneMapStartCentering.kt`; `CompanionMapScreen` owns the state.

## Location fixes

- The map starts in follow mode. Follow mode centers the camera on every usable fix.
- Until this map session has accepted a fix, a fix older than the marker freshness window
  (`phoneMapMarkerFreshnessMaxAgeMillis`) is ignored for the marker and the camera. This drops
  an old Android last-known location on cold start, as the watch drops a stale pre-wake fix.
- Once a fix has been accepted, the last fix stays the retained position when the app returns from
  the background, until a newer fix arrives. Turning GPS off ends the session.
- The compass still uses the raw fix for magnetic declination.

## Online startup without a fix

- The camera waits 15 seconds (`PHONE_MAP_STARTUP_FALLBACK_GRACE_MS`) for a fix.
- It then centers on the bounding-box center of the visible GPX tracks, keeping the zoom and
  follow mode. Without a GPX track it keeps waiting, as the watch does without a center.
- A usable fix, detaching the camera (pan, rotation, search, discovery, point selection) or
  GPS-off mode cancels the fallback. A first fix no longer forces follow mode on a camera the user already detached.
- GPX tracks are not fitted at startup; GPX tracks never take the camera while following a fix.

## GPS-off mode

- The camera centers once per context (the visible GPX ids) on the GPX center.
- A new GPX set forces a new centering. Changing the base map (offline, online or in 3D) does not:
  the camera keeps its position, zoom, rotation and 3D tilt.
- While GPS is off the viewport center is remembered for the current context and restored when
  GPS is turned off again in that context.

## Phone differences

- Restoring a GPS-off viewport restores its center only; zoom is left unchanged.
- The watch re-centers when the selected map changes, filters GPX tracks to that map and falls
  back to the map center. The phone does none of these: a map switch keeps the camera, and the
  Mapsforge renderer opens an offline map at its start point or center only when the camera is
  outside that map.
- Auto-recenter returns to follow mode after its delay even without a fix, as on the watch.

Real-device validation is required for cold start with and without GPS, with GPX tracks, after
long background periods and when toggling GPS-off mode.
