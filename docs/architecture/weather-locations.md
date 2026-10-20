# Weather locations

The companion Weather page supports the existing GPX route forecast plus explicitly
requested current-location and map-selected forecasts. Choose on map opens the retained
main map: tap/long-press a point, choose a POI or search result, or use the map centre.
Selection returns to Weather and loads that point. Cancel returns without replacing the
previous weather target. The forecast page needs no imported route for either new mode.

`LocationWeatherController` owns only the standalone location forecast. It does not write
`RouteLibraryViewModel.routeWeatherUiState`, Mission Plan or Live Hike weather. Changing
locations clears the old forecast immediately; refreshing the same location keeps its
forecast visible. Requests cancel when superseded or when the weather page stops, with
generation checks against non-cooperative late replies. A cancelled late location fix
cannot start a forecast request. Leaving the screen does not schedule a future refresh.

Current location uses the existing foreground fine/coarse permissions. It requests one
balanced-power Google Play services fix, accepts a monotonic fix age of at most two minutes,
and waits at most 20 seconds (15-second location acquisition request). Missing permission,
missing/stale/future fixes and invalid coordinates produce a recoverable error with map
selection still available. Cancellation cancels the location token. No recording, background
permission, navigation mode, GPS cadence or watch behavior is changed. A location snapshot
is fetched again only when the user chooses Current location again.

Both modes reuse Open-Meteo, existing hourly/daily presentation, forecast timezone handling,
refresh, and the bounded shared app-private cache. Optional elevation is omitted for these
points so the provider can use its terrain model. Cached data retains its actual forecast
coordinates and gets the current requested display label. The cache/stale indicators remain
visible. The current and map-selected coordinates are disclosed in the companion/public
privacy policies; there is no new weather service, dependency, credential or automatic
background polling.

Fixture tests cover fresh-fix validation, missing permission/fix, selected location and refresh,
cache display, source changes, cancellation and late replies. Compose tests cover no-GPX
entry actions, generic location labels, refresh and the map-centre/cancel controls. Real-phone
verification remains necessary for permission denial, GPS availability, selecting points and
search results on both map renderers, returning/cancelling, and cached/offline forecasts.
