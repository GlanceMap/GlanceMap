# Tiramisu Theme

Last reviewed: 2026-04-26

This note tracks the bundled Tiramisu Mapsforge render theme used by GlanceMap.

## What is bundled

- Theme name:
  - `Tiramisu`
- Embedded app asset path:
  - `app/src/main/assets/theme/tiramisu/Tiramisu.xml`
- Embedded resource tree:
  - `app/src/main/assets/theme/tiramisu/res/`

## Upstream source

- Project repository:
  - https://github.com/IgorMagellan/Tiramisu
- Release archive used for snapshot pinning:
  - https://github.com/IgorMagellan/Tiramisu/releases/tag/v4.4

## Snapshot information

- Bundled release archive:
  - `v4.4`
- Release archive contents observed on 2026-03-18:
  - `Tiramisu.xml`
  - `res/` SVG asset tree
  - `LICENSE`
  - `README.md`

## Theme behavior notes

- Tiramisu includes a Mapsforge `stylemenu`.
- The default bundled style is:
  - `tms_hiking`
- The visible built-in styles are:
  - `Hiking`
  - `Mountain biking`
  - `Velo`
- The XML includes `<hillshading zoom-min="9" zoom-max="17" />`, so hill shading is treated as supported in the app.

## License and redistribution status

- The release archive includes a license file declaring:
  - `Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported`
  - `CC BY-NC-SA 3.0`
- Project owner confirmed on 2026-04-26 that GlanceMap's current distribution model is noncommercial.
- For the current public repository/app plan, this theme is treated as compatible with the CC BY-NC-SA 3.0 terms.
- If GlanceMap later adopts a commercial distribution model, re-review or remove this bundled snapshot.

## Notes

- Tiramisu is currently bundled as an embedded snapshot for local testing inside GlanceMap.
- If the bundled snapshot is refreshed later, update this note with the new release tag and review date.
