# Wi-Fi GPX Transfer + Chinese Localization Design

Date: 2026-11-15
Status: implemented and device-tested

## Problem

A China-market Wear OS phone cannot use Google's Wearable Data Layer: `WearableService`
returns `UNSUPPORTED_PLATFORM` because the firmware declares `cn.google.services`.
GlanceMap's existing phone -> watch sync therefore cannot discover the watch. The watch and
phone are both on the same Wi-Fi network in normal use.

The change keeps the existing product surface:

1. No new UI in either app. Reuse the existing "Send to Watch" screen and its watch list,
   Send button, progress, history, pause/resume/cancel.
2. Both apps localized to Simplified Chinese, including the download-status strings that were
   previously raw enum values.
3. Both apps keep the original package name `com.glancemap.glancemapwearos`; no `.local`
   suffix or parallel app variant.
4. The existing adb/`run-as` workflow remains usable as a fallback.

## Architecture

The watch and phone communicate directly over Wi-Fi. Google Wear is not involved.

```
phone GlanceMap (existing Send screen)
  ├─ NsdManager discovers _glancemap._tcp
  ├─ selected watch appears in the existing watch list
  └─ existing Send button -> WifiUploadStrategy -> HTTP POST -> watch

phone browser (optional fallback)
  └─ http://<watch-ip>:<port>/?t=<token> -> same HTTP POST -> watch

watch GlanceMap (existing UI, no new screen)
  └─ GpxWifiReceiveService (started when the app opens)
       ├─ NsdManager advertises _glancemap._tcp
       ├─ GpxWifiUploadServer serves GET / and POST /upload
       └─ GpxRepositoryImpl.saveGpxFileAtomic -> app_gpx/ -> SyncManager
```

### Watch receiver

- `GpxWifiReceiveService` is a `dataSync` foreground service started from `MainActivity.onCreate`
  and stopped from `MainActivity.onDestroy`. It shows a low-importance ongoing notification.
- The service starts only when the watch has a Wi-Fi IPv4 address. It registers a
  `NetworkCallback` and retries when Wi-Fi becomes available.
- `GpxWifiUploadServer` binds a `ServerSocket` on `0.0.0.0` with an ephemeral port.
- `GpxWifiAdvertiser` registers `_glancemap._tcp.` with TXT attributes:
  `v=1`, `name=<device name>`, `token=<6-digit token>`.
- A `MulticastLock` is held while the advertiser is registered.
- Handler routes:
  - `GET /?t=<token>` -> self-contained Chinese HTML upload page.
  - `POST /upload?t=<token>&name=<file>` -> raw GPX body, validation, atomic save.
- The server returns JSON `{"ok":true,"name":...,"sha256":...,"size":...}` or
  `{"ok":false,"error":...}`.

### Phone sender

- `WifiWatchRepository` discovers `_glancemap._tcp`, resolves the service, and emits
  `WatchNode(id="wifi://<host>:<port>?t=<token>", displayName=<name>)`.
- `FileTransferService` combines these nodes with the existing Data Layer watch flow. The
  existing `FileTransferUiState.availableWatches` drives the unchanged UI.
- `BatchTransferRunner` detects a Wi-Fi node, skips Data Layer preflight/status calls, and uses
  `WifiUploadStrategy` for every file. Only `.gpx` files are sent; other selected files are
  reported as skipped.
- `WifiUploadStrategy` streams the content URI to `POST /upload`, reports the existing
  progress text, and verifies the returned SHA-256 against the prepared checksum.
- Pause/resume uses the existing cooperative `awaitIfPaused()`; cancel closes the connection
  because the strategy implements `AutoCloseable`.

### No-new-UI rules

- No new Compose screens, buttons, fields, or menu entries.
- The watch receiver starts automatically; the phone's existing refresh button and Send button
  are the only user actions.
- When the user selects a Wi-Fi watch, the existing status message may show
  `Wi-Fi: http://<host>:<port>/?t=<token>` so the browser fallback URL is visible. This is text
  in an existing element, not new UI.
- The existing `No watches found.` message is replaced by the discovered Wi-Fi watch. If the
  watch app is not open, the message remains and the user opens GlanceMap on the watch.

## Localization

- Add complete `values-zh/strings.xml` to both modules.
- Add `zh` to the watch `locales_config.xml`.
- Preserve placeholders, `<plurals>`, `<string-array>`, HTML tags and XML escapes.
- Translate user-visible hard-coded status/toast/notification strings in the phone transfer
  service/runner and watch receiver service into resources.
- Keep product names and technical abbreviations unchanged: GlanceMap, OpenStreetMap,
  Mapsforge, BRouter, Refuges.info, GPS, GPX, POI, DEM, REC, TBT, Wi-Fi, HTTP, SHA-256.
- Standard Chinese terms: Map 地图, Route 路线, Routing 路线规划, Navigation 导航,
  Recording 记录, Elevation 海拔, Terrain 地形, Download 下载, Settings 设置,
  Watch 手表, Phone 手机, Transfer 传输, Track 轨迹, POI 兴趣点.

## Security and limits

- A random 6-digit token is required for both GET and POST. The token is in the mDNS TXT record
  and in the browser URL shown by the phone.
- The server only accepts `.gpx`, validates that the content contains `<gpx` or `<trk`, and caps
  uploads at 64 MiB.
- The server overwrites an existing same-name GPX, matching the existing transfer behavior.
- Traffic is cleartext HTTP on the local network. The phone manifest enables cleartext for this
  build. The watch app is the only receiver; no internet exposure is intended.

## Error handling

- Watch has no Wi-Fi: notification says Wi-Fi is required and offers the Wi-Fi settings action;
  the server starts when Wi-Fi appears.
- Phone cannot discover the watch: the existing watch list stays empty; the user opens the watch
  app and refreshes. mDNS failures are logged; the browser fallback can still be used if the URL
  is known.
- Upload errors: 400 invalid name/content, 401 invalid token, 409 checksum mismatch,
  413 too large, 500 save failure. The phone maps these to Chinese status messages.
- Phone has no Wi-Fi: the Send path fails early with a Chinese message.

## Validation

- `./gradlew :app:compileDebugKotlin :glancemapcompanionapp:compileDebugKotlin`
- `./gradlew ktlintCheck detekt :app:lintDebug :glancemapcompanionapp:lintDebug`
- Focused unit tests cover endpoint parsing, protocol URL construction, HTTP parsing, upload
  streaming, and the language picker.
- Device validation on Galaxy Watch7 + a China Samsung phone: the watch receives GPX over
  Wi-Fi, the returned SHA-256 matches, the existing progress/history UI updates, the browser
  upload page works, and the watch GPX list refreshes.
- Outdoor GPS/navigation and battery impact were not part of this validation.

## Non-goals

- No Wear OS Data Layer repair, no GMS/CN Wear OS patching, no system flashing.
- No KML conversion in the app; the user converts 2bulu KML to GPX before sending.
- No changes to the existing adb/`run-as` fallback.
- No new UI.
