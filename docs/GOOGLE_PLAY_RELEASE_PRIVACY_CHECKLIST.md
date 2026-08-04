# Google Play Privacy Release Checklist

Last reviewed: 2026-05-31

This checklist is a repo-side release aid for GlanceMap. It is not the public privacy policy.

## Official Google Play references

- Developer Program Policy: https://support.google.com/googleplay/android-developer/answer/16070163?hl=en
- Data safety form help: https://support.google.com/googleplay/android-developer/answer/10787469?hl=en
- Sensitive permissions and APIs: https://support.google.com/googleplay/android-developer/answer/16558241?hl=en
- Native debug symbols: https://developer.android.com/build/include-native-symbols

## Useful Play definitions

- Google Play defines "`collect`" in the Data safety form as transmitting data off a user's device.
- Data processed only on-device does not need to be disclosed as collected in the Data safety form.
- The Data safety form must describe the sum of all behaviors across all artifacts currently distributed under the same package on Google Play.

Repo-specific inference:

- Based on the current source, on-device navigation/location handling that stays on-device likely does not count as "`collected`" for the Data safety form.
- Re-check that conclusion if a release build sends location, diagnostics, analytics, or telemetry off-device.

## Current repo audit

- Public privacy policy source file: `licenses/PRIVACY_POLICY.md`
- GitHub Pages privacy policy page source: `docs/privacy-policy.md`
- Planned public privacy policy URL:
  `https://glancemap.github.io/GlanceMap/privacy-policy/`
- The current policy text covers:
  - app name and privacy contact,
  - personal/sensitive data types accessed and used,
  - off-device sharing/transmission paths and parties,
  - security handling notes,
  - retention/deletion behavior.
- No account creation or sign-in flow was found.
- No ads SDK or ad flow was found.
- No `ACCESS_BACKGROUND_LOCATION` permission was found.
- Watch app requests location permissions for foreground navigation features.
- Companion app requests Bluetooth and notification permissions for watch discovery and transfer UX.
- Third-party network requests exist for user-requested POI, routing, terrain downloads, and route weather. Route weather sends a GPX-derived coordinate and optional GPX elevation to Open-Meteo only when the user chooses to load or refresh a forecast in the foreground; it does not use the phone's live location.
- Optional companion Live Tracking sends location, participant/group settings, optional emails, optional GPX route, and optional comments to Arkluz when the user starts or updates a tracking session.
- User-initiated diagnostics export exists and may include device, crash, transfer, and location-related troubleshooting details.
- Firebase release setup documentation exists, but `google-services.json` was not found in either app module in the checked-in repo state.

## In-app privacy access points implemented in this repo

- Watch app: `Settings` -> `Licenses` -> `Privacy Policy`
- Companion app: main screen -> `6. Help & Privacy` -> `Privacy policy`

## Release-time checks

1. Enable GitHub Pages from repository settings with source
   `Deploy from a branch`, branch `main`, folder `/docs`.
2. Publish the privacy policy at a stable public HTTPS URL and place the same URL in the Play Console privacy policy field.
   - GitHub Pages is acceptable for this if the page is active, public,
     non-geofenced, and renders as a normal web page rather than a PDF or
     editable document view.
3. Keep the hosted text in sync with `licenses/PRIVACY_POLICY.md`.
4. Complete the Data safety form for the total behavior of every artifact currently distributed for `com.glancemap.glancemapwearos`.
5. Re-review the Data safety form before release if you add `google-services.json`, enable Firebase, add any new SDK, or start transmitting new data off-device.
6. Re-review whether user-initiated diagnostics export changes your Data safety answers, especially for crash logs, app info, and any location-related telemetry captured in support files.
7. Re-review whether any user-selected or location-derived bounding box requests to third-party services should be reflected in your Play disclosures for the exact shipped flow.
8. Re-review Live Tracking Data safety answers when Arkluz payloads, retention, or configured endpoint change.
9. Do not claim stronger security than the app actually provides. In particular, the current local phone-to-watch Wi-Fi transfer is token-protected but uses local HTTP, not end-to-end encrypted internet transport.
10. If Play Console shows the native debug symbols warning, check
   `docs/GOOGLE_PLAY_NATIVE_SYMBOLS.md`. The current Gradle release config
   already enables `SYMBOL_TABLE`; Play may still warn when the only bundled
   native library is the already-stripped AndroidX `libandroidx.graphics.path.so`.
11. Re-review the Data safety form and Open-Meteo's applicable terms before any
    release that includes route weather. The current integration transmits a
    GPX-derived coordinate and optional elevation only after an explicit user
    action; verify the disclosure and the provider arrangement remain accurate
    for the exact shipped flow.

## Practical submission note

The current repo implementation gives you:

- a user-facing privacy policy document,
- an in-app privacy policy entry point in both apps,
- and a source-controlled checklist for Play review prep.

You still need to host the policy publicly before submitting to Google Play.
