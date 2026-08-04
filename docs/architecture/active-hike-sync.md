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
