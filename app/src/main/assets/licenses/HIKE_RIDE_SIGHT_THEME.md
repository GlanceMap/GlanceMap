# Hike, Ride & Sight Theme

Last reviewed: 2026-07-11

This note tracks the bundled Hike, Ride & Sight Mapsforge render theme used by GlanceMap.

## What is bundled

- Theme name:
  - `Hike, Ride & Sight!`
- Embedded app asset path:
  - `app/src/main/assets/theme/hike-ride-sight/HikeRideSight.xml`
- Embedded resource trees:
  - `app/src/main/assets/theme/hike-ride-sight/PATTERNS/`
  - `app/src/main/assets/theme/hike-ride-sight/SYMBOLS/`

## Upstream source

- Theme page:
  - http://j.seydoux.free.fr/locus/hrs.html
- Contact / issue form:
  - https://arkluz.com/trk?contact
- Manual download ZIP:
  - http://j.seydoux.free.fr/locus/Hike,%20Ride%20&%20Sight!.zip
- Legend PDF:
  - http://j.seydoux.free.fr/locus/Hike,%20Ride%20&%20Sight!.pdf

## Snapshot information

- Snapshot refreshed from the author ZIP:
  - XML dated 2026-05-31 in the package
- Current ZIP SHA-256 observed on 2026-07-11:
  - `6f82216d598d5bd85586cc6007bca5f2494279036cfc0af47990239fef3c392c`
- Observed archive contents on 2026-07-11:
  - `Hike, Ride & Sight!.xml`
  - `PATTERNS/`
  - `SYMBOLS/`
  - HTML/PDF/PNG docs

## Theme behavior notes

- HRS includes a Mapsforge `stylemenu`.
- It exposes one visible base style:
  - `Hike, Ride & Sight!`
- Most customization is done through overlay toggles rather than multiple base styles.
- The XML contains `<hillshading zoom-min="7"/>`, so hill shading is treated as supported in the app.

## License and redistribution status

- No standalone license file was observed in the ZIP.
- The XML comment header states:
  - `Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported`
  - `CC BY-NC-SA 3.0`
- Project owner confirmed on 2026-04-26 that approval was received for GlanceMap's use/redistribution.
- For GlanceMap's current noncommercial distribution model, this theme is treated as cleared.
- Keep the required theme page and contact-form links in `licenses/CREDITS_AND_THANKS.md`.
- If GlanceMap later adopts a commercial distribution model, re-review or remove this bundled snapshot.

## Notes

- The theme page credits Jérôme Seydoux as author, and notes that the theme is based on Bernard Mai's Outdoor and Tobias Kühn's Elevate themes.
- If the bundled snapshot is refreshed later, update this note with the new review date and observed archive details.
