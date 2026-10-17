# Companion place and address search

Search is submitted explicitly using the keyboard Search action. The Online switch controls
all online geocoding; offline search remains limited to downloaded POI files. Search text,
addresses and result coordinates are not written to diagnostic logs.

`PhoneMapSearchRepository` retains its local POI search and delegates online lookup to
`PhoneOnlinePlaceSearch`. Worldwide place/address search still uses Photon. French postal
addresses are first checked against IGN's Géoplateforme geocoder using its BAN address index:

- An eligible query begins with a house number followed by a full French street type
  (such as rue, avenue, boulevard or chemin) and includes a five-digit postcode. This is
  a conservative dispatch rule, not a global country detector. Partial addresses,
  abbreviations and other queries continue through Photon.
- The documented `/geocodage/search` request sets `index=address`, `type=housenumber`,
  `postcode` and `limit=5`. It needs no Google project, payment setup or API key.
- Results must be point geometry with valid longitude/latitude, the requested number
  and postcode, a house-number result type and a score of at least 0.8. Malformed,
  approximate and mismatched records are not promoted to exact address results.
- No accepted address, a timeout, a malformed response or an HTTP failure falls back
  to Photon. Cancellation propagates and never starts a fallback request.
- Requests use HTTPS without redirects, a four-second call deadline and the existing
  cancellable, 256 KiB bounded search response reader. A 32-entry in-memory cache avoids
  repeated identical calls. Online lookup and parsing stay on the IO dispatcher.

Address labels retain provider-supplied house numbers. BAN adds its validated house
number when the returned name contains only the street, without duplicating an existing
number or suffix. Photon uses the full address when its name repeats the street; named
places retain their name and address details. Street-only results never borrow a house
number from the search query.

BAN result IDs are prefixed `ban:`. Result details retain `IGN / Base Adresse Nationale`
so source credit survives the existing selected-result popup and state restoration.
The search panel shows the BAN / Licence Ouverte 2.0 attribution and documentation link
when BAN results are present; Photon retains its OpenStreetMap attribution otherwise.
No Google SDK or credential configuration was added.

This does not change routing, route discovery providers, offline maps, recording, GPS
subscriptions or watch behavior. Address selection uses the existing map result callback.

## Official sources checked on 2026-10-17

- [IGN geocoding service and public usage limit](https://cartes.gouv.fr/aide/fr/guides-utilisateur/utiliser-les-services-de-la-geoplateforme/geocodage/)
- [OpenAPI parameters](https://data.geopf.fr/geocodage/openapi)
- [BAN migration from the retired API Adresse endpoint](https://adresse.data.gouv.fr/outils/api-doc/adresse)
- [BAN free reuse and source-credit requirements](https://doc.adresse.data.gouv.fr/docs/documentation-generale/utiliser-la-base-adresse-nationale/foire-aux-questions)

The public service is limited to 50 requests/second per IP. Automated tests use synthetic
fixtures and injected provider functions, not live endpoints. Live checks verify the
supplied failing address resolves to its house number through IGN while Photon returns
the street. Public service availability and dataset completeness are not guaranteed.
