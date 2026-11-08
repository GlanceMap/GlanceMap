# Experimental 3D terrain

Map settings expose an opt-in, default-off 3D terrain view powered by WorldWind.
The retained Mapsforge view owns map selection and the 2D center. The 3D session
uses the selected map and DEM source, with a dedicated hiking theme by default. A
setting can instead use the selected 2D theme; it does not change that selection.
Previous renderer and viewing-angle
preferences are ignored; enabling 3D starts WorldWind at 45° and a 4,000 m range
to provide a wider, more overhead initial view in mountainous areas. This does
not guarantee that every nearby ridge is visible.
Before the view appears, the session copies and maps the DEM tiles within about
0.1° (about 11 km) of the start, at most four with the start tile first, so a
cold start does not receive terrain in stages while the first imagery loads;
`view_created` reports `dem_prewarm_tiles` and `dem_prewarm_ms`. The start
look-at point is placed at the local DEM height of the map center instead of on
WorldWind terrain that has not loaded yet, which would otherwise rise toward the
camera, request finer imagery and then shift the camera when terrain arrives.
Once loaded terrain under the look-at point is within 25 m of that height, the
look-at point is ground-clamped again; a gesture before then keeps the start
height, as gestures already do for a ground-clamped anchor.

Enabling 3D requires complete DEM coverage for the selected map, including the
existing source fallback, and otherwise shows the DEM setup guidance. A confirmed
map switch to an area with no DEM turns off 3D, hill shading and slope overlay
and explains how to download terrain. Partial or unknown map coverage does not
automatically turn those settings off.

Recording, active or paused guidance, and route tools use the regular 2D map.
While terrain is visible, the Mapsforge view is detached and its layer manager
is paused; returning to 2D restores the 3D ground center and resumes the manager.
At startup the session waits up to 10 seconds for the 2D map center to lie inside
the selected map, since a cold start briefly reports 0,0 before the position is
restored; a center still outside the map then fails startup as before.
A WorldWind startup failure returns to 2D without changing the saved 3D setting.
The renderer pauses when the screen is not interactive. Its session and camera
are retained for up to 30 seconds of screen-off time, then closed when the app
can run again; leaving the mode closes the session immediately. No wake-up is
scheduled solely for this timeout. Android is asked to preserve the EGL
context during a short pause, but it can discard graphics resources, in which
case tiles must still be rendered again. When a longer sleep expires during deep
sleep, the old view is closed before a new session starts; it is not resumed
first. During standby the existing bounded
imagery and DEM caches remain allocated, and uncompressed DEM copies stay mapped. Progress polling is suspended and in-flight
imagery requests are cancelled while the screen is off. A synchronous tile
render already executing may finish before cancellation takes effect.

WorldWind 2.1.1 uses offline 256-pixel Mapsforge tiles from the selected map and
the chosen 3D theme on a single worker, with duplicate in-flight requests coalesced.
Requests made for a newer view are drawn first; within one view WorldWind's
request order is kept, so coarse fallback tiles still fill the screen before
their detailed descendants. Before drawing, a tile that WorldWind did not
request in its latest frame is skipped by cancellation rather than reported as
missing, so it is drawn normally if it returns to view; diagnostics count these
as `skipped`.
The hiking theme emphasizes paths, peaks, contours and water with restrained land colours;
features depend on the map data and labels are draped onto terrain. The selected
2D theme remains unchanged. Rendered
pixels are shared for coalescing, and each WorldWind request receives its own
bitmap because WorldWind may recycle it. The
existing selected-first local DEM repository supplies tiled elevations; no
WorldWind online provider is configured. Drawn images are saved as PNG in the
app cache folder (`worldwind-tiles`) and loaded before drawing on later requests,
including after a session restart. Saved images are grouped by map file
(path, size and modification time), theme fields and whether the 3D hiking
theme is used, text scale, tile size, and app version and build, so a change to
any of them draws new images. Saving runs off the drawing worker. The folder is
capped at 50 MiB, removing the least recently used images, and images unused for
14 days are deleted; it is trimmed when a session starts and every 32 saves.
Session diagnostics report `disk_hits`, `disk_writes` and `disk_write_bytes`,
and the export's Cache Summary reports `worldWindTileCacheSizeMb`.
WorldWind's atmosphere supplies the sky and horizon; its ground scattering is
disabled to preserve the offline map's colors and avoid the extra ground pass.
The initial elevation coverage is limited to approximately three arc-seconds,
including where Detailed terrain is present, to avoid claiming uniform 1-second
resolution across fallback boundaries. Coarse elevation grids spanning more than
four HGT files are omitted. The WorldWind resource-cache capacity is one eighth
of maximum heap, capped at 16 MiB and floored at 1 MiB; it is a cache capacity,
not a measured GPU allocation. DEM heights come from the shared DEM repository,
which reads uncompressed copies of the selected-first tiles in the app cache
folder (`dem-uncompressed`), memory-mapped rather than decoded onto the heap, so
a view spanning two 1-arc-second tiles no longer reloads them in turn. The same
copies serve hill shading, slope overlay, live elevation, and recording and GPX
elevation, through one process-wide lock. Each `.hgt.gz`, `.hgt.zip` or `.hgt`
source is copied once, keyed by source path, size and modification time, and
written through a temporary file and rename; raw sources are copied too so a
mapped file is never rewritten in place. Least recently used copies are removed
above 160 MiB (about six 1-arc-second tiles), always keeping the newest, and a
DEM signature change seen by the 3D session deletes them all; already mapped
tiles stay readable. The 3D repository keeps at most four tiles. Heights use the
same bilinear interpolation as the in-memory decoder. If a copy cannot be written
or mapped, that tile is decoded onto the heap as before; in 3D that fallback is
capped at 16 MiB of decoded samples, where one oversized tile can remain cached.
These limits do not cap total heap use. The diagnostics export's Cache Summary
reports the shared folder's file count, size and limit, and copies, copy time,
reuse and in-memory fallbacks for all DEM consumers since the process started. A grid
intentionally omitted by the four-tile safeguard is counted as skipped rather
than logged as a retrieval failure. Unexpected failures remain logged and counted.
Completed, fully populated 64×64 elevation grids are retained in a separate
session-only least-recently-used cache, with sample-array payload capped at 1 MiB,
so a short wake can reuse the same samples after WorldWind clears its coverage
cache. The cap excludes object overhead and transient copies. Incomplete grids
are not cached. A DEM signature invalidation clears both the grid and source
tile caches; no terrain resolution or interpolation rules change.

WorldWind uses the existing compass orientation, location and GPX inputs.
Drag pans; long-press and drag temporarily rotates horizontally and tilts vertically
from straight down to 85° from vertical. At wide ranges the displayed tilt gradually
returns to straight down, leaving the selected tilt available when zooming back in.
The hold uses Android's configured long-press duration and accepts movement up to
twice the device touch slop before cancelling; a second finger cancels a pending
hold. Rotation starts from the finger's latest position when the hold activates.
If the callback is delayed, movement timestamped after the hold duration enters
orbit instead of cancelling it. Orbit remains active until the finger is lifted.
The 3D view asks its parents not to intercept from touch-down until release or
cancel, as the 2D map does while panning, so Wear's window swipe-to-dismiss does
not take over rightward drags during pan or orbit.
Panning starts when movement passes the same threshold and applies the initial
drag rather than discarding it. Each new gesture uses the current 3D look-at
position as its anchor, including after orbit or a pan fling. A ground-clamped
anchor is converted to the starting ground height for the gesture, so panning
does not repeatedly move the camera up and down with the terrain beneath it.
WorldWind's camera collision protection remains active.
The tools shortcut remains available in 3D; actions that start recording, guidance,
or route tools retain the existing switch to 2D. The 3D indicator below the tools
button cycles session-only 0°, 60°, and 80° viewing-angle presets; from the
initial 45° view the first tap selects 60°. The loading ring spins until visible
imagery can be counted, then shows the share of visible imagery tiles with
textures. It remains below completion while visible imagery is incomplete or
elevation requests are outstanding, and reaches a full ring after 500 ms of quiet.
Once all visible imagery textures are ready, unrelated imagery requests do not
delay completion; pending elevation work still does.
It can show partial progress again as panning or zooming requires more tiles.
Diagnostics time the ring: `render_activity` adds `busy_ms` when it reaches 100%,
and interval and session-end lines report `render_first_complete_ms` (from
session start), `render_completions`, `render_busy_ms_total` and
`render_busy_ms_max`. A loading period interrupted by screen-off is not counted.
The indicator tracks current
renderer observations, not a guarantee that the GPU has presented a complete
frame or that WorldWind will not discover more work. A failed visible imagery
tile changes the button to a red 3D! warning instead of leaving progress stuck;
the warning clears when the failed tile leaves the view. Restarting the 3D
session retries unavailable local tiles. Initial session readiness still requires
at least one imagery tile and a draw callback.
WorldWind zoom is no longer restricted to the previous 250–50,000 m range; the
camera can move back far enough to frame the globe. At ranges of 50 km and above,
WorldWind's bundled low-resolution global background fills areas outside the
selected offline map; it uses no network. The 3D indicator remains visible and zoom displays the
same distance-scale style as the regular map, using an approximate range conversion.
These interactions and globe rendering require watch validation. The navigation
mode button cycles through recenter, compass-follow and north-up as in 2D; it
does not enter a separate orbit mode. Basic GPX is draped onto the rendered terrain
surface and preserves segments; advanced elevation colouring is not reproduced.
Terrain can still obscure a track behind a ridge. Location updates replace the
marker without rebuilding unchanged GPX paths. Hill shading enables normal-based relief without
shadow maps. Recenter and return to 2D use the 3D ground center. Session and
interval diagnostics identify the renderer; WorldWind reports observed redraw
and draw callbacks and tile work, including totals at session end for short runs;
hold outcomes and activation delay are aggregated without touch coordinates;
`orbit_end_release` and `orbit_end_cancel` separate orbits ended by lifting the
finger from orbits cancelled by the system or a parent.
Standby diagnostics record pause and resume within the same session.
`dem_grid_cache_hits`, `dem_grid_cache_misses` and `dem_grid_cache_retained_bytes`
report reuse and retained samples for the completed-grid cache; its retained size
before close is logged at session end.
`dem_cache_hits` and `dem_cache_misses` count tile reuse in the 3D repository. `dem_disk_hits`,
`dem_disk_copies`, `dem_disk_copy_bytes` and `dem_disk_copy_ms` report reuse and
creation of uncompressed copies, and `dem_fallback_tiles` counts tiles that used
the decoded fallback, whose `dem_hgt_*` load fields and retained and peak
retained sample bytes after eviction and before session teardown are logged; these do not measure
transient allocations or total process memory. Unavailable internal metrics are
labelled as such. Each DEM grid reuses loaded
tiles during sampling to reduce repeated per-sample lookup allocations. A GL callback is not proof of a fully rendered terrain frame.

Physical-watch validation is required after the cleanup, especially
for GL teardown, DEM coverage and detailed-tile memory pressure, display
alignment, input, idle redraws, battery and thermal behaviour.
