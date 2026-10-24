# Service Terms and API Usage

Last reviewed: 2026-05-06

This document captures non-library legal/usage constraints for external services and providers referenced by the app/build pipeline.

## 1) OpenAndroMaps and Elevate

Used for:
- Map download recommendations in companion app.
- Elevate theme download and local fallback assets in watch build/runtime.

References:
- https://www.openandromaps.org/en/license
- https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme

Important term notes (from upstream license page):
- Direct map links are stated as forbidden.
- Selling maps or bundling maps with an app requires written permission from the map provider.
- Integrating "andromaps_" themes is generally allowed for free update services.
- Integrating Tobias themes (including Elevate) requires written permission from Tobias.

Project status:
- Permission for GlanceMap's OpenAndroMaps/Elevate use/integration has been reported by the project owner as received from OpenAndroMaps and Tobias Kuehn.
- Keep the original agreement messages outside the public repository.
- Before switching GitHub visibility, record request/response dates and approved scope in private release notes or an internal issue.

## 2) OpenHiking Mapsforge theme

Used for:
- OpenHiking local embedded theme assets in the watch app.
- Optional OpenHiking theme refresh download in the watch build pipeline.
- OpenHiking map/theme website reference in the companion app.

References:
- https://openhiking.eu/en/downloads/mapsforge-maps
- https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus

Term notes:
- The public site exposes direct download/install links, but no documented general-purpose API was found.
- The downloaded theme ZIP observed on 2026-03-14 did not contain an obvious standalone license/readme file.

Project status:
- Project owner confirmed on 2026-04-26 that approval was received from OpenHiking.
- Project owner confirmed the OpenHiking theme license is `CC BY-SA`.
- Keep attribution and share-alike obligations with the project docs.
- Track current bundled snapshot details in `licenses/OPENHIKING_THEME.md`.

## 3) Voluntary Mapsforge theme

Used for:
- Voluntary local embedded theme assets in the watch app.
- Voluntary theme website and legend references in the companion app.

References:
- https://voluntary.nichesite.org/
- https://voluntary.nichesite.org/downloads.html
- https://voluntary.nichesite.org/key.html
- https://voluntary.nichesite.org/credits.html

Term notes:
- The public site exposes direct manual download and legend links, but no documented general-purpose API was found.
- The downloaded theme ZIP observed on 2026-04-29 did not contain an obvious standalone license/readme file.
- The XML header and credits page identify the theme license as CC BY-NC-SA 3.0.

Project status:
- Project owner confirmed on 2026-04-29 that approval was received from the creator for GlanceMap integration.
- Keep attribution and share-alike obligations with the project docs.
- Track current bundled snapshot details in `licenses/VOLUNTARY_THEME.md`.

## 4) Refuges.info API and data

Used for:
- Companion API calls to:
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`

References:
- API root: https://www.refuges.info/api
- License page: https://www.refuges.info/wiki/licence

Term notes:
- Refuges.info point-page content is published under Creative Commons BY-SA.
- Refuges.info also reuses OpenStreetMap data under ODbL where applicable.

## 5) OpenStreetMap and Overpass

Used for:
- OSM attribution in app.
- Companion map picker background raster tiles:
  - `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
- OSM POI enrichment via Overpass API endpoint:
  - `https://overpass-api.de/api/interpreter`

References:
- OSM copyright and attribution: https://www.openstreetmap.org/copyright
- OSM tile usage policy: https://operations.osmfoundation.org/policies/tiles/
- ODbL: https://opendatacommons.org/licenses/odbl/1-0/
- Overpass API documentation: https://wiki.openstreetmap.org/wiki/Overpass_API

Usage policy note:
- OSM tile usage must remain normal interactive viewing only. Do not add tile prefetching, bulk download, or offline tile caching features against `tile.openstreetmap.org`.
- OSM tiles require visible attribution, the official HTTPS tile URL, a clear app User-Agent, and HTTP caching behavior that respects server cache headers.
- Overpass public-instance guidance indicates conservative safe usage around:
  - less than 10,000 queries/day
  - less than 1 GB downloaded/day

## 6) BRouter routing engine and routing segments

Used for:
- Offline route calculation in the watch app through vendored BRouter modules.
- Companion download of `.rd5` routing segment files from:
  - `https://brouter.de/brouter/segments4`

References:
- Project: https://github.com/abrensch/brouter
- Official site: https://brouter.de/brouter/
- Segment directory: https://brouter.de/brouter/segments4/

Term and attribution notes:
- Upstream states BRouter is released under the MIT License.
- Upstream states the `.rd5` segment files are generated from OpenStreetMap data and updated weekly.
- OpenStreetMap attribution and ODbL context therefore remain relevant for BRouter routing data.

Project note:
- No separate public service-policy or rate-limit page was identified in the reviewed BRouter upstream pages on 2026-03-20.
- Project inference: keep companion downloads conservative and re-check upstream terms before any large-scale automated usage.

## 7) DEM and terrain sources

Used for:
- DEM3 downloads from:
  - `https://download.mapsforge.org/maps/dem/dem3`
- Optional 1 arcsecond DEM downloads from:
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`

References:
- https://download.mapsforge.org/maps/dem/
- https://download.mapsforge.org/maps/dem/ReadMe.md
- https://www.mapzen.com/blog/terrain-tile-service/
- https://www.mapzen.com/rights/

## 8) Build-time POI icon source

Used for:
- Maki SVG icon download during build.

References:
- https://github.com/mapbox/maki
- License: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/)

## 9) Google Play services terms context

Used for:
- `play-services-wearable`, `play-services-location` runtime dependencies.

References:
- Android SDK License Agreement: https://developer.android.com/studio/terms
- Google Play services OSS notices guidance: https://developers.google.com/android/guides/opensource

Note:
- These are Google SDK/service terms, not standard OSS licenses like Apache/LGPL.

## 10) Non-affiliation statement

The app references external providers and websites for user convenience. Unless explicitly stated by those providers, GlanceMap is not officially affiliated with or endorsed by them.
