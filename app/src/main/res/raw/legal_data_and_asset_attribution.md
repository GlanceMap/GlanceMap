# Data and Asset Attribution

Last reviewed: 2026-05-07

This file tracks attribution and licensing notes for non-code map assets, datasets and imported POI sources used by GlanceMap.

## 1) OpenStreetMap data

- Recommended attribution text:
  - `Map data (c) OpenStreetMap contributors`
- Sources:
  - https://www.openstreetmap.org/copyright
  - https://opendatacommons.org/licenses/odbl/1-0/

## 2) Elevate map theme (OpenAndroMaps ecosystem)

- Local theme file:
- `app/src/main/assets/Elevate.xml`
- `app/src/main/assets/theme/elevate/`
- `app/src/main/assets/theme/elevate-winter/`
- `app/src/main/assets/theme/elevate-winter-white/`
- Embedded license statement in file:
  - CC BY-NC-SA 4.0 for map style.
  - Commercial usage note references BY-ND 4.0.
- Upstream references:
  - https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme
  - https://www.openandromaps.org/en/license

Important:
- `Elevate.xml` references `read_me_elevate.txt` for resource reuse licenses.
- The checked-in theme snapshots include `read_me_elevate.txt`; normal builds do not download theme assets.
- The root source fallback tracks `app/src/main/assets/read_me_elevate.txt` next to `Elevate.xml`.
- OpenAndroMaps license page includes bundling/permission constraints.
- Permission for GlanceMap's OpenAndroMaps/Elevate use/integration has been reported by the project owner as received from OpenAndroMaps and Tobias Kuehn.
- Exact agreement dates and approved scope should be recorded outside the public repository before switching visibility.

## 3) OpenHiking Mapsforge theme

- Local embedded theme files:
  - `app/src/main/assets/theme/openhiking/OpenHiking.xml`
  - `app/src/main/assets/theme/openhiking/` resource tree
- Pinned upstream version in project config:
  - `2026-02-10`
- Optional build-time refresh archive:
  - `https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus`
- Extracted theme contents observed on 2026-03-14:
  - `OpenHiking/OpenHiking.xml`
  - SVG/pattern/symbol subdirectories bundled inside the ZIP.
- Upstream references:
  - https://openhiking.eu/en/downloads/mapsforge-maps
  - https://openhiking.eu/locus/Hungary.xml

Important:
- The downloaded ZIP did not include an obvious standalone license/readme file.
- Project owner confirmed on 2026-04-26 that approval was received from OpenHiking.
- Project owner confirmed the OpenHiking theme license is `CC BY-SA`.
- Keep attribution and share-alike obligations with the project docs.
- Archive the exact upstream page/version and approval details privately when available.
- User-facing note is available in `licenses/OPENHIKING_THEME.md`.

## 4) Tiramisu Mapsforge theme

- Local embedded theme files:
  - `app/src/main/assets/theme/tiramisu/Tiramisu.xml`
  - `app/src/main/assets/theme/tiramisu/res/` resource tree
- Bundled snapshot source:
  - https://github.com/IgorMagellan/Tiramisu/releases/tag/v4.4
- Observed release contents on 2026-03-18:
  - `Tiramisu.xml`
  - `res/` SVG resource tree
  - `LICENSE`
  - `README.md`
- License declared in upstream bundle:
  - CC BY-NC-SA 3.0

Important:
- The upstream license is non-commercial.
- Project owner confirmed on 2026-04-26 that GlanceMap's distribution model is noncommercial, so the current public repository/app plan is treated as compatible.
- Keep attribution and share-alike obligations with the project docs.
- Re-review if the project distribution model becomes commercial.
- User-facing note is available in `licenses/TIRAMISU_THEME.md`.

## 5) French Kiss Mapsforge theme

- Local embedded theme files:
  - `app/src/main/assets/theme/frenchkiss/frenchkiss.xml`
  - `app/src/main/assets/theme/frenchkiss/motifs/`
- Snapshot source used for the current local bundle:
  - User-provided `xctrack-beta-latest.apk`
- Observed snapshot contents on 2026-03-18:
  - `frenchkiss.xml`
  - `motifs/` PNG resource tree
- Embedded provenance statement in XML header:
  - `IGN TOP25 theme for XCTrack by Pascal Cochet`

Important:
- No standalone license file was observed next to the extracted theme assets.
- Project owner confirmed on 2026-04-27 that approval was received from the French Kiss developer.
- Keep the current attribution in the project credits unless upstream requests more specific wording later.
- Archive the exact approval details privately.
- User-facing note is available in `licenses/FRENCH_KISS_THEME.md`.

## 6) Hike, Ride & Sight Mapsforge theme

- Local embedded theme files:
  - `app/src/main/assets/theme/hike-ride-sight/HikeRideSight.xml`
  - `app/src/main/assets/theme/hike-ride-sight/PATTERNS/`
  - `app/src/main/assets/theme/hike-ride-sight/SYMBOLS/`
- Theme page:
  - http://j.seydoux.free.fr/locus/hrs.html
- Manual ZIP download:
  - http://j.seydoux.free.fr/locus/Hike,%20Ride%20&%20Sight!.zip
- Observed upstream license statement:
  - Declared in the XML comment header as CC BY-NC-SA 3.0

Important:
- No separate license file was observed in the downloaded ZIP.
- The stated license is non-commercial.
- Project owner confirmed on 2026-04-26 that approval was received for GlanceMap's use/redistribution.
- GlanceMap's current noncommercial distribution model is treated as compatible.
- Keep attribution and share-alike obligations with the project docs.
- The required user-facing links are kept in `licenses/CREDITS_AND_THANKS.md`.
- User-facing note is available in `licenses/HIKE_RIDE_SIGHT_THEME.md`.

## 7) Voluntary Mapsforge theme

- Local embedded theme files:
  - `app/src/main/assets/theme/voluntary/Voluntary V5.xml`
  - `app/src/main/assets/theme/voluntary/vol_res/`
- Theme website:
  - https://voluntary.nichesite.org/
- Manual ZIP download:
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20MF5.zip
- Legend:
  - https://voluntary.nichesite.org/key.html
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20Key.pdf
- Observed upstream license statement:
  - Declared in the XML comment header and upstream credits page as CC BY-NC-SA 3.0

Important:
- No separate license file was observed in the downloaded ZIP.
- The stated license is non-commercial.
- Project owner confirmed on 2026-04-29 that approval was received from the creator for GlanceMap integration.
- GlanceMap's current noncommercial distribution model is treated as compatible.
- Keep attribution and share-alike obligations with the project docs.
- User-facing note is available in `licenses/VOLUNTARY_THEME.md`.

## 8) OS Map Mapsforge theme

- Local embedded theme files:
  - `app/src/main/assets/theme/os-map/OS Map V4 Day.xml`
  - `app/src/main/assets/theme/os-map/OS Map V4 Night.xml`
  - `app/src/main/assets/theme/os-map/os_res/`
- Theme discussion:
  - https://forum.locusmap.eu/index.php?topic=7000.msg59948#msg59948
- Release ZIP:
  - https://drive.google.com/uc?export=download&id=1RzZTMLRIQM1pY_R8FXdVxKPSTqO2LHUE
- Legends:
  - https://drive.google.com/file/d/1PE0eBzJnGMbDs9a_V_uhQQa0db5RK-Zs/view
  - https://drive.google.com/file/d/1OwAeuBtYN-XxjGkpOs3SrdYAUwYXDets/view
- Observed upstream license statement:
  - Declared in the XML comment header as CC BY-NC-SA 3.0

Important:
- The stated license is non-commercial.
- GlanceMap's current noncommercial distribution model is treated as compatible.
- Keep attribution and share-alike obligations with the project docs.
- User-facing note is available in `licenses/OS_MAP_THEME.md`.

## 9) Refuges.info data imports

- Companion imports POI data from:
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`
- License reference:
  - https://www.refuges.info/wiki/licence

Notes:
- Refuges.info indicates point-page content under CC BY-SA.
- Refuges.info also references OpenStreetMap data and ODbL constraints where applicable.

## 9) Overpass API enrichment for OSM POIs

- Companion enrichment endpoint:
  - `https://overpass-api.de/api/interpreter`
- Documentation:
  - https://wiki.openstreetmap.org/wiki/Overpass_API

Usage note:
- Public instance guidance indicates conservative limits (query/day and bandwidth/day).

## 10) DEM elevation data (slope/hill rendering)

- Build/runtime flows use DEM3-style `.hgt.zip` tiles and optional 1 arcsecond `.hgt.gz` tiles.
- Default source in app/build pipeline:
  - `https://download.mapsforge.org/maps/dem/dem3`
- Optional 1 arcsecond source:
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`
- References:
  - https://download.mapsforge.org/maps/dem/
  - https://download.mapsforge.org/maps/dem/ReadMe.md
  - https://www.mapzen.com/blog/terrain-tile-service/
  - https://www.mapzen.com/rights/

## 11) POI icon assets

- Local POI source icons are derived from Maki and Temaki SVG icon sets:
  - https://github.com/mapbox/maki
  - https://github.com/rapideditor/temaki
- The build pipeline may also download Maki SVG fallbacks from:
  - https://github.com/mapbox/maki
- License:
  - CC0 1.0 Universal
  - https://creativecommons.org/publicdomain/zero/1.0/

## 12) BRouter routing assets and segments

- Bundled routing profile assets:
  - `app/src/main/assets/brouter/profiles2/lookups.dat`
  - `app/src/main/assets/brouter/profiles2/hiking-mountain.brf`
- Companion routing segment download source:
  - `https://brouter.de/brouter/segments4`
- Upstream references:
  - https://github.com/abrensch/brouter
  - https://brouter.de/brouter/

Notes:
- BRouter upstream states `.rd5` routing segments are generated from OpenStreetMap data and updated weekly.
- OpenStreetMap attribution remains relevant for the routing-segment data context.
- The watch app vendors BRouter code separately under the MIT License; see `THIRD_PARTY_NOTICES.md` for the code-license notice.

## 13) User-imported files

- The app supports user-imported map/POI/GPX files.
- Licensing and redistribution rights for user-provided files remain the user's responsibility.

## 14) Practical compliance checklist

- Keep a legal screen reachable from watch settings.
- Keep `CREDITS_AND_THANKS.md` user-friendly and short.
- Keep deeper legal docs in sync with companion external links and APIs.
- Re-check terms whenever provider URLs or import sources change.
