# Active-Hike Synchronization

This document describes the compact watch-to-phone state used to make the companion useful while
a hike is in progress.

## Ownership

- The watch owns GPS acquisition, route matching, off-route detection, progress, ETA, and
  elevation calculations.
- The companion owns the larger-screen briefing and dashboard presentation.
- The companion does not use an active-hike snapshot to make navigation decisions or issue
  guidance back to the watch.

## Contract

`transfercontract/src/main/kotlin/com/glancemap/shared/transfer/ActiveHikeSnapshot.kt` defines a
versioned, dependency-free payload at `/glancemap/active_hike_snapshot`.

It contains the current phase, route identity, remaining and completed distance, progress, ETA,
remaining ascent/descent, off-route state, and a wall-clock timestamp. Version 2 also carries
recording-only active duration, speed, and altitude. Optional metrics are explicitly encoded as
empty fields. Version 1 remains decodable so an updated companion can show routed TBT progress
from an older watch app; unknown versions or malformed fields are discarded.

## Delivery semantics

The message is a latest-state hint, not a reliable event log:

- The watch sends immediately when phase, route, or off-route status changes.
- It refreshes active state at most once every five seconds.
- Lost or reordered messages are safe: the phone keeps the newest timestamp and a later snapshot
  replaces earlier data.
- The phone never persists this stream as hike history.

The first implementation publishes while the watch navigation screen is active. A future
background mission session may reuse this contract without changing the companion dashboard.

The companion's **Live Hike Dashboard** is a presentation of this state. Routed TBT sessions show
completed and remaining distance, time left, ETA, remaining climb/descent, progress, and off-route
status. REC sessions intentionally show only measured distance, active duration, speed, and
altitude because a recording has no planned destination or defensible ETA. The watch prioritizes
an active or paused TBT session over a simultaneous recording; standalone recording metrics need
a version-2 watch build.

## Companion Trail Intelligence

When the selected companion GPX matches the active watch route by transferred filename or route
title, the companion combines the live distance-from-start with its locally stored GPX profile.
It renders the next planned 30-minute window: distance, ascent/descent, and up to three GPX
waypoints in that window.

This forecast is route-planning information. It is hidden when the routes do not match and does
not alter watch guidance, calculate a safety decision, or make claims about terrain, weather,
water, or hazard conditions.

## Companion Weather Context

The companion can load a short weather context for the selected route through a provider-neutral
`WeatherForecastProvider` boundary. The first provider is Open-Meteo.

- A forecast request is always explicit: the companion makes no background weather requests.
- It sends a coordinate from the locally stored GPX route, not the phone's live location. For a
  matching active hike, the coordinate is the GPX point nearest the watch-reported route progress;
  otherwise it is the route start. GPX elevation is sent when available.
- The response contains current conditions, a near-term hourly outlook, and a 10-day daily
  outlook. It is fresh for 30 minutes in memory, then falls back to the latest locally saved
  snapshot when appropriate. A failed refresh may show that saved snapshot as stale.
- The companion keeps a bounded local forecast history (up to 12 snapshots per route-area and 96
  snapshots overall) in app-private storage. Every weather card shows the snapshot fetch time and
  saved-snapshot count so offline data is never presented as a live update.
- The UI identifies Open-Meteo, links to its site, and labels weather as context rather than a
  navigation, turnaround, or safety decision.
- The current public endpoint is appropriate for the present development integration. Before a
  commercial distribution, confirm the provider's terms or arrange a suitable commercial service
  without changing the companion's provider boundary.

## Companion Mission Plan

The companion can store one local, multi-day mission plan. Each day references an imported GPX
route by ID and can optionally bound it by start and end route distances. This supports both a
separate GPX for every day and multiple days drawn from one long GPX, without copying the original
route files.

- Selecting **Set today** selects the corresponding route in the companion library and makes the
  existing Home briefing, weather context, and Send to Watch action refer to that day.
- Day distance, ascent, descent, and estimated duration are calculated from the shared trail
  profile over the saved distance range.
- A full-route day sends the original GPX. A bounded day is exported as a disposable GPX in the
  companion cache immediately before transfer; it is not added to the route library.
- The watch receives a normal GPX through the existing transfer flow. No watch navigation logic or
  watch-to-phone contract changes are required for this milestone.
