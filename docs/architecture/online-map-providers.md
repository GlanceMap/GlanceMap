# Companion online map providers

The companion's provider selector is defined in `PhoneMapRendererCatalog.kt`.
New providers use the existing MapLibre renderer for both the base map and the
comparison layer. Existing source preference names and the OpenTopoMap default
remain unchanged. Utility pickers continue to use standard OpenStreetMap.

## Providers requiring no API key

| Source | Coverage | Connection |
| --- | --- | --- |
| OpenFreeMap | Global | `https://tiles.openfreemap.org/styles/liberty` |
| IGN aerial | France and supported overseas areas | Géoplateforme WMTS, `ORTHOIMAGERY.ORTHOPHOTOS`, `PM_0_19` |
| swisstopo | Switzerland | `ch.swisstopo.pixelkarte-farbe`, Web Mercator XYZ |
| SWISSIMAGE | Switzerland | `ch.swisstopo.swissimage`, Web Mercator XYZ |
| USGS Topo | US detailed mapping | National Map `USGSTopo` ArcGIS tiles |
| USGS imagery | US orthoimagery | National Map `USGSImageryOnly` ArcGIS tiles |
| Esri World Imagery | Global, variable imagery detail | Public World Imagery ArcGIS tiles |

Plan IGN V2, CyclOSM, OpenTopoMap and standard OSM were already configured.
MapTiler satellite and Tracestrack remain optional, key-configured providers.
No credentials or paid subscriptions are introduced by these additions.

The Swiss sources include geographic bounds to avoid requesting tiles outside
their published coverage. ArcGIS endpoints use `{z}/{y}/{x}`; Swiss XYZ sources
use `{z}/{x}/{y}`. IGN's current capabilities advertise the `PM_0_19` matrix set.

## Vector styles and comparison

`OnlineMapProvider` describes exactly one of a raster URL template or a complete
style URL. OpenFreeMap loads its vector style directly; raster providers retain
the generated raster style. Credits for both displayed providers remain
available when maps are compared.

The renderer captures the original basemap paint opacity before adding GPX,
POIs, location and other app overlays. Comparison opacity multiplies those
original paint values or expressions, rather than accumulating opacity changes
or fading app overlays. This supports the vector basemap as a comparison layer
without replacing the existing overlay ownership logic.

## Mapterhorn hillshade

The existing online hillshade control now uses Mapterhorn's 512-pixel,
Terrarium-encoded WebP DEM tiles. It uses the global pyramid through zoom 12 and
overzooms above that; finer zooms have regional coverage. Hillshade is inserted
above raster imagery or below the first vector label layer. This does not add a
new 3D terrain control or change the separate live-elevation sampling provider.

## Access terms

Provider access and licensing remain distinct. Preserve visible attribution,
the identifying GlanceMap User-Agent, service caching and provider usage limits.
These sources are for interactive online display; this change adds no download
or prefetch feature.

- [OpenFreeMap](https://openfreemap.org/) allows commercial online use without a
  key or stated request quota, on a best-effort service.
- [Mapterhorn](https://mapterhorn.com/data-access/) publishes terrain tiles from
  open datasets; retain its linked source attribution.
- [IGN WMTS](https://cartes.gouv.fr/aide/fr/guides-utilisateur/utiliser-les-services-de-la-geoplateforme/diffusion/wmts/)
  documents the public imagery service. Restricted SCAN layers are not added.
- [swisstopo](https://www.swisstopo.admin.ch/en/faq-free-geodata) permits commercial
  use of its open data, subject to attribution and service fair use.
- [USGS](https://apps.nationalmap.gov/services/) lists the National Map services.
- Esri's public URL responds without a key, but its
  [linked licensing summary](https://goto.arcgis.com/termsofuse/viewsummary)
  conditions use on Esri software/subscriptions. Adding the requested source is
  not confirmation of a free production license for a MapLibre application.

OpenHikingMap/OpenTopoMap-R are not added: their public host limits free use to
non-commercial projects. Maptoolkit Community is also excluded because its
published terms restrict navigation and routing.
