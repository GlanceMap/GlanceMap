# Experimental 3D terrain

Map settings expose an opt-in, default-off 3D terrain view powered by WorldWind.
The retained Mapsforge view owns map selection and the 2D center. The 3D session
uses the selected map and DEM source, with a dedicated hiking theme by default. A
setting can instead use the selected 2D theme; it does not change that selection.
Previous renderer and viewing-angle
preferences are ignored; enabling 3D starts WorldWind at 45° and a 4,000 m range
to provide a wider, more overhead initial view in mountainous areas. This does
not guarantee that every nearby ridge is visible.

Enabling 3D requires complete DEM coverage for the selected map, including the
existing source fallback, and otherwise shows the DEM setup guidance. A confirmed
map switch to an area with no DEM turns off 3D, hill shading and slope overlay
and explains how to download terrain. Partial or unknown map coverage does not
automatically turn those settings off.

Recording, active or paused guidance, and route tools use the regular 2D map.
While terrain is visible, the Mapsforge view is detached and its layer manager
is paused; returning to 2D restores the 3D ground center and resumes the manager.
A WorldWind startup failure returns to 2D without changing the saved 3D setting.
The renderer pauses when the screen is not interactive. Its session and camera
are retained for up to 30 seconds of screen-off time, then closed when the app
can run again; leaving the mode closes the session immediately. No wake-up is
scheduled solely for this timeout. Android is asked to preserve the EGL
context during a short pause, but it can discard graphics resources, in which
case tiles must still be rendered again. When a longer sleep expires during deep
sleep, the old view is closed before a new session starts; it is not resumed
first. During standby the existing bounded
imagery and DEM caches remain allocated, including the single oversized DEM
tile exception described below. Progress polling is suspended and in-flight
imagery requests are cancelled while the screen is off. A synchronous tile
render already executing may finish before cancellation takes effect.

WorldWind 2.1.1 uses offline 256-pixel Mapsforge tiles from the selected map and
the chosen 3D theme on a single worker, with duplicate in-flight requests coalesced.
The hiking theme emphasizes paths, peaks, contours and water with restrained land colours;
features depend on the map data and labels are draped onto terrain. The selected
2D theme remains unchanged. Rendered
pixels are shared for coalescing, and each WorldWind request receives its own
bitmap because WorldWind may recycle it. The
existing selected-first local DEM repository supplies tiled elevations; no
WorldWind online provider or persistent rendered imagery cache is configured.
WorldWind's atmosphere supplies the sky and horizon; its ground scattering is
disabled to preserve the offline map's colors and avoid the extra ground pass.
The initial elevation coverage is limited to approximately three arc-seconds,
including where Detailed terrain is present, to avoid claiming uniform 1-second
resolution across fallback boundaries. Coarse elevation grids spanning more than
four HGT files are omitted. The WorldWind resource-cache capacity is one eighth
of maximum heap, capped at 16 MiB and floored at 1 MiB; it is a cache capacity,
not a measured GPU allocation. The decoded HGT cache remains separate and is
capped at the lower of four tiles or the existing adaptive limit, and 16 MiB of
decoded samples. One oversized tile can remain cached even if it exceeds that
budget. A grid processes one source tile at a time; decoding another tile can
temporarily overlap the cached tile. These limits do not cap total heap use;
other relief and recording consumers retain their existing cache policy. A grid
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
The decoded DEM cache reports retained and peak retained sample bytes after
eviction, and its retained size before session teardown; these do not measure
transient allocations or total process memory. Unavailable internal metrics are
labelled as such. Each DEM grid reuses loaded
tiles during sampling to reduce repeated per-sample lookup allocations. A GL callback is not proof of a fully rendered terrain frame.

Physical-watch validation is required after the cleanup, especially
for GL teardown, DEM coverage and detailed-tile memory pressure, display
alignment, input, idle redraws, battery and thermal behaviour.
