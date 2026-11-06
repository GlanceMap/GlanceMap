# Phone offline map data downloads

The phone library shows elevation and BRouter status chips on each installed
Mapsforge map, and either chip opens the same download choices. The choice starts the existing offline-bundle foreground service with the local
map filename and only the requested data components. Map bounds determine tile
coverage; the map and POI files are never fetched by this action. Existing valid
tiles are reused and remain shared across maps.

If the map belongs to a known OpenAndroMaps bundle, its existing bundle metadata is
updated. For an independently imported map, a stable local operation identity lets
the service persist, pause and resume without inventing a downloadable map URL or
adding a synthetic catalog bundle. The completed files live in the normal elevation
and routing stores and the library refreshes coverage on completion. A saved or
active offline download must be finished or cancelled before another is started.

Selections store only the managed map filename. On restore, the file must be a
local `.map` name and a local-operation identity must have the expected format; the
downloader validates the actual map before reading its bounds. Standard bundle
selections without this field remain compatible with saved operations from older
versions. Paused map-data operations expose only data settings: they cannot turn
on map or POI downloading.

## Rendered tile cache

The phone Mapsforge base and comparison layers keep rendered tiles in a persistent
disk bucket named `phone-offline-<map hash>-<theme hash>`. The map part is the
installed file revision. The theme part covers the theme, style, enabled overlays and
external theme revision, plus the tile pixel size and app version code, so a density
change or an update to a bundled theme never reuses differently styled pixels. A
recreated map surface, a returning screen or a restarted process for the same
map and theme reads the existing tiles instead of re-rendering the viewport.
Mapsforge still re-renders a cached tile when the map file data is newer than that tile.

Before this, buckets were non-persistent: Mapsforge deleted them on every layer
destroy, so every surface creation was a cold render. Phone captures from November 2026
showed 36 of 36 first-visible-tile events as `cold_render` and none as `warm_cache`,
including reopenings of the same cache id seconds apart. The time from first draw to
first visible tile ranged from 0.25 s to 4.4 s, with the long end at zoom 8.

Mapsforge rebuilds the index of a reopened bucket on a background thread, and its
render workers never check the cache before rendering. Tiles requested before that
index catches up would be rendered again. For the first
`PHONE_MAP_DISK_INDEX_FALLBACK_MS` (10 s) after opening, the drawable cache checks the
tile file directly (`<bucket>/<zoom>/<x>/<y>.tile`). A tile found on disk is decoded
into the memory cache, so it is drawn instead of queued. An unreadable file is
skipped for that tile, which is then rendered normally. During this window each
missing visible tile and its parents costs one file check per frame.

Debug captures record, per renderer:

- `tile_cache_created ... persistent=true diskFallbackMs=10000`.
- `first_visible_tile_warm_cache` or `first_visible_tile_cold_render`, with `zoom`,
  `firstDrawToTileMs` and `drawableAtFirstDraw`.
- `viewport_tiles_complete` each time the visible tiles become fully drawable. It
  carries `first` (the first viewport after opening), `fillMs` (from first draw, or from
  the first sample with a missing tile), `visible`, `drawableAtStart`, and the cache
  counters `renderedTiles`, `diskReads` and `diskReadFailures`. Coverage is sampled on
  every draw while a fill is timed.
- `tile_cache_retention` with bucket count and bytes before cleanup, deleted buckets,
  and bytes after.

The runtime report adds "Tile cache persistent", "Tiles rendered into cache", "Tiles
reused from disk during index", "Disk tile read failures" and "Disk index fallback
active".

### Tiles per screen and render order

Mapsforge's frame buffer is the view times an overdraw factor, squared to its
diagonal so the map can rotate. With the default 1.2, a 720x1336 phone view needs
16–25 tiles of 512 px per screen, against 4–9 on a 466x466 watch. At about 9 tiles per
second, a new phone area took roughly 2.5 s to fill. The phone now uses an overdraw
of 1.0 (`PHONE_MAPSFORGE_OVERDRAW_FACTOR`). The square still covers the view diagonal,
so rotation keeps full coverage, and the same view needs 9–16 tiles. Less of the map
beyond the screen edge is rendered ahead of a pan. The `mapview_render_ready` event
logs the `overdraw` in use.

Re-adding a tile job that Mapsforge already holds does not make it reschedule its
queue. After a zoom, tiles of the previous level could keep their priority over the
tiles on screen. As on the watch, the phone tile layer requeues one waiting job when
the drawn zoom changes, which forces a reschedule, and logs
`tile_queue_reprioritized fromZoom toZoom pendingJobs`. `viewport_tiles_complete`
also reports `renderedDuringFill` and `tilesPerSec` for device comparisons.

`PhoneMapsforgeTileCacheRetention` registers live buckets and runs one low-priority
cleanup per process, using the same limits as the watch renderer. It deletes buckets
unused for 14 days. Above 150 MiB in total, it deletes the oldest buckets first, keeping
the 5 most recently used where possible, until the total is at most 100 MiB. Open
buckets are never deleted. Hillshade buckets (`phone-hillshade-*`) are outside this
policy.
