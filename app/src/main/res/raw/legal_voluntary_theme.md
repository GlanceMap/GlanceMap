# Voluntary Theme

Last reviewed: 2026-07-11

This note tracks the bundled Voluntary Mapsforge render theme used by GlanceMap.

## What is bundled

- Theme name:
  - `Voluntary`
- Embedded app asset path:
  - `app/src/main/assets/theme/voluntary/Voluntary V5.xml`
- Embedded resource trees:
  - `app/src/main/assets/theme/voluntary/vol_res/svg/`
  - `app/src/main/assets/theme/voluntary/vol_res/patterns/`
  - `app/src/main/assets/theme/voluntary/vol_res/osmc/`
- The upstream ZIP also includes:
  - `Velocity V5.xml`
  - `Voluntary MF5.zip for Cruiser and Orux.txt`

## Upstream source

- Theme website:
  - https://voluntary.nichesite.org/
- Downloads page:
  - https://voluntary.nichesite.org/downloads.html
- Details page:
  - https://voluntary.nichesite.org/details.html
- Credits page:
  - https://voluntary.nichesite.org/credits.html
- Manual download ZIP:
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20MF5.zip
- Legend page:
  - https://voluntary.nichesite.org/key.html
- Legend PDF:
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20Key.pdf

## Snapshot information

- Download package refreshed on 2026-07-11.
- XML header version observed:
  - `v.260516`
- Current ZIP SHA-256 observed on 2026-07-11:
  - `f3a1d263913913f061fb448e271a22c9f6a1bf025dfa65da575efa6a17e3a3ef`
- Observed archive contents on 2026-07-11:
  - `Voluntary V5.xml`
  - `Velocity V5.xml`
  - `vol_res/svg/`
  - `vol_res/patterns/`
  - `vol_res/osmc/`

## Theme behavior notes

- Voluntary includes a Mapsforge `stylemenu`.
- It exposes seven visible base styles:
  - `Hiking & Wintersport`
  - `Cycling`
  - `City`
  - `Road`
  - `Multi`
  - `Transparent`
  - `Yellow`
- The default style is `vol-multi`.
- Most customization is done through overlay toggles for hiking routes, cycling routes, MTB routes, ski/winter details, paths, tracks, settlements and background.

## License and redistribution status

- No standalone license file was observed in the ZIP.
- The XML comment header and upstream credits page state:
  - `Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported`
  - `CC BY-NC-SA 3.0`
- Project owner confirmed on 2026-04-29 that approval was received from the creator for GlanceMap integration.
- For GlanceMap's current noncommercial distribution model, this theme is treated as cleared.
- Keep the theme website, credits page, downloads page and legend links in user-facing documentation.
- If GlanceMap later adopts a commercial distribution model, re-review or remove this bundled snapshot.

## Notes

- The theme is by John Campbell.
- The upstream credits state that Voluntary was originally based on OpenAndroMaps HC and owes a lot to Tobias Kuehn.
- The upstream credits identify symbol/pattern sources including SJJB SVG map icons, Tobias Kuehn adaptations, Nicholas Mollet map icons, OpenStreetMap Wiki aerialway symbols, public-domain Noun Project symbols, and OSM Carto symbols.
- If the bundled snapshot is refreshed later, update this note with the new review date, observed archive details and checksum.
