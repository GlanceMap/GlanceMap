# Phone offline map data downloads

The phone library offers elevation and BRouter choices for each installed Mapsforge
map. The choice starts the existing offline-bundle foreground service with the local
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
