# Companion External Sources

Last reviewed: 2026-03-20

This file lists third-party websites and APIs referenced by the companion app for user-driven downloads/imports.

## 1) Map download websites (opened in browser)

1. OpenAndroMaps
   - https://www.openandromaps.org/en/downloads

2. Alternativas Libres
   - https://alternativaslibres.org/en/downloads-mf.php

3. BBBike Extract
   - https://extract.bbbike.org/?format=mapsforge-osm.zip

4. Vector City
   - https://vector.city/

5. Freizeitkarte
   - https://www.freizeitkarte-osm.de/android/en/index.html

6. OpenHiking
   - https://www.openhiking.eu/en/downloads/mapsforge-maps

## 2) GPX website shortcut

1. VisuGPX
   - https://www.visugpx.com/

## 3) Refuge/POI APIs used directly by companion

1. Refuges.info bbox API
   - https://www.refuges.info/api/bbox

2. Refuges.info polygons API
   - https://www.refuges.info/api/polygones

3. Overpass API interpreter
   - https://overpass-api.de/api/interpreter

## 4) Routing data downloads

1. BRouter routing segments
   - https://brouter.de/brouter/segments4/

## 5) DEM elevation data

1. Mapsforge DEM3 tiles
   - https://download.mapsforge.org/maps/dem/dem3

2. Mapzen Terrain Tiles / AWS skadi
   - https://s3.amazonaws.com/elevation-tiles-prod/skadi

## 6) Place and address search (reviewed 2026-10-17)

1. Photon / OpenStreetMap worldwide place search
   - https://photon.komoot.io/api/
   - https://www.openstreetmap.org/copyright

2. IGN Géoplateforme / Base Adresse Nationale French postal addresses
   - https://data.geopf.fr/geocodage/search
   - https://adresse.data.gouv.fr/outils/api-doc/adresse
   - BAN data: Licence Ouverte 2.0; IGN/BAN attribution is shown with results.
   - Eligible French addresses use IGN first; unmatched requests fall back to Photon.
   - Both services are disabled by the companion's Online search switch.

## 7) Transparency note

- These sources are external to GlanceMap.
- File availability, file quality, service uptime, and legal terms are controlled by each provider.
- The user remains responsible for redistribution rights of externally downloaded files.
