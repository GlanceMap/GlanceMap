# Activity details elevation profile

Activity details ends with a read-only elevation profile inside its existing
scrolling dialog. `GpxViewModel.loadActivityElevationProfile` loads the saved GPX
through the existing signature-aware profile cache on the IO dispatcher. The
loader does not open the full-screen profile or compute route ETA.

The distance axis uses the canonical cumulative distance from the saved,
corrected GPX geometry, matching activity details. Pause and recovery segment
transitions add no distance (a pause joined into one continuous line is not a segment
transition in the saved file). A recorded GPS-gap transition adds its saved
`gmap:gapDistanceMeters` (the capped straight line counted live); files without it add
none. Transitions' known elevation endpoints are connected in grey; missing
elevations also produce grey connections rather than fabricated samples.

Display reduction preserves the first/last samples, global extrema, bucket
extrema and both endpoints of every gap. Gap endpoints take precedence over the
120-sample target. The activity-details chart and interactive elevation popup
use the user's saved GPX track colour for normal segments and reserve grey for
gaps. The popup keeps its existing zoom and selection behavior, and its uniform
sampling retains both endpoints of each gap. The activity chart reuses existing
scale bounds and metric/imperial formatting. It consumes no touch or rotary
gestures, leaving the parent dialog in control of scrolling.

Loading is scoped to the visible section and activity path, and is cancelled on
activity changes or dismissal. Missing elevations and unreadable files have
separate empty/error states. Recording filters, distances and GPX export remain
unchanged.
