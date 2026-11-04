# Experimental 3D terrain

The map settings expose an opt-in, default-off 3D terrain spike. The retained
Mapsforge view remains the owner of map selection and center. A temporary
renderer snapshot supplies the selected map, theme, label scale, and DEM source;
background work samples a 64×64 grid through the existing relief DEM repository
and renders a separate 512×512 Mapsforge texture over the same Mercator extent.
An on-demand OpenGL ES surface draws the indexed terrain. Pan and zoom may
prepare a replacement mesh and texture in the background.

Enabling 3D requires complete DEM coverage for the selected map, including the
existing source fallback, and otherwise shows the DEM setup guidance. A confirmed
map switch to an area with no DEM turns off 3D, hill shading and slope overlay
and explains how to download terrain. Partial or unknown map coverage does not
automatically turn those settings off. If initial 3D sampling still encounters
missing or invalid elevation, the view falls back to 2D and shows the DEM guidance.

The 3D surface respects the configured zoom-button visibility and pinch setting.
The rotary crown zooms the 3D camera when enabled, respecting the existing
direction setting. Camera distance starts at 2,000 m and is limited to
1,000–14,000 m; the 2D map's zoom settings and controls remain unchanged.

When 3D terrain is enabled, Map settings also offers a fixed viewing angle of
45°, 50°, 55°, 60°, 65° or 70° (default 60°). The setting measures tilt from
top-down, while the renderer measures camera elevation above the horizon:
renderer elevation is 90° minus the selected value. Higher selected angles
therefore give a lower, more oblique view. Updating the selection changes only
the camera matrix and requests a frame; it does not rebuild the mesh or texture.
One-finger dragging pans geographically without changing pitch. The existing navigation mode
button retains recenter, compass-follow and north-up behavior; an additional
3D orbit mode uses horizontal drag to rotate around the terrain and vertical
drag to tilt. The next button press returns to compass-follow. Offline mode can
toggle between normal panning and orbit. 3D compass-follow uses the same rendered
map rotation as 2D, and geographic drag is adjusted for camera bearing. Gestural
tilt and orbit are session-only; the saved angle remains the default for a new
session.

The initial square derives from a 30° field of view, initial camera distance and
pitch, with a bounded 4–12 km starting width and a margin around the approximate flat
ground footprint. A safe radius depends on the width, current pitch and camera
distance. Moving beyond it requests a new square while dragging; new requests
are coalesced and supersede stale preparation. A GPU upload swaps mesh and
texture on the existing render-on-demand surface. The previous window remains
visible while preparing; a small 3D loading indicator shows background preparation
and initialization until the first frame. Failed refreshes retain the current
window. Recenter moves to the GPS
target directly when safe, otherwise prepares a window around the target.
Zoom changes camera distance immediately and requests a matching geographic
extent (bounded to 4–100 km) after a short debounce. The current window stays visible until the new
window is ready, and failed refreshes keep it in place. The extent is bounded;
the whole square must fit the selected offline map and have usable DEM coverage.
Very fast drags, extreme zoom-out or tilt, hills, and missing map or DEM coverage
can still expose the finite terrain edge.

When the existing Hill shading setting is enabled, per-vertex lighting derived
from the sampled mesh gives subtle directional relief. It also shades the GPX
and labels in the shared texture. Hill shading is read when the 3D session starts.

Active GPX tracks are drawn into the Mapsforge texture using the existing track
segments, selected color mode, width, and opacity. They follow the terrain
surface, including segment gaps, and update when the active tracks or their
style changes. Start/end markers and direction arrows are not drawn in 3D.

While terrain is visible, the Mapsforge view is detached and its layer manager
is paused. Returning to 2D restores the last panned center, resumes the manager,
and reattaches the view. Recording, active or paused guidance, and route tools
use the regular 2D map so existing controls and map safety remain available.
The spike does not draw position or POI layers. Failure and first-frame
timeout return to 2D without changing the saved setting; turning the setting off
and on or reopening the screen permits a retry. The surface is paused when the
screen is not interactive and disposed when the screen or mode is left.

Real-watch evaluation is still required for EGL failure handling, view lifecycle,
texture alignment, visible relief, interaction, memory, frame timing, and battery.
Logcat tag `Terrain3D` records preparation, switches, failures and teardown.

## WorldWind comparison renderer

When experimental 3D is enabled, the renderer preference selects GlanceMap
(default) or WorldWind. Both use the same eligibility rules; recording, guidance
and route tools continue to force 2D. The outgoing session is stopped before the
other starts. A WorldWind startup failure returns to 2D without changing the
saved preference.

WorldWind 2.1.1 uses offline 256-pixel Mapsforge tiles from the selected map and
theme on a single worker, with duplicate in-flight requests coalesced. The
existing selected-first local DEM repository supplies tiled elevations; no
WorldWind online provider or persistent rendered imagery cache is configured.
The initial elevation coverage is limited to approximately three arc-seconds,
including where Detailed terrain is present, to avoid claiming uniform 1-second
resolution across fallback boundaries. Coarse elevation grids spanning more than
four HGT files are omitted. The WorldWind resource-cache capacity is one eighth
of maximum heap, capped at 16 MiB and floored at 1 MiB; it is a cache capacity,
not a measured GPU allocation. The existing decoded HGT cache remains separate.

WorldWind uses the existing angle, compass orientation, location and GPX inputs.
Drag pans; long-press and drag temporarily rotates horizontally and tilts vertically.
The 3D indicator remains visible while the renderer is active, and its zoom label
shows the relative camera range rather than a ground-distance scale. WorldWind
button and crown zoom allow a 250–50,000 m camera range; these experimental limits
require watch validation. The existing explicit orbit mode remains available. Basic GPX follows terrain and preserves segments; advanced elevation
colouring is not reproduced. Hill shading enables normal-based relief without
shadow maps. Recenter and return to 2D use the 3D ground center. Session and
interval diagnostics identify the renderer; WorldWind reports observed redraw
and draw callbacks and tile work, including totals at session end for short runs;
unavailable internal metrics are labelled as such. Each DEM grid reuses loaded
tiles during sampling to reduce repeated per-sample lookup allocations. A GL callback is not proof of a fully rendered terrain frame.

Physical-watch validation is required before changing the default, especially
for GL teardown, DEM coverage and detailed-tile memory pressure, display
alignment, input, idle redraws, battery and thermal behaviour.
