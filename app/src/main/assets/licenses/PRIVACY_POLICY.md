# Privacy Policy for GlanceMap

Last updated: 2026-05-31

This Privacy Policy applies to GlanceMap, including the Wear OS watch app and its Android companion app, distributed under the GlanceMap name and package `com.glancemap.glancemapwearos`.

Privacy contact: `Glancemap@protonmail.com`

## Summary

GlanceMap is designed to work primarily on your own devices. Most navigation, map rendering, file storage, settings, and phone/watch transfer logic happen locally on the watch and phone.

We do not require you to create an account to use the app. We do not sell your personal data. We do not use your location or files for advertising.

## Data we access and why

### 1. Location data on the watch and companion app

- Precise or approximate location, when you grant permission.
- Used for live navigation, map centering, route guidance, ETA, compass/location features, and related foreground GPS features.
- If you choose to use Live Tracking in the companion app, phone location is sent to Arkluz to publish your live track for the group you configure.
- In the current reviewed app configuration, GlanceMap does not request `ACCESS_BACKGROUND_LOCATION`. Location permission is requested for foreground use.

### 2. Files and user content

- Files you choose to import, open, generate, store, transfer, or export, including `.gpx`, `.map`, `.poi`, `.rd5`, and diagnostics files.
- Used to display maps, routes, points of interest, routing data, transfers between phone and watch, and optional diagnostics exports.
- These files may contain personal or sensitive information depending on what you place in them.

### 3. Device, app, and connection information

- App version, device model, Android/Wear OS version, notification state, location/Bluetooth/notification permission state, Bluetooth/Wi-Fi/network state, nearby watch information, transfer state, selected watch identifiers, and local file metadata such as file names and sizes.
- Used for pairing, transfers, compatibility, notifications, debugging, and performance.

### 4. Diagnostics and crash information

- The app can store local crash notes and local diagnostics logs on-device.
- If you manually export diagnostics, the report may include device and app details, log lines, crash stack traces, transfer information, timestamps, selected file names, and, if diagnostic capture is enabled, location or GNSS-related telemetry.
- Diagnostics are intended for troubleshooting and are only shared off-device when you explicitly choose to send or export them.

### 5. Settings and local preferences

- Preferences such as units, GPS interval, map options, last import or download parameters, transfer history, and theme selections.
- Live Tracking settings such as group name, participant password, viewer password, participant name, optional notification email addresses, optional alert email addresses, no-movement alert interval, draft comments, and selected GPX reference.
- Used to remember your setup and improve usability across launches.

## When data leaves your device

Data may leave your device in the following situations:

### 1. User-requested downloads from third-party data providers

When you request online POI, routing, elevation, map-picker previews, or similar data, the app sends the information needed to fulfill that request to the selected provider, such as a user-selected geographic area (bounding box), requested routing tile names, map display tile coordinates, or requested files.

As with normal internet requests, those providers may also receive standard request metadata such as your IP address, request time, and user-agent or similar connection information.

Providers used by the current app configuration include:

- `refuges.info`
- `overpass-api.de`
- `tile.openstreetmap.org`
- `brouter.de`
- `download.mapsforge.org`
- `arkluz.com`

If you open third-party map or GPX download websites from the companion app, those websites operate under their own privacy policies.

### 2. Optional Live Tracking through Arkluz

When you start Live Tracking from the companion app, the app sends GPS position, timestamp, accuracy, optional altitude and speed, battery level, group name, participant password, participant name, optional notification email addresses, optional alert email addresses, and no-movement alert settings to Arkluz.

If you add a GPX route or comment, those are also uploaded to Arkluz for the live tracking group. If network delivery fails, the app may temporarily store queued GPS points locally and retry sending them later. Arkluz live tracks and positions are expected to be automatically deleted by Arkluz after 7 days.

### 3. Phone/watch file transfer

Files you choose to send are transferred between your phone and watch through Google Wear OS communication channels and, in some cases, a token-protected local Wi-Fi HTTP transfer on your local network.

We do not operate a dedicated cloud service for these transfers.

### 4. User-initiated diagnostics export

If you choose to export or email diagnostics, the selected diagnostics content is attached to an email draft or shared using your chosen app. Your email provider or other selected destination then handles that transmission under its own policies.

### 5. Optional Firebase services, if configured in a release build

The GlanceMap codebase contains an optional future integration path for Firebase Analytics and Firebase Crashlytics.

In the currently reviewed app configuration, Firebase is not enabled, and GlanceMap does not send analytics or crash reports to Firebase.

If Firebase is enabled in a future release, Google or Firebase may process analytics events, crash diagnostics, device or app metadata, and related performance information under Google's terms and privacy practices. The Google Play Data safety form and this policy should then be reviewed again before release.

## Sharing

We may share data only as needed to provide app features you choose to use:

- With Wear OS / Google Play services communication components used to connect the phone and watch and move data between them.
- With third-party providers you directly use for map, POI, routing, or terrain downloads.
- With Arkluz when you use the optional Live Tracking feature.
- With your chosen email or sharing app and downstream provider when you manually send diagnostics or exported files.
- With Firebase or Google only if Firebase is enabled in the release you install.

We do not sell your personal data.

## Storage, retention, and deletion

- Imported maps, GPX files, POI databases, routing packs, settings, and similar working data are generally stored locally on your devices until you delete them, clear app data, or uninstall the app.
- Temporary caches may be removed automatically or when you use built-in cleanup actions.
- Transfer history, last-used download or import parameters, and help state may remain locally until cleared, overwritten, or removed with app data.
- Live Tracking group settings, passwords, email settings, draft comments, selected GPX reference, and queued retry positions may remain locally until cleared, overwritten, sent, removed by logout, removed by cleanup, or removed with app data.
- Local crash and diagnostics files may remain on-device until overwritten, cleared, or the app is uninstalled.
- Diagnostics files prepared for email may remain in app cache until cleaned by the app, the system, or you.
- GlanceMap does not require an account, so there is no account-based cloud profile to delete.

## Security

- The app primarily stores data in app-private storage areas and uses Android `FileProvider` for controlled file sharing where needed.
- Requests to third-party data providers in the current app configuration generally use HTTPS.
- Live Tracking requests to Arkluz use HTTPS in the release configuration.
- Local phone-to-watch Wi-Fi transfer currently uses a token-protected local HTTP connection on your network and should not be treated as end-to-end encrypted internet storage.
- No method of storage or transmission is perfectly secure, and third-party services or apps you choose to use have their own security practices.

## Your choices

You can:

- Grant or deny permissions such as location, notifications, and Bluetooth access.
- Decide which files to import, generate, transfer, or export.
- Decide whether to create or join a Live Tracking group, start/stop Live Tracking, add GPX/comments, or configure notification and alert emails.
- Decide whether to start diagnostics capture or send diagnostics by email.
- Manage or delete locally stored files, caches, and app data from the app or Android system settings.
- Uninstall the app to remove locally stored app data from that device, subject to normal Android behavior and any files you exported elsewhere.

## Children

GlanceMap is not designed as a children's app.

## Changes to this policy

We may update this Privacy Policy as the app changes. We will update the "Last updated" date when we make material changes.

## Contact

Privacy questions or requests can be sent to:

`Glancemap@protonmail.com`
