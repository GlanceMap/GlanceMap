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
remaining ascent/descent, off-route state, and a wall-clock timestamp. Optional metrics are
explicitly encoded as empty fields. Unknown versions or malformed fields are discarded.

## Delivery semantics

The message is a latest-state hint, not a reliable event log:

- The watch sends immediately when phase, route, or off-route status changes.
- It refreshes active state at most once every five seconds.
- Lost or reordered messages are safe: the phone keeps the newest timestamp and a later snapshot
  replaces earlier data.
- The phone never persists this stream as hike history.

The first implementation publishes while the watch navigation screen is active. A future
background mission session may reuse this contract without changing the companion dashboard.

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
- The response contains only current conditions and a near-term outlook. It is held in an
  in-memory cache for up to 30 minutes; a stale cached result may be shown if a refresh fails.
- The UI identifies Open-Meteo, links to its site, and labels weather as context rather than a
  navigation, turnaround, or safety decision.
- The current public endpoint is appropriate for the present development integration. Before a
  commercial distribution, confirm the provider's terms or arrange a suitable commercial service
  without changing the companion's provider boundary.
