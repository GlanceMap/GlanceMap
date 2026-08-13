# OS Map Theme

Last reviewed: 2026-05-07

This note tracks the bundled OS Map Mapsforge render theme used by GlanceMap.

## What is bundled

- Theme name:
  - `OS Map`
- Embedded app asset paths:
  - `app/src/main/assets/theme/os-map/OS Map V4 Day.xml`
  - `app/src/main/assets/theme/os-map/OS Map V4 Night.xml`
- Embedded resource trees:
  - `app/src/main/assets/theme/os-map/os_res/svg/`
  - `app/src/main/assets/theme/os-map/os_res/patterns/`
- Companion legend links:
  - Day: https://drive.google.com/file/d/1PE0eBzJnGMbDs9a_V_uhQQa0db5RK-Zs/view
  - Night: https://drive.google.com/file/d/1OwAeuBtYN-XxjGkpOs3SrdYAUwYXDets/view

## Upstream source

- Theme forum page:
  - https://forum.locusmap.eu/index.php?topic=7000.msg59948#msg59948
- Latest release ZIP provided for this snapshot:
  - https://drive.google.com/uc?export=download&id=1RzZTMLRIQM1pY_R8FXdVxKPSTqO2LHUE

## Snapshot information

- XML header version observed:
  - `v4. 20231218`
- Current ZIP SHA-256 observed on 2026-05-07:
  - `d8c31bdb340029d187d3f2b7d01d932603fc175aac30a04d308da8f2402ac825`
- Bundled files from the archive:
  - `OS Map V4 Day.xml`
  - `OS Map V4 Night.xml`
  - `OS Map V4 Day.png`
  - `OS Map V4 Night.png`
  - `release notes.txt`
  - `os_res/`

## Theme behavior notes

- OS Map includes a Mapsforge `stylemenu`.
- It exposes four visible base styles:
  - `OS Landranger`
  - `OS StreetView`
  - `OS Outdoor`
  - `OS Light`
- Day and night variants are backed by separate XML files, but the watch app exposes them under one `OS Map` theme as combined styles such as `Day - Landranger 1:50k` and `Night - Landranger 1:50k`.

## License and redistribution status

- The XML comment header states:
  - `Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported`
  - `CC BY-NC-SA 3.0`
- The XML credits Karl Chick as theme author and John Campbell's Voluntary UK theme as the original base.
- For GlanceMap's current noncommercial distribution model, this theme is treated as compatible with the existing bundled-theme policy.
- If GlanceMap later adopts a commercial distribution model, re-review or remove this bundled snapshot.
