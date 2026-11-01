# Today's Hike: planned weather along the route

Today's Hike uses the route's saved guidance ETA configuration and TrailCore's cumulative
walking-time projection. A checkpoint's arrival is before its own stop; a stop before the finish
occupies time at the same route position. The profile is a planned timeline, including while live
guidance is active. Existing live checkpoint estimates remain separate.

The profile scrolls under a fixed cursor. Inspection stores the canonical route position for the
map marker and an in-memory planned time, so a pause can be inspected without moving the marker.
Changing departure shifts the timeline against the already-loaded forecasts without a request.
Date and zone handling reuse the saved schedule and forecast time zone fallback.

Weather is explicitly requested at the start, midpoint, finish and checkpoint positions, up to
eight locations with at most two requests at once. Each location uses the existing 30-minute
cache and stale fallback. The visualization uses the nearest expected route sample and nearest
hourly forecast, as an approximation, not spatial or temporal interpolation. A missing sample,
missing condition, or uncovered forecast time produces an uncolored region. Colored regions and
condition-change markers are approximate and do not imply a precise boundary. The textual weather
and freshness state remain available. No wind, visibility, or freezing safety threshold is set.

Weather-colored GPX is deferred. A future implementation can derive contiguous route-distance
intervals from this same planned forecast, intersect them with the original GPX segment geometry,
and pass explicitly colored render segments through the shared phone map overlay boundary. Both
Mapsforge and MapLibre already render colored segments. The design must preserve gap styling,
direction arrows and route visibility, define precedence or an explicit user choice between
weather and existing solid/elevation coloring, avoid rebuilding geometry on cursor movement, and
cover both renderers and stale/unavailable areas. It must not color unsampled sections as known.
