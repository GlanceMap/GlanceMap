# Custom Mapsforge themes

Some specialist Mapsforge `.map` files need the provider's render theme to display all their tags. The map and theme are separate: import `specialist.map` and `provider-theme.zip` independently.

GlanceMap does not redistribute third-party imported maps or themes. Obtain them yourself and follow their provider's terms and licence. Imported packages stay in app-owned storage on your watch.

## Import and select

1. Download a Mapsforge theme ZIP yourself. Keep the XML and all its folders together in the ZIP.
2. In Companion, use **Send to Watch → Select files** and select the ZIP. Companion checks for render-theme XML and labels it **Mapsforge theme**. An arbitrary ZIP is not accepted as a theme.
3. Send it using the normal transfer controls. The watch validates and installs the package before confirming success. Wi-Fi, channel, small-file transfer, progress and recovery use the existing transfer system.
4. Select the corresponding map on the watch, then choose **Settings → Themes → Map theme → Custom · …**. A ZIP containing multiple render themes exposes every validated XML entrypoint separately. Theme style-menu defaults and default overlays are used.
5. The theme choice is remembered for this map. Switching to another map restores that map's choice or the existing global default. Choices survive navigation recreation, app restart and reboot. Map rename moves its choice; map deletion removes it.

Hill shading, relief, DEM selection and label size retain the normal GlanceMap controls. An imported theme does not need hillshading instructions: terrain layers remain independent. Global night mode continues to use Mapsforge Dark and restores the map's selected theme when turned off.

## Replace and remove

Sending the same ZIP filename again replaces that package. Theme IDs stay stable when the XML entrypoint paths stay the same. Changing the ZIP filename creates a separate package.

The **Custom themes** section of watch theme settings lists imported packages and offers **Delete**. Deletion removes every theme in that package and invalidates its map associations. Missing, deleted or unusable themes fall back to the existing default/built-in theme.

A failed import leaves the installed revision intact. Installation errors distinguish invalid packages, no render XML, damaged packages, size limits, missing resources and installation failures.

## Storage and validation

```text
files/mapsforge-themes/
  .transfers/<name>.mapsforge-theme.zip    # existing resumable transfer staging
  .staging/<temporary-id>/               # unpublished extraction
  <sha256-of-transfer-filename>/
    installed.properties                # atomically published active revision
    versions/<sha256-of-archive>/
      <original package paths>
```

Nested roots and sibling resource folders are preserved. Each external source records the stable theme/package IDs, display name, relative XML entrypoint, package resource root and archive revision. The original XML is rendered through the existing Mapsforge layer pipeline. Relative `file:` and unprefixed image references resolve from the XML directory and must remain inside the package. No XML is copied to an unrelated cache.

Limits (binary MiB): **64 MiB archive**, **256 MiB total expansion**, **16 MiB per entry**, **8 MiB per theme XML or referenced SVG**, **10,000 ZIP entries**, **64 render-theme entrypoints**, **64 XML nesting levels**, **512 characters per ZIP path**. Both declared and actual expanded sizes are checked; CRC and central-directory validation reject damaged ZIPs. The watch also parses each discovered theme with its installed Mapsforge renderer before publication.

Archive paths reject absolute paths, drive paths, backslashes, control characters, empty/dot/traversal components and canonical-path escape. Duplicate paths and file/directory collisions fail. ZIP Unix symlink attributes are never materialized as links; entries are written as regular files. Theme XML and referenced SVG DTDs/entities are rejected before native parsing/decoding. Resource references are restricted to local SVG/PNG/JPEG/WebP data inside the package; scripts and external resource schemes are not used. Standard macOS archive metadata is excluded from entrypoint discovery.

Extracted files are synced before an atomic manifest replacement. Old revisions remain available to already-rendering layers during this process and are reclaimed when the store is created in the next process. Reimporting the identical archive reuses its revision. Unpublished staging data is cleaned after failure and at process startup. Installation uses Java file/ZIP/SAX and `java.nio.file` APIs available on Android API 28; the app minimum is unchanged.

Theme preferences retain existing global keys as defaults and store map-specific theme/style/overlay keys under a hash of the app-owned map path. Terrain/night toggles remain global. Package content changes produce a different resource path and enter the renderer's normal theme-signature/cache invalidation flow. Imported packages are not derived-cache data and are retained by **Clear cache**.

## Manual interoperability check

A user-supplied Finnish map/theme pair from [kartat.hylly.org](https://kartat.hylly.org/) can be used to check interoperability. GlanceMap neither bundles nor automatically downloads those files and is not affiliated with the provider.

On a real watch, supply `mtk_suomi.map` and `peruskartta.zip`, send both, select the map and discovered theme, and inspect detailed symbols, paths and patterns. Leave/re-enter navigation, restart the app, reboot, switch to another `.map`, then switch back. Verify each map's choice, label sizing, hill shading, relief and night mode. Also test transfer pause/recovery, replacement while visible, deleting the active package and Android 9/API 28 installation/rendering.
