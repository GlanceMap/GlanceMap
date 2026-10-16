# Companion route discovery

The left navigation drawer offers **Discover routes**, opening the existing GPX map panel.
Discovery searches within 10 km of an explicit origin: **Near me** uses a valid location fix
no older than two minutes (requesting permission if needed); **Choose on map** accepts a tap,
long press, or the map centre. Waiting and point selection can be cancelled. Existing location
subscriptions, recording, and navigation remain unchanged. **Search this area** searches the
map centre; it does not send requests on every camera frame. **Search place** uses the existing Photon place search, moves the map,
and submits discovery around the chosen place. Photon remains place search, BRouter remains
route generation, and the Route Library remains the sole managed route store.

## Boundary and flow

All new code is in the companion's `discovery` package. `PublishedTrailProvider` accepts a
validated `TrailDiscoveryQuery` and returns `PublishedTrail` objects. Provider JSON remains
inside the adapters. `TrailDiscoveryRepository` queries only enabled sources using structured
concurrency, isolates failures (including individual Geotrek instances), merges successful
results, and reports source-specific success, failure, or configuration requirements.

Requests are explicit, debounced by 350 ms, cancelled when replaced, the discovery panel
closes, or the camera settles for 600 ms more than 500 m from the pending query centre, and protected by generation checks against late results. Successful queries have a
five-minute, 24-entry in-memory cache. Each adapter is limited to 40 results per response;
this is a bounded discovery preview, not an exhaustive catalogue or a bulk downloader.
Moving the map does not automatically refresh an existing result set: press Search this area.
Changing source preferences clears obsolete results immediately and reruns the last query.
No disabled-source results remain visible.

HTTP uses HTTPS, no redirects, an 8-second connect timeout, a 22-second call timeout and a
4 MiB decompressed response limit, with an additional 25-second source deadline. Cancellation
cancels the OkHttp call. URLs, credential headers, response bodies and exception messages
are not logged or shown as provider errors. External text is displayed as text, never executed
as HTML. Individual malformed records cannot crash other records/providers. Coordinates,
elevation, point counts, and line geometry are validated. Invalid points invalidate an entire
line rather than connecting across a missing point. Overpass timeout/partial-response remarks
are failures rather than apparently complete routes.

## Providers and verification (2026-10-16)

Only official documentation, APIs and official implementation sources were consulted. No
websites are scraped. Automated tests use local fixtures, never live endpoints.

### OpenStreetMap / Waymarked Trails

Functional baseline: OSM hiking relations through the documented Overpass QL API. It queries
`type=route`, `route=hiking` relations around the search point and requests complete member
way geometry with `out body geom 40`. Hiking networks (`lwn`, `rwn`, `nwn`, `iwn`) are retained
by the OSM catalogue rather than restricted to a single regional network. Relation IDs and
original OSM URLs are retained; every result credits OpenStreetMap contributors and identifies
ODbL 1.0. The settings endpoint is replaceable with an operator-owned Overpass-compatible
backend without changing the UI. Waymarked Trails' hosted API is deliberately not called:
open server source code does not establish hosted API permission or a stable service contract.

The public `https://overpass-api.de/api/interpreter` default is for limited, explicit searches.
It has no availability guarantee. The official operator warns against relying on shared
instances as a mass-market app backend. Before wider production rollout, deploy an owned
backend or secure an operator agreement and configure its HTTPS endpoint. There is no silent
fallback to undocumented hosts and no automatic retries on 429.

Member way directions and contiguous endpoints are respected. Disconnected ways remain
separate GPX segments, with a visible warning. Nested relations, missing ways or invalid
coordinates mark geometry incomplete and disable saving; the app does not invent connectors.
OSM geometry has no elevation by default: no elevation gains are fabricated. OSM operator
is retained when available; this response mode does not request contributor personal metadata.

References:

- [Overpass QL and geometry](https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL)
- [Overpass operators' capacity policy](https://dev.overpass-api.de/overpass-doc/en/preface/commons.html)
- [OSM attribution and ODbL](https://www.openstreetmap.org/copyright)
- [Official Waymarked Trails API source and deployment](https://github.com/waymarkedtrails/waymarkedtrails-api)

### Geotrek

Functional adapter with **configuration-dependent regional coverage**. No instance is enabled
or hard-coded as worldwide. Route Sources accepts up to eight registry entries:

```text
Regional park|https://geotrek.example/|CC BY 4.0|© Regional park|1
```

The five fields are name, public HTTPS installation base, verified content licence, required
attribution and that installation's hiking practice ID. The example is a placeholder, not an
approved server. Confirm public API access (`API_IS_PUBLIC`) and data reuse rights with each
operator; Geotrek's software licence does not automatically license regional content. Registry
parsing is separate from route normalization. Each instance is a separate provider operation,
so one regional outage does not hide another region's results. Geotrek's official aggregator
is an operator-configured import mechanism, not a hosted worldwide instance directory; the
phone can target an approved aggregated installation through the same registry.

The adapter uses documented API v2 `/trek/`, bounding-box and hiking-practice filters, published
content, and selected fields including complete GeoJSON geometry. Bounding boxes crossing the
date line are split. LineString and MultiLineString preserve elevations/segments. Source ID,
instance name, original API record URL, configured attribution/licence and update date survive
normalization and GPX export. No external GPX URLs or pagination links are followed.

References:

- [Official API/public-access configuration](https://geotrek.readthedocs.io/en/latest/advanced-configuration/application-settings.html)
- [Official v2 serializers](https://github.com/GeotrekCE/Geotrek-admin/blob/master/geotrek/api/v2/serializers.py)
- [Official v2 filters](https://github.com/GeotrekCE/Geotrek-admin/blob/master/geotrek/api/v2/filters.py)
- [Official v2 trek view](https://github.com/GeotrekCE/Geotrek-admin/blob/master/geotrek/api/v2/views/trekking.py)
- [Official operator-configured aggregator](https://geotrek.readthedocs.io/en/2.124.5/import-data/aggregator.html)

### Wanderer

Represented in settings and the provider abstraction, **unavailable in this version**. The
official REST API is per-instance (`/api/v1/trail`, paginated filtering and trail file APIs),
and ActivityPub federation does not make one server a worldwide route catalogue. No trusted
instance, public read-access agreement, or per-trail content licence was supplied. Authentication
and trail reuse rights depend on the installation; the software's AGPL licence is not a licence
for user-uploaded tracks. The official public server list helps people choose a community,
but does not establish worldwide coverage or content reuse rights for those servers.
We therefore do not ship a guessed geo filter, arbitrary demo server,
or fetch unlicensed GPX files. Enabling the switch produces an explicit unavailable status.

To activate later, supply an approved instance registry and rights/access policy, then implement
the documented geographic search/file schema against fixtures from those approved versions.
Use separate instance labels/IDs as Geotrek does; keep federation/registry discovery independent
of normalization. The existing adapter boundary and source toggle require no UI rewrite.

References:

- [Official REST API](https://wanderer.to/develop/api/)
- [List trails](https://wanderer.to/api-reference/operations/apiv1trail/get/)
- [Federation](https://wanderer.to/develop/federation/)
- [Authentication and API tokens](https://wanderer.to/use/authentication/)
- [Official public server list](https://wanderer.to/servers)

### DATAtourisme

Configuration-dependent, France-only, **informational results**. Uses official `/v1/tour`,
`geo_distance` and `type=WalkingTour`. The documented POI geolocation is not a complete route
track, so this adapter never builds fake geometry from it. Only details can be downloaded;
no navigable Library route is created.
Publisher (`hasBeenCreatedBy.legalName`) and last-update date are required to retain a record;
both are displayed and retained alongside Licence Ouverte 2.0 attribution.

Supply `DATATOURISME_API_KEY` through private user Gradle properties or a CI environment
variable. Repository `gradle.properties` definitions are rejected. The build default is empty;
no-key operation returns an unavailable status without HTTP. Credentials are sent only in the
`X-API-Key` header to the fixed official HTTPS host. Never put a key in a URL, fixture, log or
repository. As with existing phone integrations, a key embedded in an Android build is
extractable: use an operator-controlled service before distributing a private shared key broadly.

References:

- [Official API and geographic parameters](https://api.datatourisme.fr/v1/docs?lang=en)
- [Official OpenAPI schema](https://api.datatourisme.fr/v1/openapi.yml)
- [Reuse requirements: producer and update date](https://www.datatourisme.fr/ressources-utilisateurs/)
- [Licence Ouverte](https://www.datatourisme.fr/ressources-juridiques/)

## Preferences, normalization, attribution and saving

`RouteSourcePreferences` uses the companion's existing private SharedPreferences pattern.
Each provider has an independent persistent boolean, exposed as source chips directly on
the search screen and as switches in Route Sources settings. OSM alone defaults on; the other sources
default off. Source configuration and enablement are separate: enabling a source without its
required configuration reports unavailable instead of claiming an empty successful catalogue.

`PublishedTrail` has a list of `TrailSource` entries so deduplication never discards known
identity, original URL, publisher, licence, attribution or update date. Cards show provider
identity and attribution; details show all source records. Map previews live inside that details
panel using existing GPX overlays and map fit behavior. They disappear when discovery closes.

Deduplication is intentionally conservative: identical provider IDs, or normalized equal titles
with identical complete ordered geometry and matching computed distance, are combined.
Nearby starts/similar lengths alone are insufficient. Reverse geometry, coarse geometry and
metadata-only records remain separate until real fixtures justify broader spatial matching.
All source entries are preserved, including during GPX export.

Full geometry uses neutral `GeoPoint`/`TrailPoint` and existing TrailCore profiles to derive
distance, ascent/descent and estimated hiking time. Provider distance remains secondary
metadata. Elevation metrics are displayed only when all points have elevation. No Android,
HTTP client, provider DTO or settings code was added to `:trailcore`; watch behavior is unchanged.

**Download for offline** produces escaped GPX 1.1 with every source URL, attribution, licence, ID, publisher and
update date and the route description in metadata/track description. It uses `RouteLibraryRepository.saveGpxRoute`, the
shared generated/discovered GPX parsing, profile, index and selection flow. It triggers the
existing library refresh callback so Route Library and Today's Hike can use the result normally.
Geometry remains exclusively in Route Library. Existing GPX exports retain the embedded attribution.
A stable published-trail ID in the Library index makes retries idempotent, including when the
Library save succeeds but the details write fails.

The **Downloaded** page reads app-private details from `route-discovery/offline.json`, written
with Android `AtomicFile`, bounded to 4 MiB and 1,000 records. Snapshots retain title, description,
distance, activity, difficulty, start point, source credits, download time, and the Library route ID.
They never duplicate geometry. Opening a downloaded entry loads its existing GPX locally and
recomputes the TrailCore profile off the main thread; it never calls a provider. Metadata-only
entries are clearly labelled and do not create fake GPX routes. Missing/unreadable Library GPX
files leave details readable with a warning. An unreadable details catalogue is preserved,
rather than overwritten by a subsequent save. Offline availability survives app restart and
provider preferences do not hide downloads. Downloads contain route geometry and details only;
map basemaps remain managed by the existing map downloads feature.

## Adding another source and validation

Implement `PublishedTrailProvider` with a stable per-instance label, explicit unavailability,
bounded HTTPS requests, isolated normalization and validated complete geometry. Add its
provider preference and official source/licensing documentation. Add fixture tests for malformed
records, coordinate order, partial failure, required attribution and safe GPX round trips.

Unit tests cover settings persistence, enablement, normalization, bad coordinates/geometry,
bounded responses, missing credentials, merging, attribution, cache expiry, independent instances,
debounce and stale completion. Android tests cover toggles, provider labels, loading/empty/error,
opening details, source chips, current-location/map-centre actions, the left drawer entry,
disabled-source visibility, and actual route/details persistence after repository recreation.
Tests also verify retry deduplication, metadata-only downloads, and preservation of a damaged
catalogue. Unit tests reject stale/future/invalid current-location fixes and invalid offline coordinates.
Live API compatibility, approved regional instance rights, and online/offline map preview/save
on a phone still need explicit manual validation before rollout. Automated green builds do not
establish live-service availability or legal permission for an arbitrary regional instance.
